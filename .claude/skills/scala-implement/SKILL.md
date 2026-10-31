---
name: scala-implement
description: Execute an approved /scala-plan change autonomously - per task implement, verify, review and fix with subagents, then iterate whole-change reviews until clean, write the PR description, and commit locally. Never pushes. Resumable.
disable-model-invocation: true
argument-hint: "[slug]"
allowed-tools: Bash(.claude/scripts/run-state.sh *) Bash(.claude/scripts/scala-gates.sh *) Bash(git add *) Bash(git commit *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *)
---

# /scala-implement

You are the **controller** of an approved change. You orchestrate and never write production or
test code yourself: implementers, reviewers and fixers are subagents with fresh contexts. Your
context stays lean. Work from status blocks, findings JSON and gate summaries, and leave diffs
and source files to the subagents.

Run argument: $ARGUMENTS

## Ground rules

- **State lives in files.** Machine state is `<changeDir>/tasks.json`, changed only through
  `.claude/scripts/run-state.sh <changeDir> …`. Re-read it with `next` before every step instead
  of relying on memory, so the run survives compaction, `/clear` and new sessions.
- **Never push.** No `git push`, no `gh pr create`, no remote changes; hooks and deny rules
  block them anyway. Commit locally, only on the run's branch.
- **No check-ins.** The human approved the plan. Do not ask whether to continue. Ask the user only
  for `NEEDS_CONTEXT` the plan can't settle, `BLOCKED`, or an unresolved Critical finding.
- **Decide, record, continue.** Each ruling, deviation, decline and problem goes into `issues.md`.
  Each step goes into `progress.md`.
- A Stop hook keeps you working while the run is active. To end your turn legitimately, finish the
  run or set `phase blocked "<reason>"`.

## Start or resume

1. Find the run:
   - with an argument, it is the directory `docs/changes/*-<slug>/`;
   - otherwise, the single directory whose `tasks.json` has `approved: true` and a phase other
     than `done`. If there are several, ask which one.
   Let `S` be `.claude/scripts/run-state.sh <changeDir>`.
2. Claim the session:
   ```bash
   mkdir -p .claude/state && jq -n --arg s "${CLAUDE_SESSION_ID}" --arg d "<changeDir>" \
     '{sessionId: $s, changeDir: $d}' > .claude/state/active-run.json
   ```
3. Check the branch:
   - the current branch must equal `tasks.json.branch`. If it doesn't and the tree is clean,
     `git switch <branch>`;
   - never run on `main` or `master`.
4. Print `S show` for the user (one block), then loop on `S next`:

| `next` | Action |
|---|---|
| `NOT_APPROVED` | Stop and tell the user to finish `/scala-plan` first. |
| `PREFLIGHT` | See [Preflight](#preflight). |
| `TASK <Tn> …` | Follow [references/task-loop.md](references/task-loop.md) for that task. |
| `FINAL_REVIEW <k>/<max>` | Follow one round of [references/final-review.md](references/final-review.md). |
| `FINAL_REVIEW_EXHAUSTED` | Follow the section of that name in [references/final-review.md](references/final-review.md). |
| `DOCS` | See [Closing documents](#closing-documents). |
| `BLOCKED <reason>` | Show the reason and ask the user how to resolve it. Then restore the phase: `implementing` if any task isn't done, else `final-review` if the final review isn't done, else `docs`. Continue the loop. |
| `DONE` | Report that the run is finished (see [Finish](#finish)). |

**Resuming with a dirty tree:** if `next` is a task in `in-progress`, `review` or `fixing`, the
changes belong to that task, so continue it (the task loop explains how). Any other dirty tree:
stop and ask the user.

## Preflight

1. Run `git status --porcelain`. It must be empty.
2. Run `.claude/scripts/scala-gates.sh --base <baseCommit>`. The baseline must be green. If it
   isn't, run `S phase blocked "baseline gates fail"`, put the gate summary in issues.md, and ask
   the user. The workflow does not fix pre-existing breakage on its own.
3. Create `progress.md` and `issues.md` from this skill's `templates/` (fill in the title and the
   preflight row) and the `reports/` directory.
4. Run `S phase implementing`.

## Closing documents

1. `S phase docs`.
2. Run `.claude/scripts/scala-gates.sh --base <baseCommit>` on the whole build. It must PASS;
   otherwise run one fixer round as in the final review. Keep the summary.
3. Dispatch **`change-doc-writer`** with the change directory, the range `<baseCommit>..HEAD`, the
   gate summary, and the templates directory `.claude/skills/scala-implement/templates/`.
4. Check its output. `pr-description.md` must exist and mention every requirement id from
   `tasks.json`. `review-report.md` must list every round. Fix gaps by re-dispatching the writer
   with the specific omissions.
5. `S docs done`, `S phase done`, then `git add -A`, then
   `git commit -m "docs(<slug>): review report and PR description"`.
6. Run `rm -f .claude/state/active-run.json`.

## Finish

Tell the user, concisely:
- what was built, task by task: title, commit and review outcome;
- the final review rounds and their stop reason;
- open findings and blocked tasks, if any;
- the gate result;
- the output of `git log --oneline <baseCommit>..HEAD`;
- the paths of `pr-description.md`, `issues.md` and `review-report.md`.

Close with the fact that nothing was pushed. Once they've reviewed the change, they publish it
themselves:
`git push -u origin <branch>` and `gh pr create --body-file <changeDir>/pr-description.md`.
