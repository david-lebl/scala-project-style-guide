# <Title>

| | |
|---|---|
| Change | `<yyyy-mm-dd>-<slug>` |
| Status | Draft / **Approved <yyyy-mm-dd>** |
| Branch | `feature/<slug>` (local only) |
| Source | <issue link, ticket, or "conversation"> |

## Summary

<Two to four sentences: what changes for whom, and why now.>

## Context

<The problem, what prompted it, the current behaviour, and the relevant constraints. Link to code
(`path/File.scala:line`) instead of describing it at length.>

## Requirements

Confirmed with the requester. Each is testable on its own.

### R1 — <short name>
<What must hold.>

Acceptance criteria:
- [ ] AC1.1 Given … when … then …
- [ ] AC1.2 …

### R2 — …

## Assumptions

Decisions taken without asking, because the codebase or the style guide makes them the clear
default. The reviewer may overturn any of them.

- A1 <assumption> — because <reason>.

## Open points

Questions still undecided. **Blocking** ones must be resolved before approval. Non-blocking ones
proceed with the stated default and are listed again in the PR description.

| # | Question | Blocking | Default if unanswered | Owner |
|---|---|---|---|---|
| O1 | … | yes / no | … | … |

## Out of scope

- <Explicitly excluded behaviour or work, and where it belongs instead (follow-up, other context).>

## Design

**Affected bounded contexts and modules:** `ordering-core`, `ordering-infra`, …

**Public contract changes** (public package of `*-core`; the Cardinal Rule):

| Change | Before | After | Compatibility |
|---|---|---|---|
| New method | — | `def cancelOrder(id: OrderId): IO[OrderError, Unit]` | additive |
| New error case | — | `OrderError.X` / `ORD-007` / 409 | additive |

_None_ when the public package does not change.

**Approach:** <Domain model changes, ports, adapters, wiring in `app`, error cases and codes,
configuration. Reference the style-guide chapters that apply (e.g. `310-persistence.md`).>

**Cross-context interaction:** <Anti-corruption port in the consumer's core and adapter in its
infra, or "none".>

## Tasks

Executed in order; each task leaves the build green and is committed separately.

| Task | Title | Covers | Modules |
|---|---|---|---|
| T1 | … | R1 | `orderingCore` |
| T2 | … | R1, R2 | `orderingInfra` |

### T1 — <title>

- **Goal:** <one sentence>
- **Covers:** R1 (AC1.1, AC1.2)
- **Changes:** <files and packages to add or change, with intended visibility>
- **Tests:** <which tests, in which module; property-based where a rule spans an input space>
- **Done when:** <observable criteria beyond green gates>

### T2 — …

## Risks

- <Risk> — <mitigation>.

## Verification

- Gates: `.claude/scripts/scala-gates.sh` (lint, scalafmt, compile with fatal warnings, tests).
- <Any extra manual or integration verification for QA.>
