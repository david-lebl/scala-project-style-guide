---
name: guide-review
description: Audit a path or the current diff against one specific style guide chosen by number or name. Use when the user asks to check something against guide N, wants a focused single-topic review, or says "review this for error-handling / persistence / ACL". Narrower than /code-review.
argument-hint: [guide-number-or-name] [path?]
allowed-tools: Bash(git diff *) Bash(git log *) Bash(git status *) Read Glob Grep
---

Read **one** specific guide end-to-end and audit the target scope against only that guide's rules.

## Pre-flight

1. Resolve `$ARGUMENTS[0]` to a guide file under `guides/`:
   - Numbers `01`–`12` → match the `NN-*.md` prefix.
   - Names like `error`, `persistence`, `api`, `testing`, `bounded` → match by keyword in the filename.
   - Ambiguous (e.g. `03` maps to both `03-error-model.md` and `03-services-capabilities-style.md`): list candidates and ask the user to pick.
2. Determine scope:
   - `$ARGUMENTS[1]` present → that path.
   - Otherwise → the current uncommitted diff (`git diff` plus staged).
3. Read `CLAUDE.md` for the project's hard rules. Read the resolved guide file in full — you will audit against every "Don't", invariant, and checklist in it.

## Inputs

- `$ARGUMENTS[0]` — required. Guide number or keyword.
- `$ARGUMENTS[1]` — optional. Path or glob to audit. Defaults to the working-tree diff.

## Checklist

1. Resolve the guide (Pre-flight step 1). If ambiguous, stop and ask.
2. Read the full guide. Extract its rules into a short mental checklist — section headers, "Don't" bullets, invariants.
3. Enumerate target files. For a path, use `Glob` and `Grep`. For a diff, use `git diff --name-only`.
4. For each file, walk it against the rules. For every violation, produce `[guide NN §M] file:line — what's wrong — how to fix`.
5. If a rule in the guide has no applicable code in scope, skip it silently — do not report "rule X does not apply".
6. End with one-line verdict: `guide NN compliance: clean | N violations`.

## Invariants to enforce

- Stay on-topic. If auditing guide 05 (persistence), do not flag error-model issues — that is `/guide-review 03`.
- Cite section numbers always.
- Do not rewrite code. Pointer-and-fix only.

## Output format

```
## /guide-review <guide-id> — <scope>

Guide: guides/<resolved-filename>
Scope: <path or "working-tree diff">  (<N> files)

### Violations
- [guide NN §M] <file>:<line> — <problem>. Fix: <one line>.

### Near-misses (borderline, worth a second look)
- <file>:<line> — <short note>

### Verdict
<N violations | clean>
```

## Common mistakes

- Resolving the wrong guide when the number is ambiguous (e.g. two `03-*` files exist). Always show the user which file you picked, or ask.
- Expanding into cross-cutting review. `/guide-review 02` must stay about domain modelling, not service design.
- Quoting large slabs of the guide back at the user. They have the file — cite section numbers.
- Failing silently when `$ARGUMENTS` is empty. Ask.