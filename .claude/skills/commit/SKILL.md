---
name: commit
description: Create a git commit that matches the project's commit style, after verifying format and compile pass. User-invocable only — never auto-committed by the model.
argument-hint: [optional commit-message hint]
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git add *) Bash(git commit *) Bash(git rev-parse *) Read
---

Stage explicit paths, run pre-flight checks, and commit using this repo's message style. Only the user triggers this skill.

## Pre-flight

1. `git rev-parse --show-toplevel` to confirm you are inside a git repo and know its root.
2. `git status --short` (never `-uall`) to see what is staged and unstaged.
3. `git log --oneline -n 10` to study the repo's commit message style. Match it — do not invent a new convention. In this repo commits use a short imperative subject, optionally with a `feat:` / `fix:` / `docs:` / `refactor:` prefix. Follow whatever pattern is dominant in the last 10 commits.
4. `git diff --stat` and `git diff --cached --stat` to understand scope.

## Inputs

`$ARGUMENTS` — optional free-text hint for the commit message. Use it to inform the subject; do not paste it verbatim without thinking about style.

## Checklist

1. Inspect status and diffs (see Pre-flight).
2. Decide if there is anything to commit. If no changes, stop with `nothing to commit`.
3. Run the `format` skill's command (`sbt scalafmtAll scalafmtSbt` or `mill __.reformat`). If this modifies files, include them in the commit only if the user wanted formatting folded in — otherwise stop and ask.
4. Run the `compile` skill's command (`sbt compile` or `mill __.compile`). If it fails, abort the commit and show the errors.
5. Stage only files the user has been working on. Do **not** `git add -A` or `git add .` — name paths explicitly. Skip anything that looks like a secret (`.env`, `credentials*`, `*.pem`).
6. Draft the commit message:
   - Subject line: imperative, under 72 chars, match the style from `git log`.
   - Body (optional): one paragraph on *why*, not *what*. Reference the guide number if the change enforces or updates one (e.g. "per guide 06 §2").
7. Commit using a HEREDOC so quoting cannot mangle the body:
   ```sh
   git commit -m "$(cat <<'EOF'
   <subject>

   <optional body>

   Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
   EOF
   )"
   ```
8. `git status` afterwards to verify the commit landed.

## Invariants to enforce

- **Never** `--no-verify` / `--no-gpg-sign` unless the user explicitly asked.
- **Never** amend a previous commit unless the user explicitly asked. Make a new commit.
- **Never** `git push`. Pushing is a separate user decision.
- **Never** stage files that look like secrets without asking.
- If a pre-commit hook fails, fix the underlying issue — do not bypass it.
- Follow the exact style from `git log`. Do not invent Conventional Commits in a repo that does not use them, and vice versa.

## Output format

1. Staged files list.
2. The final commit message as it will be written.
3. Post-commit `git status` summary.

## Common mistakes

- `git add .` catching untracked editor files or secrets.
- Subject lines over 72 chars or written in past tense ("added X" instead of "add X").
- Summarising *what* was changed when *why* is what helps future readers.
- Amending after a hook failure — the commit did not happen, so `--amend` edits the *previous* commit. Create a new one instead.
- Auto-including a `Claude Code` footer in a repo whose log does not already contain one. Match the repo's style; if unsure, ask.