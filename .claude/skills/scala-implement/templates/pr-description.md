# <Title>

## Summary

<Two to four sentences: what this change does and why.>

Plan: `docs/changes/<dir>/plan.md` · Issues and decisions: `docs/changes/<dir>/issues.md` ·
Review report: `docs/changes/<dir>/review-report.md`

## Changes

| Module | Change |
|---|---|
| `ordering-core` | … |
| `ordering-infra` | … |
| `app` | … |

## Public contract changes

<Exact signatures, new error cases and codes with HTTP status, DTO changes, and their
compatibility. "None" if the public packages are unchanged.>

## Requirements traceability

| Requirement | Tasks | Commits | Tests |
|---|---|---|---|
| R1 <name> | T1, T2 | `abc1234`, `def5678` | `OrderServiceLiveSpec: cancelOrder / cancels an existing order` |

## How it was tested

- Gates on `<head>`: lint PASS, scalafmt PASS, compile (fatal warnings) PASS, tests PASS (<n> tests).
- <Integration tests or manual checks, if any.>

## QA notes

Steps a human can follow to verify the behaviour, derived from the acceptance criteria:

1. …

## Known limitations and open findings

- <Open findings with rulings, non-blocking open points from the plan with the default taken, and
  deviations from the plan.>

## Out of scope / follow-ups

- …
