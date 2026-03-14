# 01 — Project Structure

> Module layout, build definition, package conventions, and dependency rules for a Scala 3 + ZIO monorepo.

---

## 1. The Two-Layer Split

Every bounded context in the monorepo is composed of two build modules:

**1st layer — `*-core`:** Contains both the **stable public contract** (service trait, DTOs, error enum) and the **changeable domain internals** (rich domain model, repository ports, `Live` service implementation, in-memory test doubles). These two concerns are separated by package visibility, not by build modules.

**2nd layer — `*-infra`:** Contains **infrastructure adapters** — persistence implementations (Postgres, MySQL, …), HTTP routes, message consumers, and anti-corruption adapters that bridge to other bounded contexts.

Why not three modules (separate `*-api`, `*-domain`, `*-infra`)? Because the API types and the domain model are tightly coupled inside a single bounded context. Splitting them into separate build modules adds indirection without meaningful benefit — the `impl` package boundary already enforces that external consumers see only the public contract. The real architectural boundary is between **domain logic** (core) and **infrastructure** (infra), and that's what the two-module split captures.

---

## 2. Canonical Layout

```
monorepo/
├── build.sbt
├── modules/
│   ├── commons/                             ← shared kernel
│   │   └── src/main/scala/com/myco/commons/
│   │
│   ├── ordering-core/                       ← 1ST LAYER
│   │   └── src/main/scala/com/myco/ordering/
│   │       ├── OrderService.scala             ← public
│   │       ├── CheckoutInput.scala            ← public
│   │       ├── OrderView.scala                ← public
│   │       ├── OrderError.scala               ← public
│   │       ├── OrderEvent.scala               ← public
│   │       └── impl/
│   │           ├── Order.scala                  ← private[ordering]
│   │           ├── LineItem.scala
│   │           ├── OrderRepository.scala
│   │           ├── OrderServiceLive.scala
│   │           └── InMemoryOrderRepository.scala
│   │
│   ├── ordering-infra/                      ← 2ND LAYER
│   │   └── src/main/scala/com/myco/ordering/
│   │       └── impl/
│   │           ├── postgres/
│   │           │   ├── PostgresOrderRepository.scala
│   │           │   └── OrderDAO.scala
│   │           ├── http/
│   │           │   └── OrderRoutes.scala
│   │           └── adapters/
│   │               └── PaymentAdapter.scala
│   │
│   └── app/
│       └── src/main/scala/com/myco/app/Main.scala
```

### Public package (`com.myco.ordering`)

Service trait, input/output DTOs, error enum with `Code`, published events, shared ID types (opaque).

### Impl package (`com.myco.ordering.impl`)

Rich domain model, repository ports, anti-corruption ports, `Live` service, in-memory test doubles. All types scoped `private[ordering]`.

### Infra sub-packages

`impl.postgres/` — DAO, SQL, migrations. `impl.http/` — routes, JSON codecs. `impl.adapters/` — anti-corruption adapters bridging to other contexts.

---

## 3. Chained Package Clauses

Scala allows splitting a package path into chained declarations. All members of every enclosing package are automatically in scope:

```scala
package com.myco.ordering
package impl
package postgres

// In scope automatically — zero imports needed:
//   com.myco.ordering       → OrderService, OrderId, OrderError, …
//   com.myco.ordering.impl  → Order, OrderRepository, OrderStatus, …
//   com.myco.ordering.impl.postgres → OrderDAO, local types
```

The single-line form (`package com.myco.ordering.impl.postgres`) does **not** auto-import parent packages. Always use the chained form in `impl` sub-packages.

---

## 4. Build Definition (sbt)

```scala
lazy val commons = project.in(file("modules/commons"))

lazy val orderingCore = project.in(file("modules/ordering-core"))
  .dependsOn(commons)                   // ONLY commons

lazy val orderingInfra = project.in(file("modules/ordering-infra"))
  .dependsOn(orderingCore)

lazy val shippingCore = project.in(file("modules/shipping-core"))
  .dependsOn(commons)                   // ONLY commons

lazy val shippingInfra = project.in(file("modules/shipping-infra"))
  .dependsOn(shippingCore, orderingCore) // own core + other cores for adapters

lazy val app = project.in(file("modules/app"))
  .dependsOn(orderingInfra, shippingInfra)
```

### Dependency rules

| Module | May depend on | Must NOT depend on |
|--------|--------------|-------------------|
| `*-core` | `commons` only | Other `*-core`, any `*-infra` |
| `*-infra` | Own `*-core`, other `*-core` (for adapters) | Other `*-infra` |
| `app` | All `*-infra` (transitively brings `*-core`) | — |

---

## 5. Package Visibility as Architecture

```scala
// Public — stable contract
package com.myco.ordering
trait OrderService: …

// Private to context — changeable internals
package com.myco.ordering
package impl
private[ordering] final case class Order(…)
private[ordering] trait OrderRepository: …

// Private to adapter — only the Postgres adapter sees its DAO
package com.myco.ordering
package impl
package postgres
private[postgres] final case class OrderDAO(…)
```

Three layers: `private[postgres]` ⊂ `private[ordering]` ⊂ public.

---

## 6. The `commons` Module

**Belongs in commons:** Money types, geographic types, common ID utilities, shared error patterns, technical utilities.

**Does NOT belong in commons:** Types that *feel* universal but differ across contexts (e.g. `User`), types shared by only two contexts, business rules of any kind.

**Rule of thumb:** if you have to ask, it probably doesn't belong. Keep commons small and stable.

---

## 7. Summary Table

| Artefact | Package | Build module | Visibility | Stable? |
|----------|---------|--------------|------------|---------|
| Service trait | `com.myco.ordering` | `*-core` | Public | Yes |
| DTOs, error enum, events | `com.myco.ordering` | `*-core` | Public | Yes |
| Domain entities, ADTs | `…ordering.impl` | `*-core` | `private[ordering]` | No |
| Repository port | `…ordering.impl` | `*-core` | `private[ordering]` | No |
| `Live` impl, in-memory repo | `…ordering.impl` | `*-core` | `private[ordering]` | No |
| DAO, SQL, migrations | `…impl.postgres` | `*-infra` | `private[postgres]` | No |
| HTTP routes, codecs | `…impl.http` | `*-infra` | Package-private | No |
| Anti-corruption adapters | `…impl.adapters` | `*-infra` | Package-private | No |

---

## 8. Checklist — Adding a New Module

1. **Define the ubiquitous language.** Agree on terminology with domain experts.
2. **Create `<context>-core`.** Public package: service trait, DTOs, errors. `impl` package: domain, ports, `Live`, in-memory repo. All `impl` types `private[<context>]`. Depends only on `commons`.
3. **Create `<context>-infra`.** Sub-packages for persistence, HTTP, adapters. Depends on own `*-core` + other `*-core` for adapters.
4. **Wire in `app`.** Add `.dependsOn(<context>Infra)`. Provide layers.
5. **Write tests.** Core tests with in-memory layers. Infra tests with containers.
6. **Consider merging.** If the new service shares vocabulary with an existing context, add it there instead. See [06 — Bounded Contexts](06-bounded-contexts.md) §4.

---

*See also: [02 — Domain Modeling](02-domain-modeling.md), [06 — Bounded Contexts](06-bounded-contexts.md), [07 — Dependency Injection](07-dependency-injection.md)*
