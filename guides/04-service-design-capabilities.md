# \[WIP\] 04 — Service Design

> Public service traits, `Live` implementations, self-contained services, and input/output DTOs.

---

## 1. Anatomy of a Service

Every service in a bounded context has three parts:

1. **Public trait** — in the root package. The stable contract.
2. **`Live` implementation** — in the `impl` package. The changeable internals.
3. **ZIO accessor companion** — in the root package alongside the trait. Convenience methods for the ZIO environment.

```scala
// Public package — stable contract
package com.myco.ordering

import zio.*

trait OrderService:
  def checkout(input: CheckoutInput): IO[OrderError, OrderView]
  def getOrder(id: OrderId): IO[OrderError, OrderView]
  def cancelOrder(id: OrderId): IO[OrderError, Unit]

object OrderService:
  def checkout(input: CheckoutInput)(using s: OrderService): IO[OrderError, OrderView] =
    s.checkout(input)
  // … other accessors
```

```scala
// Impl package — changeable internals
package com.myco.ordering
package impl

private[ordering] final case class OrderServiceLive(using
  orderRepo: OrderRepository,
  idGen:     IdGenerator
) extends OrderService:
  // implementation
```

---

## 2. Public DTOs — Input and Output Types

The service trait's method signatures use **public DTOs**, not internal domain types. This is deliberate:

- Callers (other contexts, HTTP routes) should not see the rich domain model.
- DTOs are simple, flat, serialisation-friendly. Domain types are complex, nested, constraint-rich.
- The public surface can remain stable while the domain model evolves.

### Input types

Shaped for what the caller provides. Simple case classes, often with `String` or `Int` fields that get parsed into domain types inside the `Live`:

```scala
final case class CheckoutInput(
  customerId:      CustomerId,
  shippingAddress: AddressInput,
  items:           List[LineItemInput]
)

final case class AddressInput(
  country:    String,
  city:       String,
  postalCode: String,
  line1:      String,
  line2:      String
)
```

### Output types (views)

Shaped for what the caller needs to display. A read-model, not the domain entity:

```scala
final case class OrderView(
  id:         OrderId,
  customerId: CustomerId,
  status:     String,        // not OrderStatus — that's internal
  items:      List[LineItemView],
  total:      BigDecimal,
  currency:   String
)
```

### Shared ID types

ID types like `OrderId` and `CustomerId` live in the public package as opaque types. They appear in both input and output DTOs and serve as the stable reference handle across context boundaries.

---

## 3. The `Live` Implementation

The `Live` class is the only place where public DTOs, internal domain types, and repository ports all meet. Its job is translation and orchestration:

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  idGen:     IdGenerator
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      // Step 1: Parse public DTO → domain types (can fail with domain error)
      items   <- ZIO.fromEither(parseItems(input.items))
      address <- ZIO.fromEither(parseAddress(input.shippingAddress))

      // Step 2: Domain logic
      orderId <- idGen.generate.orDie
      order    = Order(orderId, Order.Data(…, items, OrderStatus.Unpaid))

      // Step 3: Persist domain entity directly (repo returns UIO or domain error)
      _ <- orderRepo.save(order)

      // Step 4: Map domain → public view
    yield toView(order)
```

**Data flow:** `DTO in → parse → domain → business logic → persist → view out`

All parse/map methods are `private` to the class. Change the domain model → update these methods → nothing else breaks.

### Providing itself as a ZLayer

```scala
object OrderServiceLive:
  val layer: URLayer[OrderRepository & IdGenerator, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
```

---

## 4. Self-Contained Services

TODO

---

## 5. Multiple Services in One Bounded Context

There's nothing wrong with having several services in the same `*-core` module. When two services share domain vocabulary and evolve together, co-locating them is simpler than a separate context with anti-corruption layers.

```
ordering-core/
└── com/myco/ordering/
    ├── OrderService.scala          ← public
    ├── FulfillmentService.scala    ← public (second service)
    ├── OrderView.scala
    ├── FulfillmentView.scala
    ├── OrderError.scala
    ├── FulfillmentError.scala
    └── impl/
        ├── Order.scala               ← shared domain entity
        ├── Shipment.scala
        ├── OrderRepository.scala
        ├── ShipmentRepository.scala
        ├── OrderServiceLive.scala
        └── FulfillmentServiceLive.scala  ← can use OrderRepository directly
```

`FulfillmentServiceLive` can depend on `OrderRepository` and work with `Order` entities directly — they are in the same `impl` package. No translation, no anti-corruption layer.

**Heuristic:** if two services share more than half their domain vocabulary and their data models change in lockstep, merge them. If they have distinct vocabularies that overlap only at the edges, keep them separate.

---

## 6. Service Method Conventions

### Signatures

```scala
// Domain operations that can fail with expected errors
def checkout(input: CheckoutInput): IO[OrderError, OrderView]
def getOrder(id: OrderId): IO[OrderError, OrderView]

// Operations that should not fail with domain errors (but may have infra defects)
def listRecentOrders(customerId: CustomerId): UIO[List[OrderView]]
```

### Naming

Use **verb-first**, domain language: `checkout`, `getOrder`, `cancelOrder`, `shipOrder`, `calculatePrice`.

Avoid generic names: `process`, `handle`, `execute`, `doAction`. These tell the reader nothing about intent.

### Error type

Every service method that can fail with a domain error returns `IO[<Context>Error, A]`. See [03 — Error Model](03-error-model.md).

Infrastructure failures (database down, network timeout) are **not** domain errors. They go through ZIO's defect channel via `.orDie`.

---

## 7. Stateless Services

Services are **stateless**. All state lives in repositories (or external systems). The `Live` implementation has no mutable fields:

```scala
// ✗ Stateful service — hard to test, hard to reason about
private[ordering] class OrderServiceLive extends OrderService:
  private var orderCount = 0
  override def checkout(…) =
    orderCount += 1
    ???

// ✓ Stateless — all state is in the repository
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository
) extends OrderService:
  override def checkout(…) = ???
```

Configuration is a dependency injected via `ZLayer`, not a field to mutate. See [08 — Configuration](08-configuration.md).

---

## 8. Summary

1. **Trait in public, `Live` in impl.** Stable contract vs changeable internals.
2. **Public DTOs, not domain types.** Service signatures use simple, serialisation-friendly types.
3. **`Live` translates and orchestrates.** Parse DTOs → domain → business logic → persist → view.
4. **Self-contained.** No global state, no hidden dependencies. Everything via `ZLayer`.
5. **Multiple services per context** when they share vocabulary. No need for anti-corruption overhead.
6. **Verb-first naming.** Domain language, not generic verbs.
7. **Stateless.** State lives in repositories.

---

*See also: [03 — Error Model](03-error-model.md), [05 — Persistence](05-persistence.md), [07 — Dependency Injection](07-dependency-injection.md)*
