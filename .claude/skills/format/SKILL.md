---
name: format
description: Format the Scala codebase with scalafmt via sbt or mill (auto-detected), using the project's .scalafmt.conf. Use when the user asks to format, reformat, fix style, or prepare changes for commit.
allowed-tools: Bash(sbt *) Bash(mill *) Bash(scalafmt *) Bash(ls *) Bash(test *) Bash(pwd) Read Glob
---

Run scalafmt against the repo using the configured formatter. Safe to auto-invoke after code edits.

## Pre-flight

1. Locate the scalafmt config. Check in order:
   - `.scalafmt.conf` at repo root
   - `.scala-build/.scalafmt.conf`
   - `project/.scalafmt.conf`
   If none found, stop and tell the user.
2. Detect the build tool like the `compile` skill does.

## Inputs

No arguments. If the user passes a path, treat it as "format only this path" using `scalafmt` CLI directly.

## Checklist

1. Detect the build tool and `.scalafmt.conf` location.
2. Run the formatter:
   - **sbt:** `sbt scalafmtAll scalafmtSbt` — formats all sources and the build itself.
   - **mill:** `mill __.reformat`
   - **Neither, but scalafmt CLI is available:** `scalafmt --config <path-to-.scalafmt.conf>`
3. Capture which files were rewritten (scalafmt prints them; `git status` afterwards is a reliable fallback).
4. If the formatter reports a parse error, surface it verbatim — do not try to auto-fix syntax.

## Invariants to enforce

- Never modify `.scalafmt.conf` to make a file pass. If formatting fails on valid Scala, the file is wrong, not the config.
- Do not run `--non-interactive` flags that suppress errors.
- Running twice in a row must be a no-op. If it is not, report it — that usually means the config has unstable rewrites enabled.

## Output format

1. `Tool: <sbt|mill|scalafmt-cli>  Config: <path>`
2. List of reformatted files (up to 20, then `(N more)`).
3. Footer: `format OK` or `format FAILED: <reason>`.

## Common mistakes

- Running `scalafmtSbt` without `scalafmtAll` (leaves source files unformatted).
- Using `mill __.fix` instead of `mill __.reformat` — `fix` is scalafix, which this skill does not cover.
- Reporting success when scalafmt silently skipped files because of a parse error earlier in the run.