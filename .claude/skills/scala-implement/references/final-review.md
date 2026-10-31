# Final review loop

Runs once every task is `done`, when `next` returns `FINAL_REVIEW <k>/<max>`. It reviews the whole
change, and nothing reviews after it, so findings are verified before anything is fixed. `S` is
`.claude/scripts/run-state.sh <changeDir>`, `G` is `.claude/scripts/scala-gates.sh`.

Range: `<baseCommit>..HEAD`, excluding the change docs: `git diff <base>..HEAD -- . ':(exclude)docs/changes'`.

## One round

1. `S phase final-review` (first round only), then `k=$(S final round)`.
2. **Gates:** run `G` on the whole build. On FAIL, dispatch `scala-fixer` with the GATE finding
   (see the task loop, step B) before reviewing.
3. **Review:** dispatch in parallel, as one message with four Agent calls, and wait for all of them:
   - `scala-spec-reviewer`, scope `final`;
   - `scala-quality-reviewer`, scope `final`, lens `correctness-effects`;
   - `scala-quality-reviewer`, scope `final`, lens `architecture-domain`;
   - `scala-test-reviewer`.

   Each gets: the change directory, the range above, every open or parked finding so far (task and
   final, with rulings), and from round 2 the previous round's outcomes. Tell them: "Do not
   re-report parked or declined findings unless they got worse."
   Save the replies as `<changeDir>/reports/final-r<k>-<spec|correctness|architecture|tests>.json`.
4. **Merge:** collect the Critical and Important findings and prefix ids with the lens to keep
   them unique (`architecture/QUAL-2`). Keep Minor findings for the report only.
   - No Critical or Important candidates: `S final record '{"candidates": 0, "confirmed": 0}'`,
     then `S final complete "clean round <k>"`, then go to the docs phase.
5. **Verify:** dispatch `finding-verifier` with the candidates and the parked findings. Save the
   reply as `reports/final-r<k>-verify.json`.
   - `confirmed` = the results with decision `CONFIRMED` and severity Critical or Important.
   - Empty: record the round, then `S final complete "no confirmed findings in round <k>"`.
6. **No-progress check:** if every confirmed finding repeats one confirmed in round k-1 (same
   file, same problem) and the fixer declined or failed it then, park those findings with the
   ruling `"no progress across rounds"`, record the round, and run
   `S final complete "no progress"`.
7. **Fix:** dispatch `scala-fixer` with `Scope: final`, `Round: <k>`, and the confirmed findings.
   Park its declines with `S park final '{…, "ruling": "declined: <reason>"}'` and add them to
   issues.md.
8. **Verify and commit:** run `G`, then `git add -A`, then
   `git commit -m "fix(<context>): address final review round <k>"`, with a body listing the
   finding ids fixed.
9. **Record:** `S final record '{"candidates": n, "confirmed": n, "refuted": n, "fixed": [ids], "declined": [ids], "commit": "<sha>"}'`,
   then add a progress row.

Then ask `next` again: it returns another round, `FINAL_REVIEW_EXHAUSTED`, or `DOCS`.

## FINAL_REVIEW_EXHAUSTED

The round cap was reached, and the last round ended with fixes that no later round reviewed.
Park every confirmed finding from the last round that was not fixed, with the ruling
`"unresolved at round cap"`. Note in issues.md that the round-<max> fixes were gate-verified but
not re-reviewed. Then run `S final complete "round cap reached"`.
