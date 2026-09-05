# Feature Roadmap — Hermes Parity Build-Out

Continuation rule: after any context compaction, interruption, or long
gap, READ THIS FILE FIRST before touching code. Update the status notes
after every meaningful implementation step. Source of truth for memory
is `docs/MEMORY_SOURCE_OF_TRUTH.md`; this file governs the seven
features below. TTS is explicitly deferred (Android decision pending).

Order is fixed by the user:

1. Curator janitor — periodic audit of the skill library (stale,
   archive, prune, consolidate, rename), plus pin/adopt controls.
2. Skill usage tracking — record which skills get loaded/used so review
   and listings can prioritize.
3. Todo tracking — a model-facing structured task list per turn.
4. Branching — branch a session into a child lineage that keeps working
   while the parent is preserved.
5. Plugin architecture — runtime-loadable providers/tools instead of
   hardcoded profiles.
6. Vision/image inputs plus clip-button fix — images (and files) as
   first-class message parts; the chat clip button properly configured.
7. Web access — search/fetch/browse tool for the model.

Rules for every feature: deeply map how Hermes implements and manages
it first (files, symbols, contracts), then implement fully in the app —
production ready, no shortcuts. Workflows must be logical and crystal
clear for both agent and user: no confusing or conflicting behavior or
journeys.

## 1. Curator janitor — Status: DONE (`faf5dd87`)

Hermes map: `agent/curator.py` — state file (last_run_at/run_count/
summary), first-observation deferral, interval/idle gates, deterministic
stale (30d) / archive (90d) transitions, pin/adopt/restore semantics,
prune_builtins default ON, consolidate LLM pass default OFF,
`tools/skill_usage.py` `.usage.json` sidecar (viewed/used/patched/
authored timestamps).

App implementation: `AiSkillCurator` (state, shouldRun with deferral,
dry-run + real run, stale/archive-candidate queries), usage sidecar +
hooks in `AiSkillRegistry` (view/invoke/manage), frontmatter pin
(`khabeer-pinned`, blocks review writes + archiving), adopt (tags
review-managed), seeded exemption via `.seeded.json` manifest with APK
asset backfill, auto-run on service start, Skills page (Check library
with preview-then-confirm, status line, per-card badges, Pin/Adopt/
Archive, archived section with Restore). No LLM pass (matches Hermes
default-off consolidate). Never-used skills exempt until first use.

## 2. Skill usage tracking — Status: DONE (`faf5dd87`, built with #1)

Hermes map: `tools/skill_usage.py` telemetry feeding curator clocks
and review prioritization.

App implementation: same sidecar as #1 (`last_viewed_at`,
`last_used_at`, `last_patched_at`, `agent_authored`, `first_seen_at`);
`skillLastActivity()` drives stale/archive derivation. Review-side
prioritization by usage counts: NOT DONE (future).

## 3. Todo tracking — Status: DONE (uncommitted, unit + live verified)

Hermes map: `tools/todo_tool.py` `TodoStore` — ordered items
(id/content/status/parent), merge-by-id writes, active-only injection
with nesting (completed/cancelled dropped), caps (content/list),
re-injection after compression.

App implementation: `AiTodoStore` (same contract/bounds), `todowrite`
tool + dispatch in `AiRuntimeService`, durable snapshot in
`runs.todo_json` (v19, mobile processes die), re-injection in
`rebuildReplayFromDatabase` for resume/compaction/undo. Unit-verified
(merge, injection, nesting, caps). Live-verified on Codex
gpt-5.6-sol: model called todowrite, 2 tasks stored and confirmed.

## 4. Branching — Status: NOT STARTED

Hermes map: branch command, lineage (`parent_session_id`), session
switching and recovery semantics.
(Deep map to be filled during implementation.)

App plan: TBD.

## 5. Plugin architecture — Status: NOT STARTED

Hermes map: provider/tool plugin discovery, manifests, sandboxing.
(Deep map to be filled during implementation.)

App plan: TBD.

## 6. Vision/image inputs + clip button — Status: NOT STARTED

Hermes map: vision toolset, image message parts per provider dialect,
attachment lifecycle.
(Deep map to be filled during implementation.)

App plan: TBD. The chat clip button is currently poorly implemented
and must be reworked as part of this feature.

## 7. Web access — Status: NOT STARTED

Hermes map: web/browser toolsets, fetch policy, search backends.
(Deep map to be filled during implementation.)

App plan: TBD.
