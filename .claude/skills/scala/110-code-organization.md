# Code Organization and Visibility

Where a declaration lives inside a module: which package, which file, which
visibility. Read this when creating or moving a `.scala` file, adding a package,
or widening a modifier. Module-level layout is in [Project
Structure](100-project-structure.md).

## Dependencies

None.

---

## Before creating a file

Answer three questions first:

1. Which existing package does this belong to — the public contract, `impl`,
   or an `impl.<tech>` adapter package?
2. What visibility does each top-level declaration deserve?
3. Does the filename match the primary type or concept?

## Top-level visibility

Every top-level `class`, `trait`, `enum`, `object`, `opaque type` and `def`
gets an explicit, deliberate visibility:

| Modifier | Meaning | Typical use |
|----------|---------|-------------|
| none (public) | part of the stable contract | service trait, DTOs, error enum, ID types in `com.myco.<context>` |
| `private[<context>]` | whole bounded context, across core and infra | domain model, ports, `Live`, in-memory doubles |
| `private[<tech>]` | one adapter package | DAOs, SQL fragments, wire formats |
| `private` | this file / enclosing scope | helpers used once |

Choose by scanning the actual call sites. Start narrow; widen only when a
caller needs access.

```scala
package com.myco.ordering
package impl

private[ordering] trait OrderRepository:
  def findById(id: Order.Id): UIO[Option[Order]]

private[ordering] final case class OrderServiceLive(orderRepo: OrderRepository)
    extends OrderService:
  override def getOrder(id: OrderId): IO[OrderError, OrderView] =
    orderRepo.findById(Order.Id(id.value)).someOrFail(OrderError.OrderNotFound(id)).map(toView)

  private def toView(order: Order): OrderView = ???
```

> **Important:** nothing in `impl` is public. A public type in `impl` silently
> becomes part of the contract other contexts can couple to.

## Files

Whether a type gets its own file depends on **where it is constructed**, not
where it is referenced. A type constructed in one place may share that file; a
type constructed in several places (several services, a codec, a DAO) gets its
own file named after itself.

- Bundle a sealed hierarchy / enum in one file.
- A trait may share a file with its accessor companion; a port may share a file
  with the snapshot type only it returns.
- An error enum shares its file with its nested `Code` enum.
- Otherwise, one top-level type per file, file named after the type.

Never create `Models.scala`, `Types.scala`, `Helpers.scala`, `Utils.scala`. The
"Types"/"Utils" name is itself the smell — the contents share nothing but being
types. Split them by the rules above.

## Packages

- Name packages after concepts (`ordering`, `billing`, `postgres`, `http`,
  `adapters`), never after mechanisms (`util`, `common`, `helpers`, `misc`).
- Avoid single-file packages; fold them into the parent or a sibling.
- A sub-package MUST NOT shadow a top-level `def` or `val` of the same name in
  the enclosing package; Scala 3 rejects it. Sink the package deeper instead.
- Test sources mirror the main package tree and use the same chained clauses,
  so they can reach `private[<context>]` members.

## Enforcing boundaries

Enforce boundaries by construction, cheapest first:

1. sbt `dependsOn` edges define what a module can see at all.
2. `private[<context>]` / `private[<tech>]` hide internals within the package tree.
3. A Scalafix rule (e.g. forbidden imports) only when the first two can't
   express the constraint.
