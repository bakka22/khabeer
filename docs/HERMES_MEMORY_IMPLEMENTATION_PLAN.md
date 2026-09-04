# Hermes Memory System Implementation Plan for khabeer Mobile

> SUPERSEDED by `docs/MEMORY_SOURCE_OF_TRUTH.md` — read that first.
> This file is kept for history only.

This file is the persistent checkpoint for the Hermes-style memory work.

Important continuation rule: after any context compaction, interruption, or long gap, read this file first before touching code. Update the status notes after every meaningful implementation step.

## Scope

Replicate Hermes Agent's core memory system in the Android/Termux-native khabeer app.

Explicitly excluded:

- External memory providers such as mem0, Supermemory, Hindsight, Honcho, RetainDB, ByteRover, Holographic, OpenViking.
- Automatic mid-session compaction. Compaction must be manual, or gated by an explicit user action when the context is close to full.

## Source-of-truth Hermes map

The system has five core layers:

1. `MEMORY.md` — agent curated notes.
   - Path: `$HOME/.khabeer/memories/MEMORY.md`
   - Format: strict UTF-8, plain text entries joined by `\n§\n`
   - Cap: 2,200 chars
   - Writer: agent via `memory` tool, user via Memory page

2. `USER.md` — user profile.
   - Path: `$HOME/.khabeer/memories/USER.md`
   - Format: strict UTF-8, plain text entries joined by `\n§\n`
   - Cap: 1,375 chars
   - Writer: agent via `memory` tool, user via Memory page

3. `SOUL.md` — agent identity/persona.
   - Path: `$HOME/.khabeer/SOUL.md`
   - Cap: 4,000 chars
   - Writer: user only
   - Prompt behavior: injected first, at session start. It is not a normal `memory` tool target.

4. Session transcript.
   - Path/storage: app SQLite database, `messages` table
   - Writer: runtime at user/assistant/tool/event boundaries
   - Purpose: permanent episodic memory and source for `session_search`

5. Compaction summaries.
   - Path/storage: same SQLite `messages` table
   - Flags: `_compressed_summary=1`, older rows `active=0`, `compacted=1`
   - Writer: manual compaction flow only
   - Format: structured checkpoint, not a vague paragraph

Auxiliary:

- FTS5 index over messages for `session_search`.
- Journey/memory graph view over memory entries and skills.

## Exact memory prompt format

```text
════════════════════════════════════════════════
MEMORY (your personal notes) [62% — 1,364/2,200 chars]
════════════════════════════════════════════════
entry one text
§
entry two text
```

Equivalent format applies to:

- `USER PROFILE (who the user is)`

`SOUL.md` is not wrapped as a memory block; it is injected as slot #1 identity text.

## Tool contract

`memory` tool:

- Targets: `memory`, `user`
- Actions: `add`, `replace`, `remove`
- Fields: `content`, `new_text`, `old_text`
- Batch: `operations` array
- Batch behavior: all-or-nothing; char budget checked only on final result
- Success response: terminal, tells model not to repeat
- Error response: may include `current_entries` for consolidation
- Anti-thrash: after 3 consolidation failures in one turn, force terminal skip

`session_search` tool:

- No args: browse recent sessions
- `query`: discovery search
- `session_id`: read session
- `session_id + around_message_id`: scroll around anchor
- Backend: FTS5 when available, fallback only if device SQLite lacks FTS5

## Safety/storage rules

- Strict UTF-8 reads for write paths.
- Unreadable file is not an empty store; abort to prevent wipe.
- Atomic temp+rename writes.
- File lock for read-modify-write.
- Deduplicate on load/write.
- Threat scan on write and prompt snapshot load.
- Bad on-disk entry renders as `[BLOCKED: …]` in prompt but remains on disk so user/agent can remove it.
- Drift guard for rewrite operations: if file no longer round-trips as clean `§` memory, refuse and create `.bak.<timestamp>`.

## Turn lifecycle

1. Session start:
   - Seed `SOUL.md`, `MEMORY.md`, `USER.md` if missing.
   - Load and render frozen memory snapshot into system prompt.

