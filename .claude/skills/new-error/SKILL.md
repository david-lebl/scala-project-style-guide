---
name: new-error
description: Scaffold or extend a bounded context's error enum (sealed enum + nested Code + httpStatus + message) following guide 03. Use when the user asks to add an error type, define error codes, model failure cases, or create a new error enum.
argument-hint: [context-name] [CaseName,CaseName,...]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Generate or extend a `<Ctx>Error` enum that matches the exact pattern in guide 03.

## Pre-flight

1. Read `guides/03-error-model.md` in full. The enum shape (nested `Code`, `httpStatus`, `message`) comes from §2 there.
2. Locate the context's public package: `<root>/<ctx>-core/src/main/scala/<base-pkg>/<ctx>/`.
3. Check whether `<Ctx>Error.scala` already exists:
   - **Exists:** this is an *extend* operation — parse the current file, add new cases without touching existing ones, and preserve the `Code` order. Never reshuffle existing codes: they are stable contract.
   - **Does not exist:** this is a *create* operation — produce the whole file.

## Inputs

- `$ARGUMENTS[0]` — required. Context name (kebab or PascalCase — normalise both).
- `$ARGUMENTS[1]` — required. Comma-separated case names in PascalCase (`EmptyItemList,ItemNotFound,PaymentDeclined`). Each becomes an enum case.
- If the user gives the fields for a case (e.g. `ItemNotFound(catalogueNumber:String)`), honour them. Otherwise produce a no-arg case and let the user refine it.

## Checklist

1. Normalise the context name: `ordering` → `Ordering`. The enum is `OrderingError`.
2. Read guide 03 §2 for the exact enum shape. Key elements:
   - `enum <Ctx>Error(val code: <Ctx>Error.Code, val details: String)`
   - `def message: String = s"[${code.value}] $details"`
   - Cases extend the base constructor with a `Code` and a message string.
   - Nested `object <Ctx>Error { enum Code(val value: String, val httpStatus: Int) { ... } }`.
3. For each requested case:
   - Pick a short, stable `Code.value` like `ORD-001`, `ORD-002` using the repo's existing pattern. Read existing codes in the file (or siblings) to continue the numbering. Never recycle a retired code.
   - Pick a reasonable `httpStatus` (`400` for client-input errors, `404` for not-found, `409` for conflict, `422` for domain rejection). If unclear, default to `400` and note it.
   - Write the details string — interpolate any fields passed in the case.
4. Emit scaladoc on every case: one line describing the *domain* condition (not the technical one).
5. Preserve file formatting: follow the style of the existing file if extending; match `OrderError.scala` in `examples/` if creating.
6. Run compile.

## Invariants to enforce

- One error enum per bounded context. No second `<Ctx>Errors.scala`, no per-service error types.
- The enum lives in the public package `<base-pkg>.<ctx>` — not in `impl`.
- Every case has a `Code` value that never changes. Extending is additive only.
- Infrastructure failures (DB down, network) are **defects** per guide 03 §1 — do **not** add cases like `DatabaseError`, `NetworkTimeout` to this enum.
- Error messages carry structured context, not just strings. A `case ItemNotFound(catalogueNumber: String)` is right; a `case ItemNotFound(msg: String)` is wrong.

## Output format

```
<create | extend> <Ctx>Error in <path>

Added cases:
  - <CaseName>   code=<ORD-007> httpStatus=<400>

File: <path>
Compile: OK
```

If any decision was ambiguous (e.g. default httpStatus chosen), flag it with a `(default)` tag and invite the user to override.

## Common mistakes

- Renumbering existing `Code` values when extending. They are the stable contract — never reshuffle.
- Treating infrastructure failures as domain errors (`DatabaseUnavailable` case). They belong in the defect channel, not this enum.
- Omitting the nested `Code` enum. It is required by guide 03 §2.1.
- Using stringly-typed case fields (`reason: String`) where a typed field would do.
- Creating a second error enum for "internal" errors. Merge into the one enum or use defects.
