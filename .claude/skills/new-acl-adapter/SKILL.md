---
name: new-acl-adapter
description: Scaffold an anti-corruption layer between two bounded contexts — port + snapshot in the consumer's core, adapter in the consumer's infra — following guides 06 and 12. Use when the user asks to consume another context, integrate with context X, add an ACL / anti-corruption layer, or cross a context boundary.
argument-hint: [consumer-ctx] [producer-ctx] [strategy?]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Set up cross-context coupling without breaking the Independence Rule. The consumer defines its own view of the producer's data; the adapter bridges.

## Pre-flight

1. Read `guides/06-bounded-contexts.md` — the canonical ACL shape and the Independence Rule.
2. Read `guides/12-cross-context-coupling.md` — the three strategies:
   - **Strategy A** — merge contexts (>50% shared vocabulary, same team, lockstep). Do not use this skill; re-plan instead.
   - **Strategy B** — direct dependency on the producer's `*-core`. Simpler, couples to producer types.
   - **Strategy C** — full ACL with port + snapshot + adapter. Default for this skill.
3. Confirm both contexts exist. Glob `<root>/<consumer-ctx>-core` and `<root>/<producer-ctx>-core`.

## Inputs

- `$ARGUMENTS[0]` — required. Consumer context name (kebab).
- `$ARGUMENTS[1]` — required. Producer context name (kebab).
- `$ARGUMENTS[2]` — optional. `B` or `C`. Default `C`. If `A`, stop and ask the user to reconsider; this skill will not merge contexts.

## Checklist

1. Validate contexts exist; if not, stop.
2. Pick strategy. If the user did not specify, ask briefly using the guide-12 decision matrix (vocabulary overlap, change cadence, team structure). Default `C`.
3. For **Strategy C** (default):
   - In the consumer's `*-core/impl/`, create a port trait `<Producer>Port` with the specific capability the consumer needs (e.g. `fetchCustomer(id: CustomerId): UIO[Option[CustomerSnapshot]]`). Port returns `UIO` or `IO[<ConsumerCtx>Error, _]` — infrastructure errors are defects, domain errors translate into the consumer's error enum.
   - In the consumer's `*-core/impl/`, create one or more `Snapshot` case classes — the consumer's private view of the producer's data. Carry only the fields the consumer actually uses. Do **not** import types from `<producer>-core` into the port or snapshot.
   - In the consumer's `*-infra/impl/adapters/`, create `<Producer>Adapter` that:
     * Depends on `<producer-ctx>-core` (the only place this dep is allowed).
     * Calls into the producer's public service.
     * Translates producer types → consumer snapshots.
     * Translates producer errors → consumer errors (or `Option.empty` where "not found" is not an error).
     * Provides a `val layer` that needs the producer service.
   - Register the adapter in `app/Main.scala`.
4. For **Strategy B**:
   - No port, no snapshot. The consumer's `Live` depends directly on the producer's public service type.
   - In `<consumer-ctx>-core/build.sbt` (or mill equivalent), add a dep on `<producer-ctx>-core`. This is the **only** allowed core → core dep, and only under Strategy B.
   - Produce error-mapping helper in the consumer's `impl` if producer errors need translation.
   - Note clearly in the output: this couples the consumer to producer type changes.
5. Compile.

## Invariants to enforce

- Under Strategy C: `<consumer-ctx>-core` has **no** dependency on `<producer-ctx>-core` — the producer types appear only in the infra adapter.
- Snapshots contain only the fields the consumer needs. They are not DTOs of the producer.
- Foreign errors do not leak. Translate in the adapter; the consumer handles its own error enum.
- Under Strategy B: document the coupling in a file-level comment. Make the compromise visible.
- Async communication (events) is a separate pattern — this skill does not scaffold event consumers. If the user wants that, point them at guide 06 §4.

## Output format

```
Set up ACL: <consumer-ctx> <- <producer-ctx>  (Strategy <B|C>)

Files:
  Strategy C:
    <consumer-ctx>-core/.../impl/<Producer>Port.scala
    <consumer-ctx>-core/.../impl/<Entity>Snapshot.scala
    <consumer-ctx>-infra/.../impl/adapters/<Producer>Adapter.scala

  Strategy B:
    <consumer-ctx>-core/build-dep added: <producer-ctx>-core
    <consumer-ctx>-core/.../impl/<Producer>ErrorMapping.scala

Layer registered: <path>
Compile: OK

Notes:
  - Strategy C: consumer stays independent; port insulates from producer churn.
  - Strategy B (if chosen): consumer now couples to producer types — keep it only while both contexts are co-owned.
```

## Common mistakes

- Importing `com.myco.<producer>.*` in the consumer's `*-core`. That is the Independence Rule broken.
- Passing the producer's DTOs straight through as consumer snapshots. Snapshots are consumer-shaped; copy only the fields you need.
- Letting foreign errors leak. Adapt them, or convert "not found" to `Option.empty`, or die on truly exceptional cases.
- Using Strategy B by default. Prefer C; B is a considered compromise.
- Silent merge of contexts ("just import it"). Guide 12 has an explicit Strategy A; if merge is right, merge deliberately.