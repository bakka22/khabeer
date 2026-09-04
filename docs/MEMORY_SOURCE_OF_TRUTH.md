# Khabeer Memory System — Source of Truth

This document is the authoritative spec for the in-app memory system.
It mirrors the Hermes Agent memory system, with exactly two exclusions
(see §0). Where this doc and code disagree, this doc wins — update the code.
It supersedes `docs/HERMES_MEMORY_IMPLEMENTATION_PLAN.md` (kept for history).

Source mapped from `/mnt/d/projects/hermes-agent` (verified against
`tools/memory_tool.py`, `tools/session_search_tool.py`,
`tools/threat_patterns.py`, `tools/write_approval.py`,
`agent/memory_manager.py`, `agent/memory_provider.py`,
`agent/context_compressor.py`, `agent/background_review.py`,
`agent/native_compaction.py`, `agent/learning_graph*.py`,
`hermes_cli/subcommands/memory.py`, `hermes_cli/journey.py`).

## §0 Scope and exclusions

Mirror everything in this doc. EXCLUDED, by decision:

1. **External memory providers** (mem0, Supermemory, Honcho, Hindsight,
   RetainDB, ByteRover, Holographic, OpenViking). There is exactly one
   store: the built-in file store below. No provider selection, no lazy
   pip install, no per-user scoping, no `memory.provider` config.
2. **Mid-session automatic compaction** (batch, micro, idle, in-place-auto,
   native server-side). Auto-compaction breaks the provider prefix cache
   mid-session. Compaction is MANUAL ONLY (§5): user-invoked from the
   Memory page or session menu, plus a user-facing prompt when the context
   window is almost full (§5.1). Nothing else may archive, rewrite, or
   rotate the live transcript.

## §1 Memory layers

| Layer | Path (Termux `$HOME`) | Writer |
|---|---|---|
| `MEMORY.md` | `~/.khabeer/memories/MEMORY.md` | agent via `memory` tool; user via Memory page editor |
| `USER.md` | `~/.khabeer/memories/USER.md` | agent via `memory` tool; user via Memory page editor |
| `SOUL.md` | `~/.khabeer/SOUL.md` | user only (Memory page editor or direct file edit). NEVER a `memory` tool target |
| Staged writes | `~/.khabeer/pending/memory/<id>.json` | system (§7) |
| Locks/backups | `<file>.lock`, `<file>.bak.<unix_ts>`, temp `.mem_*` + atomic rename | system |

- Format: plain UTF-8 entries joined by `ENTRY_DELIMITER = "\n§\n"`.
  Split only on the full delimiter, never on a bare `§`.
- Caps (char-based, model-independent): `MEMORY.md` = **2200** chars
  (~800 tokens), `USER.md` = **1375** chars (~500 tokens) at 2.75
  chars/token. `SOUL.md` cap = **4000** chars. Caps are user-configurable;
  the toggles/limits live on the Memory page.
- Prompt block format (exact):
  ```text
  ════════════════════════════════════════════════
  MEMORY (your personal notes) [62% — 1,364/2,200 chars]
  ════════════════════════════════════════════════
  entry one text
  §
  entry two text
  ```
  Same shape for `USER PROFILE (who the user is)`. `SOUL.md` is injected
  verbatim as identity slot #1, not wrapped as a memory block.
- Seeding: on session start, create missing dirs/files (empty store, no
  overwrite of existing `SOUL.md`), dedupe entries order-preserving,
  sanitize for snapshot, then FREEZE `_system_prompt_snapshot`.
- Injection order in system prompt: 1. `SOUL.md` → 2. frozen memory
  blocks → 3. memory guidance (both/one/none enabled) →
  4. `SESSION_SEARCH_GUIDANCE` → 5. skills guidance.
- Frozen snapshot rule: mid-turn `memory` writes persist to disk
  immediately but NEVER mutate the live prompt snapshot. Snapshot
  refreshes on next session start. This keeps the provider prefix cache
  hot for the whole session.

## §2 `memory` tool contract

- Targets: `memory`, `user` only. Schema narrows `target.enum` to the
  enabled subset; tool hidden entirely when both stores disabled (§7).
- Actions: `add(target, content)`, `replace(target, old_text, new_text)`,
  `remove(target, old_text)`; `new_text` accepted as alias of content.
  Match semantics are SUBSTRING (`old_text in entry`), not IDs:
  0 matches → failure + `current_entries` inventory; >1 distinct
  matches → "be more specific" + 80-char previews.