2. Mid-turn:
   - Agent may call `memory`.
   - Writes hit disk immediately.
   - Current prompt snapshot does not change.

3. Every N user turns:
   - Background review nudge runs.
   - Review may propose memory operations.
   - Operations pass through same memory gate/staging/write path.

4. Manual compaction:
   - Generate structured checkpoint.
   - Insert `_compressed_summary=1` row.
   - Archive compacted rows with `active=0`, `compacted=1`.
   - Rebuild active runtime replay from summary + protected tail.
   - Memory snapshot remains authoritative.

## Structured compaction summary format

Manual compaction summaries must start with:

```text
[CONTEXT COMPACTION — REFERENCE ONLY]
Your persistent memory (MEMORY.md, USER.md) in the system prompt is ALWAYS authoritative — never deprioritize memory due to this compaction note.
If the newest user message reverses earlier work ("stop", "undo", "never mind"), obey the newest user message and end any in-flight work from this summary.
```

Then include:

- `## Historical Task Snapshot`
- `## Goal`
- `## Constraints`
- `## Completed Actions`
- `## Active State`
- `## Blocked`
- `## Key Decisions`
- `## Errors & Fixes`
- `## Relevant Files`
- `## Critical Context`
- `## Context Recovery`

The recovery section must name `session_search(query='<keywords>', session_id='<session_id>')`.

## Phases and current status

### Phase 1 — Built-in memory core

Status: implemented, compile verified.

Implemented:

- `AiMemoryStore`
- `SOUL.md`, `MEMORY.md`, `USER.md` seeding under `$HOME/.khabeer`
- Character caps
- Frozen snapshot injection
- Correct `SOUL.md` treatment as user-owned identity, not tool target
- `memory` tool with add/replace/remove/batch
- Atomic batch semantics
- Overflow inventory responses
- Terminal success response
- Three-failure anti-thrash cap
- Basic threat scanning
- Strict write reads
- File locks
- Drift backup/refusal

Still missing/needs hardening:

- File locks use Java NIO `.lock`; verify behavior on target Android filesystem.

Additional hardening completed:

- Ported Hermes-style strict threat scan concepts from `tools/threat_patterns.py`: bounded filler regexes, pattern ids, NFKC normalization, invisible unicode detection, prompt-injection/C2/exfiltration/backdoor/hardcoded-secret patterns.

### Phase 2 — Memory configuration and approval

Status: implemented, compile verified.

Implemented:

- `MEMORY.md` enabled toggle
- `USER.md` enabled toggle
- Memory tool hidden if both disabled
- Target schema narrows based on enabled stores
- `memory.write_approval` equivalent toggle
- Staged memory write files under `$HOME/.khabeer/pending/memory`
- Memory page pending approval list
- Approve/reject staged memory writes

Still missing/needs hardening:

- Staged write UI is functional but plain; can be polished later.
- No bulk approve/reject yet.

### Phase 3 — Session transcript and session_search

Status: partly implemented, compile verified.

Implemented:

- Durable message storage already existed.
- Tool calls/results now write searchable historical transcript rows globally through the shared tool executors.
- Message IDs exposed in transcripts.
- `session_search` tool added globally for all providers.
- Browse/read/scroll/search modes.
- FTS5 virtual table and triggers added.
- LIKE fallback if FTS5 is unavailable.
- `session_search` read/search/scroll now includes archived compacted rows so compaction recovery can actually find pre-compaction details.

Still missing/needs hardening:

- Ranking/hydration is simpler than Hermes.
- No trigram FTS table.
- No lineage dedupe beyond simple session dedupe.
- No demotion of automation/tool/subagent sources yet because mobile schema does not carry full Hermes source taxonomy.

### Phase 4 — Background review/nudge learning loop

Status: implemented, compile verified.

Implemented:

- Config toggle for review nudge.
- Nudge interval setting stored, default 10 turns.
- Completion hook triggers review every N user turns.
- Review uses the same session provider/model/base URL/API key.
- Review is quiet and does not emit visible chat bubbles.
- Review cannot use terminal/MCP.
- Review returns memory operations and applies/stages them through the normal memory path.

