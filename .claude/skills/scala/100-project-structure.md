# Project Structure

How a monorepo of bounded contexts is laid out: build modules, packages,
dependency direction. Read this before adding a module, a bounded context, or
an sbt `dependsOn` edge.

## Dependencies

None.

---

## The two-layer split

Every bounded context is exactly two sbt modules:

- **`<context>-core` (1st layer)** — the stable **public contract** (service
  trait, DTOs, error enum, events, ID types) in `com.myco.<context>`, plus the
  changeable **domain internals** (rich domain model, repository ports,
  anti-corruption ports, `Live` service, in-memory doubles) in
  `com.myco.<context>.impl`.
- **`<context>-infra` (2nd layer)** — infrastructure adapters: persistence,
  HTTP routes, message consumers, adapters to other contexts. Sub-packages of
  `com.myco.<context>.impl`.

Public contract and domain internals share one module because they are tightly
coupled inside a context; the `impl` package boundary already hides the
internals. The real boundary is **domain logic (core) vs infrastructure
(infra)**, and that's what the module split captures.

> **Important — the Cardinal Rule:** the public package of `*-core` is a stable
> contract. Everything in `impl` is free to change.

## Canonical layout

```
monorepo/
├── build.sbt
└── modules/
    ├── commons/                          ← shared kernel
    ├── ordering-core/
    │   └── src/main/scala/com/myco/ordering/
    │       ├── OrderService.scala          ← public
    │       ├── CheckoutInput.scala         ← public
    │       ├── OrderView.scala             ← public
    │       ├── OrderError.scala            ← public
    │       ├── OrderEvent.scala            ← public
    │       └── impl/
    │           ├── Order.scala               ← private[ordering]
    │           ├── OrderRepository.scala
    │           ├── OrderServiceLive.scala
    │           └── InMemoryOrderRepository.scala
    ├── ordering-infra/
    │   └── src/main/scala/com/myco/ordering/impl/
    │       ├── postgres/   PostgresOrderRepository.scala, OrderDAO.scala
    │       ├── http/       OrderRoutes.scala, JsonCodecs.scala
    │       └── adapters/   PaymentAdapter.scala
    └── app/
        └── src/main/scala/com/myco/app/Main.scala
```

| Artefact | Package | Module | Visibility |
|----------|---------|--------|------------|
| Service trait, DTOs, error enum, events, IDs | `com.myco.ordering` | core | public |
| Domain entities, ADTs, ports, `Live`, in-memory repo | `…ordering.impl` | core | `private[ordering]` |
| DAO, SQL, migrations | `…impl.postgres` | infra | `private[postgres]` |
| Routes, codecs | `…impl.http` | infra | package-private |
| Anti-corruption adapters | `…impl.adapters` | infra | package-private |

## Chained package clauses

Every file under `impl` uses chained package clauses. Each clause brings the
members of that package into scope, so infra code sees the public contract and
the domain internals with zero imports:

```scala
package com.myco.ordering
package impl
package postgres

// in scope: OrderService, OrderId, OrderError (public)
//           Order, OrderRepository, OrderStatus (impl)
//           OrderDAO (postgres)
```

> **Required:** the single-line form `package com.myco.ordering.impl.postgres`
> does **not** bring parent packages into scope. Always use the chained form in
> `impl` and its sub-packages, including test sources.

## Build definition and dependency rules

```scala
lazy val commons = project.in(file("modules/commons"))

lazy val orderingCore = project.in(file("modules/ordering-core"))
  .dependsOn(commons)                       // ONLY commons

lazy val orderingInfra = project.in(file("modules/ordering-infra"))
  .dependsOn(orderingCore)

lazy val shippingCore = project.in(file("modules/shipping-core"))
  .dependsOn(commons)                       // ONLY commons

lazy val shippingInfra = project.in(file("modules/shipping-infra"))
  .dependsOn(shippingCore, orderingCore)    // own core + foreign cores for adapters

lazy val app = project.in(file("modules/app"))
  .dependsOn(orderingInfra, shippingInfra)
```

| Module | May depend on | Must NOT depend on |
|--------|---------------|--------------------|
| `*-core` | `commons` | other `*-core`, any `*-infra` |
| `*-infra` | own `*-core`, other `*-core` (for adapters) | other `*-infra` |
| `app` | all `*-infra` | — |

Direction: `app` → `*-infra` → `*-core` → `commons`.

> **Important — the Independence Rule:** core modules never depend on other core
> modules. Cross-context calls go through a port in the consumer's core and an
> adapter in the consumer's infra (see [Bounded Contexts](320-bounded-contexts.md)).

## Package visibility as architecture

Visibility nests: `private[postgres]` ⊂ `private[ordering]` ⊂ public.

```scala
package com.myco.ordering
trait OrderService                                   // public — stable

package com.myco.ordering
package impl
private[ordering] final case class Order(...)        // whole context, both modules

package com.myco.ordering
package impl
package postgres
private[postgres] final case class OrderDAO(...)     // this adapter only
```

`private[ordering]` works across `ordering-core` and `ordering-infra` because
both modules contribute to the same package tree.

## The `commons` module

- **Belongs:** money, geography, generic ID utilities, shared technical
  primitives (e.g. a paged-response type).
- **Doesn't belong:** types that only *look* universal but mean different
  things per context (`User`, `Product`), types shared by just two contexts,
  any business rule.

If you have to ask, it doesn't belong. Keep `commons` small and stable.

## Checklist — adding a bounded context

1. Agree on the ubiquitous language with domain experts.
2. Create `<context>-core`: public trait, DTOs, error enum; `impl` with domain,
   ports, `Live`, in-memory repo, all `private[<context>]`. Depends only on
   `commons`.
3. Create `<context>-infra`: `impl.<tech>` sub-packages. Depends on own core
   (plus foreign cores only for adapters).
4. Wire layers in `app` (see [Dependency Injection](330-dependency-injection.md)).
5. Core tests with in-memory layers; infra tests with containers (see
   [Testing](400-testing.md)).
6. Ask whether it should be merged into an existing context instead (see
   [Bounded Contexts](320-bounded-contexts.md#merging-heuristic)).
