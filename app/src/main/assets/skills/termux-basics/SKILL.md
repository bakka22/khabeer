---
name: termux-basics
description: "Work in the Termux Android shell: paths, packages, storage, limits."
version: 1.0.0
author: Mobile Hermes
license: MIT
platforms: [linux]
---

# Termux basics

## When to Use

Any task that runs commands on this Android device: installing packages, reaching
shared storage, managing processes, or debugging "command not found" and
"permission denied" errors.

## Paths

- `$HOME` is `/data/data/com.termux/files/home` — project folders live under it
  or under `/storage/emulated/0` (shared phone storage).
- `$PREFIX` is `/data/data/com.termux/files/usr`. Android only allows executing
  binaries from inside the app sandbox, so never try to run scripts that live
  under `/sdcard` — copy them into `$HOME` first.
- Shared phone storage needs a one-time `termux-setup-storage` (the user runs it
  once); afterwards `~/storage/shared` mirrors `/sdcard`.
- Check free space with `df -h $HOME` before large downloads or builds.

## Packages

- Install: `pkg install <name>`. Search: `pkg search <term>`.
- Python: `pkg install python`, then `pip install <pkg>`.
- Node.js: `pkg install nodejs` (adds `node` and `npx`).
- Git: `pkg install git`.

## Common pitfalls

- `permission denied` on an executable that lives under `/sdcard` — that mount
  is `noexec`; move the file into `$HOME` and `chmod +x` it there.
- Long-running processes only survive while this app's session stays alive;
  Android may kill background work at any time. Prefer short commands.
- There is no systemd/cron daemon; do not assume services or timers exist.
- Line endings: files edited on Windows may carry CRLF — `sed -i 's/\r$//' file`
  before running shell scripts.
- `pkg` prompts (Y/n) block non-interactive runs: use `pkg install -y <name>`.

## Workflow

1. Inspect before acting: `uname -a`, `df -h $HOME`, `pkg list-installed | head -20`.
2. Run small commands and verify each output before the next step.
3. When a command is missing, install the package instead of working around it.
