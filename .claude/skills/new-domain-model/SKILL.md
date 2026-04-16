---
name: new-domain-model
description: Scaffold a domain entity (opaque id + data + entity) or value object in a *-core/impl package, following guide 02. Use when the user asks to add a new domain model, entity, value object, ADT, or when modelling a concept like Customer, Order, Address. Never uses primitive types where an opaque type would fit.
argument-hint: [entity-name] [field:type ...]
allowed-tools: Read Grep Glob Edit Write Bash(sbt *) Bash(mill *) Bash(test *)
---

Create a domain model that follows guide 02 — domain-first, no primitives, smart constructors, illegal states unrepresentable.

## Pre-flight

1. Read `guides/02-domain-modeling.md` in full. This skill's output must match the patterns in that file, not reproduce them from memory.
2. Identify which bounded context the entity belongs to:
   - `$ARGUMENTS[0]` contains a slash or is given as `ctx/<name>` → that context.
   - Otherwise infer from the user's message (e.g. "add Address to ordering"). Ask if unclear.
3. Locate the target directory: `<root>/<ctx>-core/src/main/scala/<base-pkg>/<ctx>/impl/`.
4. Grep a nearby existing model file (e.g. `Order.scala`) to match the concrete style used in this repo — import aliases, use of `iron` / opaque types, etc.

## Inputs

- `$ARGUMENTS[0]` — required. Entity name in PascalCase (`Customer`, `ShippingAddress`, `OrderId`).
- `$ARGUMENTS[1..]` — optional. Field specs as `name:Type` or `name:Type?` for optional. Examples: `email:Email`, `age:Int?`, `createdAt:Instant`.
- If no fields are given, create an entity skeleton with `id` and a placeholder `data` case class, and tell the user to fill it in.

## Checklist

1. Read the relevant guide sections:
   - §2 Opaque types for identifiers
   - §3 Smart constructors
   - §4 Entity vs value
   - §5 ADTs & sealed traits
   - Any "Don't" / anti-pattern blocks
2. Decide what you are scaffolding:
   - **Entity:** has identity that outlives its data (e.g. `Order`, `Customer`). Produce three things: `<Name>Id` opaque, `<Name>Data` case class, `<Name>` case class wrapping both.
   - **Value object:** has no identity, equality by value (e.g. `Money`, `Address`). Produce a single case class with a smart constructor.
   - **Enum / ADT:** sealed choice (e.g. `OrderStatus`). Produce a Scala 3 `enum` with the cases supplied.
3. For every field typed as a primitive (`String`, `Int`, `Long`, `Double`, `Boolean`), pause and decide whether an opaque type would be clearer:
   - `String` holding an email → create `Email` opaque with smart constructor.
   - `Int` holding a count → create `Quantity` or similar.
   - Genuine booleans / true-primitives are fine to leave.
4. Generate files in `<ctx>-core/src/main/scala/<base-pkg>/<ctx>/impl/`:
   - One `.scala` file per entity/value/enum. Do not bundle unrelated models.
   - Scaladoc on every top-level declaration. Entity scaladoc says what it represents in the domain.
   - Smart constructors return `Either[<CtxError>, T]` — consult guide 03 for the error enum shape, and propose adding a case to it if a suitable error does not yet exist.
5. If the repo uses `iron` for refined types, prefer `iron` refinements over hand-rolled smart constructors where they fit.
6. Do **not** create repository / service wiring here — that is `/new-repository` and `/new-service`.
7. Run compile. Fix obvious issues (missing imports) but stop at the first real error — do not guess.

## Invariants to enforce

- No public primitive fields on domain types.
- Smart constructors are the only way to create refined values; the raw constructors must be `private`.
- Entities separate id from data: `case class Order(id: OrderId, data: OrderData)`.
- Types in `impl/` are `private[<ctx>]`.
- No `throw` in smart constructors — errors go through `Either` or the ZIO error channel (guide 03).

## Output format

```
Created domain model '<Name>' in <ctx>

Files:
  <ctx>-core/src/main/scala/<pkg>/<ctx>/impl/<Name>.scala
  (optional) <ctx>-core/src/main/scala/<pkg>/<ctx>/impl/<Related>.scala

Primitives lifted into opaque types:
  - email: String → Email
  - quantity: Int → Quantity

Compile: OK

Suggested follow-ups:
  - Add an error case to <Ctx>Error for invalid <Name> construction (/new-error)
  - If persisted: /new-repository <Name>
```

## Common mistakes

- Using `String` for identifiers or for any domain concept with validation rules.
- Public default case-class constructors bypassing smart-constructor validation.
- Putting new domain types in the public package instead of `impl`.
- Adding a generic `DomainError` enum in this file — errors live in `<Ctx>Error` at the public package.
- Over-scaffolding: do not create `<Name>Repository` / `<Name>Service` here.