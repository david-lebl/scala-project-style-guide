---
name: compile
description: Compile the Scala project using sbt or mill (auto-detected) and surface any errors cleanly. Use when the user asks to compile, build, check the build, verify that code compiles, or before a commit. Respects -Xfatal-warnings / -Wunused:all as required by the style guide.
argument-hint: [module?]
allowed-tools: Bash(sbt *) Bash(mill *) Bash(ls *) Bash(test *) Bash(pwd) Read Glob Grep
---

Run compile for this Scala repo and surface errors with file:line.

## Pre-flight

1. Read `CLAUDE.md` at the repo root if present — it documents the canonical sbt commands and the `-Xfatal-warnings` / `-Wunused:all` policy.
2. Detect the build tool relative to the current working directory:
   - `build.sbt` present → `sbt`
   - `build.mill` or `build.sc` present → `mill`
   - Neither at the CWD root: look one level down (e.g. `examples/build.sbt`). If found, `cd` into that directory first and tell the user which directory you used.
   - If still nothing: stop and report "no recognised Scala build file found".

## Inputs

`$ARGUMENTS` — optional.
- Blank: compile the whole build.
- Single token: treat as a module name (e.g. `orderingCore`, `ordering-infra`, `app`).
- Multiple tokens: join them into one sbt task string if the user clearly meant that; otherwise ask.

## Checklist

1. Detect build tool and working directory as in Pre-flight.
2. Run the right command:
   - **sbt, whole build:** `sbt compile`
   - **sbt, one module:** `sbt "<module>/compile"` — the quotes are required for sbt to parse the slash task.
   - **mill, whole build:** `mill __.compile`
   - **mill, one module:** `mill <module>.compile`
3. Capture stdout + stderr. Note wall-clock time.
4. On failure: extract up to 10 compile errors. Prefer the `[error] path/to/File.scala:LL:CC` lines. Echo them back grouped by file.
5. On success: report `compile OK (<tool>, <duration>)`.

## Invariants to enforce

- `-Xfatal-warnings` is enforced — any warning fails compile. Do **not** propose adding `@nowarn`, disabling warnings, or editing `build.sbt` scalac options to silence the output. Fix the code.
- Do **not** edit `build.sbt` / `build.mill` to make compile pass unless the user explicitly asks.
- This skill only compiles. It does not format, test, or commit.

## Output format

Three parts, terse:
1. `Tool: <sbt|mill>  Dir: <path relative to repo root>  Target: <whole | module>`
2. On error: per-file error list, truncated to 10 with an `(N more)` footer.
3. Final line: `compile OK (<duration>)` or `compile FAILED (<N errors>)`.

## Common mistakes

- Running `sbt compile` from the repo root when the only build lives under `examples/`.
- Forgetting sbt's quoting: `sbt orderingCore/compile` usually works *accidentally* but fails once arguments contain spaces — always use `sbt "orderingCore/compile"`.
- Using sbt syntax in mill: it's `mill orderingCore.compile`, not `mill orderingCore/compile`.
- Reporting "compile OK" on a mill run that finished with no targets (mill is silent on empty targets — check the stderr summary).