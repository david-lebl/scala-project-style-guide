---
name: test
description: Run the Scala test suite using sbt or mill (auto-detected) and summarise results. Use when the user asks to run tests, check that tests pass, run a single module's tests, or verify a change before commit. Targets zio-test suites per guide 09.
argument-hint: [module-or-filter?]
allowed-tools: Bash(sbt *) Bash(mill *) Bash(ls *) Bash(test *) Bash(pwd) Read Glob Grep
---

Run tests for this Scala repo. Default is the whole test suite; scope it down when the user names a module or test.

## Pre-flight

1. Read `CLAUDE.md` for canonical test commands and module names.
2. Glance at `guides/09-testing.md` to remember the testing conventions (in-memory layers, no mocks, `.exit + fails(...)` for error cases) — helpful when interpreting failures.
3. Detect the build tool the same way the `compile` skill does (`build.sbt` → sbt, `build.mill` / `build.sc` → mill; look one level down if the root has neither).

## Inputs

`$ARGUMENTS` — optional.
- Blank: run all tests.
- A module name (e.g. `orderingCore`, `orderingInfra`): run that module only.
- A test class FQN or pattern: treat as a test filter.
- Ambiguous token: prefer module interpretation, and tell the user what you chose.

## Checklist

1. Detect build tool + directory.
2. Choose the command:
   - **sbt, all:** `sbt test`
   - **sbt, one module:** `sbt "<module>/test"` (quotes required)
   - **sbt, test filter:** `sbt "<module>/testOnly <FQN or pattern>"`
   - **mill, all:** `mill __.test`
   - **mill, one module:** `mill <module>.test`
   - **mill, test filter:** `mill <module>.test <pattern>`
3. Execute. Capture stdout + stderr. Note duration.
4. If zio-test output is present, extract:
   - Total passed / failed / ignored counts.
   - Names of failing suites and the first assertion failure message per suite.
5. On compile failures inside tests, surface them like the `compile` skill does.

## Invariants to enforce

- Tests use real in-memory implementations, not mocks (guide 09). If a failing test complains about a mock, flag it as a guide violation rather than "just fix the mock".
- Integration tests live in `*-infra` with testcontainers — they can be slow. Do not skip them silently; if you skip, say so.
- Do not edit production code to make a test pass without showing the user the diagnosis first.
- Never pass `-Dtest.quiet` or equivalent flags to hide failures.

## Output format

1. One-line header: `Tool: <sbt|mill>  Dir: <path>  Target: <scope>`
2. Failing suites block (if any): one line per failing suite with the first failure.
3. Summary footer: `tests: X passed, Y failed, Z ignored (<duration>)`.

## Common mistakes

- Running `sbt test` at the repo root when the build is nested.
- Using `testOnly` without quoting — sbt eats the class name arg otherwise.
- Treating a zio-test `ignored` result as a pass. It is not — report it.
- Rerunning a single flaky test and declaring victory. Say "flaky, re-run was green" if that is what happened.