- `operations[]` batch: all-or-nothing under one file lock; pre-scan all
  contents first; intermediate overflow is irrelevant — budget is checked
  on the FINAL state only; first failure aborts with "no operations were
  applied" + live state.
- Budget errors return `success:false`, `usage: "<cur>/<limit>"`, and
  `current_entries` so the model can consolidate in-turn.
- Success is TERMINAL: `success:true, done:true, usage, entry_count`,
  note "write saved — do not repeat", deliberately NOT echoing entries.
- Anti-thrash: max **3 consolidation failures per turn** (overflow /
  zero-match); beyond that a terminal "stop retrying, continue your
  reply" response. Counter resets on success and each turn.

## §3 `session_search` tool

- Modes (no `mode` param): `query` = discovery (default limit 3, roles
  `user,assistant`); `session_id` alone = read (head 20 + tail 10, scroll
  pointer for large); `session_id + around_message_id` = scroll
  (window 5); no args = browse recent sessions.
- Backend: app SQLite FTS5 index over transcript; LIKE fallback only
  where the device SQLite lacks FTS5. Snippets with match markers.
- FTS capability rule (learned on-device 2026-09-04, MIUI has no FTS5
  module): gate table/trigger creation on a full create/insert/match/
  drop probe, never on existence checks — some builds parse CREATE but
  fail on first use, and orphan triggers pointing at missing tables
  abort every message insert. Schema upgrades must also DROP such
  orphan triggers when the table is absent.
- Ranking/hydration: newest-first with automation/tool sources demoted;
  top hit hydrated full (±5 + bookends), lower hits anchor-only;
  truncation 1200 (bookends) / 4000 (messages) chars; ANSI stripped;
  compaction handoffs excluded from bookends.
- Recovery scope: search/read/scroll MUST include archived compacted
  rows (`active=0, compacted=1`) so pre-compaction detail is recoverable.
- Tool-role transcript rows stay discoverable (they hold the richest
  detail) but are DEMOTED below user/assistant hits; current-session
  lineage is skipped unless the hit is compacted history; one hit per
  lineage root; top hit hydrated with a full window, rest anchor-only
  with `>>>match<<<` snippets.

## §4 Session transcript

- Every user / assistant / tool / event boundary persists a row: id,
  session_id, role, content, tool payloads, timestamps; `active=1` live.
- Compaction archives with `active=0, compacted=1` and inserts exactly
  one `_compressed_summary=1` row; never deletes history.
- Replay rebuild for a turn = summary row(s) ordered first, then the
  protected tail of active rows. `session_search` recovery pointer
  (§5.2) must resolve through model-invoked search.

## §5 Manual compaction (ONLY compaction allowed)

- Entry points: Memory page "Compact session" card (shows provider/model,
  active replay count, readiness) and session menu. Confirmation dialog.
  Refuse while a live model turn is running.
- Structured checkpoint format (exact). Header:
  ```text
  [CONTEXT COMPACTION — REFERENCE ONLY]
  Your persistent memory (MEMORY.md, USER.md) in the system prompt is ALWAYS authoritative — never deprioritize memory due to this compaction note.
  If the newest user message reverses earlier work ("stop", "undo", "never mind"), obey the newest user message and end any in-flight work from this summary.
  ```
  Then: `## Historical Task Snapshot`, `## Goal`, `## Constraints`,
  `## Completed Actions`, `## Active State`, `## Blocked`,
  `## Key Decisions`, `## Errors & Fixes`, `## Relevant Files`,
  `## Critical Context`, `## Context Recovery` (names the
  `session_search(query, session_id)` call that recovers detail).
- Flow: generate checkpoint with the session provider → insert summary
  row → archive compacted rows → rebuild replay (summary + protected
  tail). Memory snapshot stays authoritative.

### §5.1 Near-full context gating (DECIDED)

- The app tracks replay size vs the session model's context window.
- At ≥ **90%**: prominent warning (banner + Memory page readiness
  state) urging manual compaction. Turns continue.
- At ≥ **95%**: single automatic compaction fires (same structured
  flow as §5, with a user-visible notice) to avoid a failed turn.
  This is the ONLY automatic compaction allowed — no batch, micro,
  idle, in-place-auto, or server-side passes at any other time.
- Below 90%: no automatic anything — no passes, no rotation, no
  transcript rewrites except the user's own `/retry|/undo` edits.
- Thresholds configurable on the Memory page (defaults 90 / 95).

## §6 Background review nudge