Still missing/needs hardening:

- Prompt-cache parity is approximate; it uses a compact review request rather than cloning a full AIAgent.
- No skill-review sibling in this memory plan unless explicitly added later.
- No notification mode (`display.memory_notifications`) yet.

### Phase 5 — Manual compaction

Status: implemented, compile verified.

Implemented:

- Manual compaction action in Memory page or session menu.
- Manual confirmation dialog.
- Refuses to run while the current session has a live model turn.
- Structured checkpoint prompt.
- Insert summary row.
- Archive compacted rows.
- Rebuild runtime replay from summary + protected tail.
- Ensure no automatic mid-session compaction.
- Ensure `session_search` recovery pointer is included.
- Compaction summaries replay before protected tail (`_compressed_summary DESC, id ASC`).

Still missing/needs hardening:

- Runtime path is compile-verified but still needs on-device provider validation.
- The compaction summary generator uses the same quiet provider adapter as memory review; provider-specific token parameter quirks may need device/provider testing.

Additional hardening completed:

- Memory page manual compaction card now shows current session provider/model, active replay message count, and readiness/too-small status.

### Phase 6 — Journey / memory graph

Status: implemented, compile verified.

Implemented:

- Memory page entry for Journey.
- List nodes:
  - `memory:memory:<index>`
  - `memory:user:<index>`
  - skills from `AiSkillRegistry`
- Edit memory nodes.
- Delete memory nodes.
- Open skill nodes or route to Skills page.
- Optional visual/timeline polish later.

Still missing/needs hardening:

- Journey is a concrete list/graph view, not a visual graph canvas yet.
- Skill nodes open the existing SKILL.md viewer; there is no dedicated graph edge visualization.
- Memory node indexing is positional, so external file edits can shift node ids between refreshes.

### Phase 7 — Full verification

Status: implemented and locally/on-device verified for build, install, launch, memory file seeding, Memory UI reachability, Journey UI, and core memory/database unit behavior. Live provider behavior still needs real-session validation for provider-specific streaming/quiet-call quirks.

Verified:

- `compileDebugJavaWithJavac`
- `assembleDebug`
- Focused memory/database unit tests:
  - `AiMemoryStoreTest`
  - `AiDatabaseMemoryTest`
- Full `:app:testDebugUnitTest`

Verified:

- Install on connected phone (`pfww8llbfe6tr88x`) with `adb install -r`.
- Launch `com.termux/.app.AiActivity`; process stayed alive and filtered logcat showed no Termux/khabeer fatal crash.
- First-run memory file seeding on device under `/data/data/com.termux/files/home/.khabeer`:
  - `SOUL.md`
  - `memories/MEMORY.md`
  - `memories/USER.md`
- More page contains a Memory entry.
- Memory page shows:
  - Memory status/caps
  - MEMORY.md/USER.md toggles
  - Write approval toggle
  - Nudge toggle
  - Reset buttons
  - Journey card
  - Manual session compaction card with provider/model, active replay count, and readiness text
  - Pending staged writes card
  - Editable `SOUL.md`, `USER.md`, and `MEMORY.md` text areas with save buttons
- Memory page is now hosted in `ai_more_scroll`; uiautomator reports it scrollable and swipes expose all lower controls, including `Save MEMORY.md`.
- Journey opens and shows `USER.md nodes`, `MEMORY.md nodes`, and real skill nodes.
- Core prompt/file/database behavior is covered by passing unit tests:
  - `AiMemoryStoreTest`
  - `AiDatabaseMemoryTest`
- Memory approval staging is covered by unit tests:
  - stage -> pending list -> approve writes `USER.md`
  - stage -> pending list -> reject does not write `MEMORY.md`

Still need live provider validation:

- Prove a real model turn sees the frozen memory snapshot inside a new session prompt.
- Prove a real model can call the `memory` tool and write/stage through the configured gate.
- Prove `session_search` works from an actual provider turn, not only DB unit tests.
- Prove the background nudge quiet-call path works with configured provider credentials without freezing chat.
- Prove manual compaction generation works with configured provider credentials and preserves recovery through model-invoked `session_search`.

