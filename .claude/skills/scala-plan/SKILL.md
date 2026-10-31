---
name: scala-plan
description: Plan a change to this Scala/ZIO codebase with the user - explore, clarify requirements, write a human-reviewed plan.md and tasks.json, and on explicit approval create the local feature branch for /scala-implement.
disable-model-invocation: true
argument-hint: <task description, issue text, or path to a spec>
---

# /scala-plan

Turn the request below into an approved, executable change plan. This is the only human gate
of the workflow: after approval, `/scala-implement` runs every task, review and fix without
asking. Anything left vague here becomes a guess in the code, so resolve it now.

Request: $ARGUMENTS

Never write production code in this skill. Never push. Commit only in step 7.

## 1. Set up the change directory

- Derive a kebab-case `<slug>` of at most five words from the request. The change directory is
  `docs/changes/<yyyy-mm-dd>-<slug>/` (date from `date +%F`).
- If a directory for this slug already exists and its `tasks.json` has `"approved": false`, you
  are resuming: read it and continue at the step its content implies. If it is already approved,
  stop and point the user to `/scala-implement <slug>`.
- Run `git status --short` and `git branch --show-current`. Note any uncommitted changes. They
  must be resolved by the user before step 7, so mention them early.

## 2. Understand the codebase

Read `CLAUDE.md` and the `scala` skill's `SKILL.md`. Then launch up to three **Explore**
subagents in parallel, each with one focus, and ask each for conclusions with `file:line`
references rather than file dumps:

- the bounded context(s) and modules the request touches: public contract (service trait, DTOs,
  error enum and codes), `impl` domain model, ports, `Live`;
- the infra adapters, `app` wiring, configuration and `build.sbt` dependencies involved;
- the existing tests for that area and the patterns they use.

From this, identify which style-guide chapters apply (`.claude/skills/scala/*.md`) and read
them in full.

## 3. Clarify with the user

List every unknown, then sort each one:

- **Answerable from the code or the style guide.** Decide it yourself and record it under
  *Assumptions* with the reason.
- **Needs the user.** Ask it with `AskUserQuestion`:
  - at most 4 questions per round;
  - each question self-contained;
  - concrete options, with the recommended one first and marked "(Recommended)".
  Typical topics: behaviour on edge cases and errors, error codes and HTTP statuses, public API
  shape, idempotency, persistence or migration, configuration, what is out of scope.
- **The user can't answer it now.** Record it under *Open points*, mark it blocking or not, and
  give a default.

Repeat until nothing blocking is left that the user can answer.

## 4. Write the plan

Copy the templates from this skill's `templates/` directory into the change directory and fill
them in:

- `plan.md`: every section. Requirements `Rn` carry acceptance criteria `ACn.m` phrased as
  given/when/then. Public contract changes are listed with exact signatures, error cases and
  codes, or *None*.
- `tasks.json`: `slug`, `title`, `changeDir`, `createdAt`, `requirements`, and `tasks` mirroring
  the plan's task table. `id`, `title`, `covers` and `modules` (sbt project ids) must match
  `plan.md`.

Task rules:

- tasks run strictly in order, and each one leaves the build green and is committed on its own;
- one concern per task, e.g. domain model and port → service logic → infra adapter → wiring/HTTP;
- size each task for one focused session, about a few files plus their tests. Split anything
  bigger;
- each task names its tests and its *Done when* criteria;
- every `Rn` is covered by at least one task.

## 5. Critique

Dispatch the `plan-critic` subagent with the path of `plan.md`. Fix every Critical and Important
issue it raises. Questions it raises for the user go through step 3. Run the critic at most twice.

## 6. Human review

Present the plan in chat:
- summary;
- the requirements with their acceptance criteria;
- assumptions;
- open points;
- out of scope;
- public contract changes;
- the task table;
- the path to `plan.md`.

Ask the user to review it, either by editing the file directly or by replying with changes.
Apply the changes, keep `tasks.json` in sync, and present the deltas. Repeat.

Approval must be explicit (e.g. "approve", "approved", "go"). Silence, or approval of only part
of the plan, does not count. Do not approve while a blocking open point remains.

## 7. Approve and hand over

1. Uncommitted changes outside the change directory: stop and ask the user to commit or stash
   them first.
2. Record the base, then branch:
   - `base=$(git rev-parse HEAD)`;
   - if the current branch is `main` or `master`, create the branch with
     `git switch -c feature/<slug>`. Otherwise ask whether to branch from the current branch or
     from `main`.
3. Update `tasks.json` with `jq`:
   - `approved: true`;
   - `approvedAt`: today's date;
   - `branch`;
   - `baseCommit: $base`;
   - `phase: "approved"`.
   Set `plan.md`'s Status row to **Approved <date>**.
4. `git add <changeDir> && git commit -m "docs(<slug>): approved change plan"`.
5. Tell the user: the plan is committed on `feature/<slug>`, nothing was pushed, and the next
   step is `/scala-implement <slug>`, ideally in a fresh session (`/clear`) so the run starts with
   a clean context.
