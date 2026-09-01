---
name: git-workflow
description: "Inspect, branch, commit and review changes in small verified steps."
version: 1.0.0
author: Mobile Hermes
license: MIT
platforms: [linux]
---

# Git workflow

## When to Use

Any task involving version control: checking status, committing changes,
reviewing diffs, branching, or recovering from mistakes.

## Always start with state

Run `git status && git log --oneline -5` first — know the current branch and
pending changes before doing anything else.

## Committing

1. Review EVERY change first: `git diff` (tracked) and `git status` (untracked).
2. Stage deliberately: `git add <specific files>` — never `git add -A` blindly.
3. Message: short imperative subject ("Fix title leak in copyRun"); the body
   explains WHY, not what.
4. Never commit files that look like secrets (`.env`, keys, tokens) — warn the
   user instead.

## Branching

- New work → new branch: `git switch -c <short-name>`.
- Detached HEAD? Create a branch before committing anything.

## Pitfalls

- NEVER `git reset --hard`, `git push --force`, or history rewrites without
  asking the user first — these destroy work irrecoverably.
- If identity is unset, configure it before committing:
  `git config user.name "<name>" && git config user.email "<email>"`.
- A dirty tree blocks switches/merges — `git stash` first, `git stash pop` after.

## Recovery

- Undo last commit but keep the changes: `git reset --soft HEAD~1`.
- Throwaway experiment: `git stash` → try → `git stash pop`.
- Lost commit: `git reflog` shows where HEAD has been.
