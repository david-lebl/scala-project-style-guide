---
name: code-review
description: Review pending Scala changes against the repo's style guides. Use when the user asks for a code review, wants feedback on a diff, is preparing a PR, or says "check this". Reads the relevant guides/*.md based on which files changed and flags violations with file:line citations.
argument-hint: [path-or-ref?]
allowed-tools: Bash(git diff *) Bash(git log *) Bash(git status *) Bash(git rev-parse *) Bash(git merge-base *) Read Glob Grep
---

Review pending changes against the guides in `guides/`. Read-only — never edits code.

## Pre-flight

1. Read `CLAUDE.md` for the project's hard rules: the two-layer module split, the Cardinal Rule (public package is a stable contract), and the Independence Rule (core never depends on another core).
2. Determine the diff scope:
   - `$ARGUMENTS` blank → `git diff $(git merge-base HEAD origin/main)..HEAD` and `git diff` (unstaged).
   - `$ARGUMENTS` is a path → diff for that path only.
   - `$ARGUMENTS` is a ref (e.g. `main..HEAD`) → use it as-is.
3. List the changed files and classify each by architectural role. These path-to-guide mappings drive which guides you read:

   | Path pattern | Read these guides |
   |---|---|
   | `**/*-core/**/<ctx>/*.scala` (public package)  | 02 domain, 03 error, 04 service, 10 api |
   | `**/*-core/**/impl/*.scala`                    | 02 domain, 04 service, 05 persistence, 07 DI |
   | `**/*-infra/**/impl/postgres/*.scala`          | 05 persistence |
   | `**/*-infra/**/impl/http/*.scala`              | 10 api, 03 error |
   | `**/*-infra/**/impl/adapters/*.scala`          | 06 bounded contexts, 12 cross-context coupling |
   | `**/app/**`                                    | 07 DI, 01 structure |
   | `build.sbt`, `build.mill`                      | 01 structure |
   | `*.md` in `guides/`                            | (meta — flag if guide edits silently contradict examples/) |

4. Read only the guides that match. Do **not** read all guides — the review should be scoped.

## Inputs

`$ARGUMENTS` — optional. Either a path, a git ref range, or blank.

## Checklist

1. Collect the diff (see Pre-flight).
2. For each changed file, resolve its architectural role and the guides to consult.
3. Read those guides. For each, scan the "Don't", "Anti-patterns", and "Invariants" sections.
4. Walk the diff. For every violation, produce a row: `guide#§ — file.scala:line — what's wrong — how to fix`. Be specific about which rule.
5. Call out **guide-blind smells** too: duplication, dead code, unused vals, leaked mutability. Mark these as "smell" rather than "violation".
6. Call out **pleasantries** — a short "these bits are good" section. Only include 2–3 items; do not pad.
7. Check the three hard rules explicitly:
   - Does any `*-core` module depend on another `*-core` module? (Grep `libraryDependencies` / `build.sbt`.)
   - Does any new public type leak from `impl` into `com.myco.<ctx>`? (Grep for `private[<ctx>]` absence on types in `impl/`.)
   - Does any new error enum lack a `Code` or `httpStatus`? (Grep for `enum .*Error`.)

## Invariants to enforce

- Core-to-core dependency → critical violation, block the review with it.
- Infrastructure types (DAO, SQL, HTTP codec) leaked into `*-core` → critical.
- Domain using primitive types where guide 02 requires opaque types → violation.
- Mocks in tests → violation of guide 09.
- Errors as exceptions (`throw new`) in domain code → violation of guide 03.
- A service `Live` class that is **not** `private[ctx]` → violation of guide 04.

## Output format

```
## Review of <scope> (<N> files, <M> lines)

### Critical violations
- [guide 06 §2] <path>:<line> — <short description>. Fix: <one line>.

### Violations
- [guide 02 §5] <path>:<line> — ...

### Smells (not in guides, but worth a look)
- <path>:<line> — <short description>

### Nits
- <path>:<line> — <short description>

### Good parts
- <short pleasantry>
```

Keep each bullet under 120 chars. Do not reprint code snippets unless the user asked.

## Common mistakes

- Citing a guide without a section number. Always say `guide 06 §2`, not "guide 06".
- Reading *all* guides when only 2 were relevant — burns context for no gain.
- Summarising the diff before reviewing it. Reviewers don't need a recap, they need findings.
- Writing "consider doing X" when the guide says X is required. Say "required by guide N §M".
- Rewriting the code in the review. Point at the line and name the fix; the `new-*` skills do rewrites.