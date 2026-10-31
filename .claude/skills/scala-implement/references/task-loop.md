# Task loop

Run this for the task `next` returned (`TASK <Tn> <status> <round>/<max>`). `S` is
`.claude/scripts/run-state.sh <changeDir>`, `G` is `.claude/scripts/scala-gates.sh`. The diff
under review is always the **staged** change (`git diff --cached`), because a task is committed
only after it passes review.

## A. Implement

Skip to the step matching the task's status when resuming: `in-progress` → A (re-dispatch), `review` → C,
`fixing` → D.

1. `S task <Tn> status in-progress`.
2. Dispatch **`scala-implementer`** with this prompt (fill every field):

   ```
   Change directory: <changeDir>
   Task: <Tn> — <title> (plan.md section "### <Tn>")
   Base: <git rev-parse HEAD>
   Earlier tasks: <one line per done task from progress.md: id, title, key types/functions it introduced>
   Rulings: <rows of issues.md "Rulings and answers" that concern this task, or "none">
   Working tree: <"clean" | "contains a partial attempt at this task from an interrupted run; inspect git status / git diff and complete it">
   ```
3. Read only its final status block. Handle it:
   - `DONE`: go to B.
   - `DONE_WITH_CONCERNS`: add the concerns to `progress.md` and pass them to the reviewers in C,
     then go to B.
   - `NEEDS_CONTEXT`: answer each question yourself if `plan.md`, the code or the `scala` skill
     settles it. Otherwise ask the user with `AskUserQuestion`, using the implementer's
     recommended answers as the first options. Add each answer to issues.md under *Rulings and
     answers*, then re-dispatch from A2 with the rulings filled in.
   - `BLOCKED`, a missing status block, or a gate hook that gave up: read the report, add a row
     to issues.md under *Problems encountered*, and ask the user with `AskUserQuestion`. Offer:
     give guidance and retry, skip the task (marked `blocked` and listed in the PR doc), or stop
     the run. To stop: `S phase blocked "<reason>"` and end your turn.

## B. Verify

1. Run `G <task modules>` yourself. Never trust a reported green.
2. On FAIL: treat the gate output as one finding
   `{"id": "GATE-<Tn>", "severity": "Critical", "problem": "quality gates fail", "evidence": "<gate summary>"}`
   and go to D with it.
3. `git add -A`, then `S task <Tn> status review`.

## C. Review

Dispatch **`scala-spec-reviewer`** and **`scala-quality-reviewer`** in parallel (one message, two
Agent calls), and wait for both:

```
Change directory: <changeDir>
Scope: <Tn>
Diff: staged changes — `git diff --cached -- . ':(exclude)docs/changes'` (new files included)
Implementer report: <changeDir>/reports/<Tn>-impl.md
Implementer concerns: <from DONE_WITH_CONCERNS, or none>
Already parked or declined: <this task's openFindings ids + problems, or none>
[Re-review only] Round <k>. Fixed findings to verify: <ids + problems>. Declined: <ids + reasons>. Check the fixes are correct and complete, and report only new problems introduced by them.
```

Save each reply verbatim as `<changeDir>/reports/<Tn>-review-r<k>-spec.json` and
`…-quality.json`, where `k` is the task's current fix round. Malformed JSON: ask the same agent
once more for the JSON only.

## D. Fix (evaluator-optimizer loop)

`actionable` = the Critical and Important findings from C (or the GATE finding from B). Minor
findings are only recorded.

- If `actionable` is empty, go to E.
- If the task's `fixRounds` < `limits.taskFixRounds`:
  1. `S task <Tn> round`, then `S task <Tn> status fixing`.
  2. Dispatch **`scala-fixer`** with the change directory, `Scope: <Tn>`, `Round: <k>`, and the
     `actionable` findings as a JSON array.
  3. From its `OUTCOMES`, accept a decline when the reason is concrete. Park each decline with
     `S park <Tn> '{"id": …, "severity": …, "problem": …, "ruling": "declined: <reason>"}'` and add
     it to issues.md under *Open findings*.
  4. Run B again (verify and stage). Then run C as a re-review, dispatching only the reviewers
     that had actionable findings.
- If the round cap is reached and `actionable` is non-empty:
  - Important: park each with the ruling `"unresolved after <max> fix rounds"` and go to E. The
    final review sees it again.
  - Critical: ask the user (accept and park / give guidance for one more round / stop the run).
    Never carry an unresolved Critical forward silently.

## E. Commit

1. Append a row to `progress.md`: task, `review clean` or `<n> parked`, fix rounds, and the
   counts of Minor findings.
2. `S task <Tn> status done`, then `git add -A`.
3. Commit with a conventional message. Pick the type from the task's nature (`feat`, `fix`,
   `refactor`, `test`) and the scope from the bounded context:

   ```
   feat(ordering): <task title>

   Task <Tn> of docs/changes/<dir>/plan.md
   Covers: R1, R2
   Review: clean | <n> finding(s) parked, see issues.md
   ```
4. Record the commit with ``S task <Tn> commit <sha>`` and add the sha to the progress row. These
   two edits go into the next commit; the closing docs commit picks up the last ones.