## Running notes

- 2026-09-02: Added plan file after user requested a persistent phase plan. Current repo already has phases 1–4 mostly implemented and compile-verified. Next work: Phase 5 manual compaction, then Phase 6 Journey.
- 2026-09-02: Implemented Phase 5 manual compaction. Added transactional database compaction, Hermes-style structured summary prompt, replay rebuild from active transcript, Memory page action, and compile verification. Next work: Phase 6 Journey / memory graph.
- 2026-09-02: Implemented Phase 6 Journey. Added Memory-page Journey entry, real memory node ids for USER.md/MEMORY.md, edit/delete flows backed by `AiMemoryStore.saveRaw`, skill nodes from `AiSkillRegistry`, SKILL.md viewer routing, and compile verification. Next work: Phase 7 broader build/install/on-device verification.
- 2026-09-02: Phase 7 build verification passed: `compileDebugJavaWithJavac` and `assembleDebug` both successful. ADB verification is blocked: `adb devices` and `adb devices -l` return an empty device list after restarting the ADB server. APK exists at `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk`.
- 2026-09-02: Hardening pass after audit. `session_search` now searches/reads compacted historical rows instead of active-only rows, manual compaction reads historical transcript, tool calls/results persist globally as searchable non-replay rows, and `AiMemoryStore` now has a much closer Java port of Hermes strict threat patterns. `compileDebugJavaWithJavac` passed after these changes.
- 2026-09-02: Final hardening build verification passed: `assembleDebug` successful after the `session_search` recovery/tool transcript/threat scanner changes. ADB still shows an empty device list, so install/launch and live memory-flow verification remain pending until the phone is visible to ADB.
- 2026-09-02: Added executable verification. `AiMemoryStoreTest` covers seeding/prompt injection format, Hermes strict threat blocking, batch remove+add final-budget behavior, and strict UTF-8 unreadable-file refusal without wipe. `AiDatabaseMemoryTest` covers manual compaction trimming active replay while `session_search` recovers archived/tool history. Focused tests and full `:app:testDebugUnitTest` passed, followed by successful `assembleDebug`.
- 2026-09-02: Closed the local context-size/status UI gap for manual compaction. The Memory page now shows provider/model, active replay message count, and readiness before the user confirms compaction. Re-ran `compileDebugJavaWithJavac`, full `:app:testDebugUnitTest`, and `assembleDebug`; all passed.
- 2026-09-02: Fixed the real on-device Memory page reachability bug. The More/Memory dynamic page is now wrapped in `ai_more_scroll` (`NestedScrollView`) and all page switches hide/show the scroll container consistently. Installed on connected phone, launched without fatal crash, verified memory files seeded, verified Memory page scrolls to manual compaction/pending writes/SOUL/USER/MEMORY editors, and verified Journey opens with USER.md/MEMORY.md/skill nodes.
- 2026-09-02: Hardened Windows/Robolectric test execution. Gradle/Robolectric was failing before assertions because it tried to create `.robolectric-download-lock` in the Windows home root. Unit test JVMs now set `user.home` to `build/test-user-home` and create that directory before tests. Full `:app:testDebugUnitTest` passes again with the repo-local Gradle cache.
- 2026-09-02: Attempted live OpenCode memory-tool validation from the installed app using the active `opencode · glm-5.3-flash` session. The request reached the provider and failed cleanly with HTTP 429 `GoUsageLimitError` ("Weekly usage limit reached. Resets in 4 days"). Verified on-device `MEMORY.md` remained 0 bytes afterward, so no phantom/stale memory write occurred. Live model memory/session_search/nudge/compaction validation remains blocked on provider capacity or another configured provider key.
- 2026-09-02: Added focused unit coverage for staged memory writes: stage -> pending list -> approve writes `USER.md`, and stage -> pending list -> reject does not write `MEMORY.md`. Focused `AiMemoryStoreTest` and full `:app:testDebugUnitTest` both passed.