- Every **N = 10 user turns** (configurable, default 10), a quiet review
  call runs with the session provider/model/creds: no chat bubbles,
  no terminal/MCP access.
- Review returns memory `operations[]` only; they pass through the same
  gate/staging/write path as foreground writes (§2, §7).
- Prompt-cache parity is approximate (compact review request, not a full
  agent clone). No skill-review sibling. Optional notification mode
  (`off` / `on` = "Memory updated" / `verbose` = previews) to be added
  with the nudge settings.

## §7 Config and write approval (Memory page)

- Toggles: `MEMORY.md` enabled, `USER.md` enabled (tool hidden if both
  off; schema narrows otherwise), `write_approval` (agent writes stage
  for approval instead of applying), review-nudge on/off + interval,
  char-limit display with live usage %.
- Staged writes list with approve/reject; pending survives restart.
  Approved writes apply through the normal scan/budget/write path.
- Reset buttons per file. Editors for `SOUL.md` / `USER.md` / `MEMORY.md`
  with save buttons (§8).

## §8 Manual editing (Memory page, More section)

- The user can view and edit all three files from the Memory section.
  Every manual save goes through the SAME gates as agent writes:
  strict UTF-8 read (unreadable file aborts, never wipes), threat scan,
  file lock + reload, drift guard (refuse + `.bak.<ts>` on external
  rewrite), dedupe, ATOMIC temp+rename write.
- Char limits enforced on save: over-limit saves are REJECTED with the
  current usage (`<cur>/<limit>`) and entry inventory so the user can
  consolidate — never silently truncated. `SOUL.md` limit 4000 chars.

## §9 Threat scanning and storage safety

- Port of Hermes `threat_patterns.py` concepts: bounded filler regexes,
  pattern ids, NFKC normalization, invisible-unicode detection,
  prompt-injection / C2 / exfiltration / backdoor (`authorized_keys`,
  `~/.ssh`) / hardcoded-secret patterns.
- Applied on every write path AND on prompt-snapshot load. Bad on-disk
  entries render as `[BLOCKED: …]` in the prompt but stay on disk for
  the user/agent to remove via `memory remove` or the editor.
- Drift guard: if a file no longer round-trips as clean `§`-delimited
  content (or an entry exceeds the whole-file limit → external append),
  refuse the write and snapshot `.bak.<timestamp>`.
- Dedupe on load and write. Never treat an unreadable file as empty.

## §10 Journey view

- Memory page "Journey" entry lists nodes: `memory:memory:<index>`,
  `memory:user:<index>`, plus skill nodes from the skill registry.
- Edit/delete memory nodes through the same gated write path (§8);
  skill nodes route to the Skills page. Positional indexing; refresh
  after external edits.

## §11 Turn lifecycle (order)

1. Session start: seed files → load + freeze snapshot → inject prompt
   (SOUL → memory → guidance → session_search → skills).
2. Mid-turn: model may call `memory` (disk now, snapshot unchanged) and
   `session_search` (live DB incl. archived rows).
3. Every N user turns: quiet review nudge (§6) → gated writes.
4. Near-full (≥90%): user prompt to compact (§5.1). Manual compaction
   (§5) only. No automatic mid-session compaction, ever.

## §12 Android mapping (target)

- `AiMemoryStore`: §1 files/caps/snapshot, §2 tool ops + batch +
  anti-thrash, §8 manual-save gates, §9 scan/safety.
- `AiDatabase`: §4 transcript flags, §3 FTS5 (+fallback) and
  search/read/scroll/browse, §5 archive + summary rows + replay rebuild.
- `AiRuntimeService`: §11 lifecycle, §2/§3 tool exposure to all
  providers, §6 nudge scheduling + quiet calls, §5.1 usage tracking.
- `AiActivity` (More → Memory page, Journey, compaction card, pending
  approvals, editors with live usage %): §7, §8, §10, §5.
- Legacy data: on first run after this spec, migrate
  `$HOME/.termuxAI` → `$HOME/.khabeer`, `$HOME/.katheer` →
  `$HOME/.khabeer`, rewrite stale path mentions, preserve entries.

## Decisions (resolved 2026-09-04)

- Q1: warn at 90%, single auto-compact at 95% (§5.1). Manual anytime.
- Q2: full Hermes mirror (sessions table, lineage, FTS5 + trigram
  where available, LIKE fallback, all four search modes).
- Q3: grandfather existing entries (silent dedupe); enforce caps on
  new writes/edits only.
- Q4: ship everything (§6 nudge, §7 staging, §10 edit/delete,
  session titles, full session_search).
