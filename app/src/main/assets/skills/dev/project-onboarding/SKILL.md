---
name: project-onboarding
description: "Explore an unfamiliar codebase: structure, entry points, tests."
version: 1.0.0
author: Mobile Hermes
license: MIT
platforms: [linux]
---

# Project onboarding

## When to Use

The user asks about, or asks you to modify, a codebase you have not inspected in
this session yet.

## Order of operations

1. `ls` the root; read README and the build manifest (package.json,
   build.gradle, Cargo.toml, pyproject.toml) to identify the stack.
2. Map the tree one level deep:
   `find . -maxdepth 2 -type d -not -path '*/.git*' -not -path '*/node_modules*' | head -40`.
3. Find entry points: search for `main(`, `Application`, `index.*`, route
   tables, `onCreate`.
4. Locate the tests relevant to what you will touch
   (`grep -rl "@Test\|describe(" --include="*.java" --include="*.py" . | head`).
5. State your understanding of the architecture in 3-5 bullets, THEN propose
   changes. Never edit before you can explain how the piece fits in.

## Conventions

- Match existing style: imports, naming, comment density — mimic the neighbors
  of the file you edit.
- Use the project's own libraries; never assume a well-known library exists
  without checking the manifest first.
- Small inspect-before-change steps: one command, verify, next command.

## Pitfalls

- Recursive greps/finds that descend into `node_modules`, `.git`, or build
  output — always exclude them or the output is useless noise.
- Don't trust docs blindly: verify behavior in the code before claiming it.
- Generated files (build/, dist/) are not source — never hand-edit them.
