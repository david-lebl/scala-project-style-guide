# Agentic development workflow for Claude Code

A plan → implement → review workflow for Scala 3 / ZIO projects that follow this style guide. A
human approves the plan. Agents then implement, test and review every task, run whole-change
review rounds until the result is clean, and write the PR description. Commits are local only;
nothing is ever pushed.

## Usage

```text
/scala-plan Add order cancellation with a reason code     # interactive; ends with your approval
/clear
/scala-implement                                          # autonomous; resumable at any point
```

Afterwards, read `docs/changes/<date>-<slug>/pr-description.md`, do your own review and QA, then
push and open the PR yourself.

## Flow

```
/scala-plan  ── explore (Explore subagents) → clarify (questions to you) → plan.md + tasks.json
             → plan-critic → your review/edits → explicit approval
             → git switch -c feature/<slug>, commit plan                    ◀── HUMAN GATE
/scala-implement  (controller; state machine in tasks.json via run-state.sh)
  preflight: right branch, clean tree, baseline gates green
  per task, in order:
    scala-implementer (fresh context, scala skill) ── gates enforced by its Stop hook
    controller re-runs gates → stage
    scala-spec-reviewer ∥ scala-quality-reviewer (read-only, JSON findings)
    Critical/Important → scala-fixer → gates → scoped re-review   (≤ 2 rounds)
    leftovers parked with a ruling → local commit
  final review, ≤ 3 rounds:
    gates → spec ∥ correctness/effects ∥ architecture/domain ∥ tests reviewers
    → finding-verifier (dedupe, refute false positives)
    → scala-fixer on confirmed Critical/Important → gates → commit
    stop on a clean round, no confirmed findings, no progress, or the round cap
  docs: change-doc-writer → review-report.md, pr-description.md; final commit
```

## Documents per change (`docs/changes/<date>-<slug>/`, committed on the branch)

| File | Purpose |
|---|---|
| `plan.md` | Approved plan: summary, context, requirements with acceptance criteria, assumptions, open points, out of scope, public contract changes, design, tasks, risks |
| `tasks.json` | Machine state of the run (phase, task status, fix rounds, commits, open findings) |
| `progress.md` | Ledger of every step |
| `issues.md` | Rulings and answers, deviations from the plan, problems encountered, open findings |
| `review-report.md` | Per-task and final review rounds, verdicts, resolutions |
| `pr-description.md` | Summary, changes per module, public contract changes, requirement → task → commit → test traceability, test results, QA steps, known limitations |
| `reports/` | Raw implementer, fixer and reviewer outputs |

## Building blocks

| Piece | Location | Role |
|---|---|---|
| Skills | `.claude/skills/scala-plan/`, `.claude/skills/scala-implement/` | The two commands. The implement skill holds the controller, the task loop and the final-review references, plus doc templates |
| Subagents | `.claude/agents/` | `scala-implementer`, `scala-fixer` (write); `scala-spec-reviewer`, `scala-quality-reviewer`, `scala-test-reviewer`, `finding-verifier`, `plan-critic` (read-only); `change-doc-writer` |
| Gates | `.claude/scripts/scala-gates.sh` | `style-lint.sh`, scalafmt on files changed since the base (`scalafmtFilter`), `Test/compile` with fatal warnings, tests. Fails when no test was executed |
| Style lint | `.claude/scripts/style-lint.sh` | The guide's hard rules: Independence Rule, effect boundary, nondeterminism, mutability, partiality, `Task` in core signatures, `impl` visibility. Suppress a line with `// style-lint:ignore RULE reason` |
| Run state | `.claude/scripts/run-state.sh` | `next` decides the controller's next step from `tasks.json`; all transitions go through it |
| Hooks | `.claude/hooks/` | `block-remote-git.sh` (PreToolUse, all agents), `run-guard.sh` (Stop: the controller can't quit an active run), `subagent-gates.sh` (implementer and fixer can't finish while gates fail) |
| Settings | `.claude/settings.json` | Deny rules for push, remote and PR writes; hook wiring; allow rules for sbt, local git and the scripts |
| Config | `.claude/scala-workflow.env` | `SBT_ROOT`, `SBT_CMD`, `APP_DIRS`, `REQUIRE_TESTS` |

## Design choices

- **Skill-orchestrated, deterministic where it matters.** The model orchestrates, but each rule
  with no exceptions lives in code: the next step comes from `run-state.sh next`, gates are
  enforced by hooks, pushes are blocked by deny rules and a hook, and loops are capped through
  counters in `tasks.json`.
- **Fresh context per role.** Implementers see one task. Reviewers never saw the code being
  written. The controller reads status blocks and findings, not diffs.
- **Reviews against the spec, with noise filtered out.** Reviewers report only what they can point
  to, gates own formatting and warnings, and in the final review a verifier refutes findings
  before anything is fixed. Unresolved findings are parked with a ruling and surface in the PR
  description; they are never dropped silently.
- **Sequential implementation.** sbt builds are slow and share `target/`, so only one agent
  compiles at a time. Only the read-only reviewers run in parallel.

## Research basis

- Anthropic, [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents): workflow patterns, gates, evaluator-optimizer loops.
- Anthropic, [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents): JSON task list, progress file, commits as recovery points.
- Claude Code [best practices](https://code.claude.com/docs/en/best-practices): spec-first, fresh-context review against the spec, hooks for rules without exceptions.
- VirtusLab [Orca `implement.sc`](https://github.com/VirtusLab/orca/blob/master/flows/implement.sc): plan → per-task review-then-fix → final review-and-fix loop carrying open findings → PR.
- obra [Superpowers subagent-driven development](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md): implementer status protocol, two-stage review, capped fix loops, ledger.
- [GitHub Spec Kit](https://github.com/github/spec-kit) / Kiro: specify → clarify → plan → tasks → implement.
