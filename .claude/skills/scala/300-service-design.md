# Service Design

The public face of a bounded context: a service trait with DTOs in the public
package, and a `Live` implementation in `impl` that parses, orchestrates,
persists and maps back. Read this before adding or changing a service method.

## Dependencies

- `"dev.zio" %% "zio"`

---

## Anatomy of a service

1. **Public trait** in `com.myco.<context>` — the stable contract.
2. **Accessor companion** next to the trait — `ZIO.serviceWithZIO` shortcuts.
3. **`Live` implementation** in `com.myco.<context>.impl` — the changeable
   internals, with its own `layer`.

```scala
package com.myco.ordering

import zio.*

trait OrderService:
  def checkout(input: CheckoutInput): IO[OrderError, OrderView]
  def getOrder(id: OrderId): IO[OrderError, OrderView]
  def cancelOrder(id: OrderId): IO[OrderError, Unit]

object OrderService:
  def checkout(input: CheckoutInput): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.checkout(input))
  def getOrder(id: OrderId): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.getOrder(id))
  def cancelOrder(id: OrderId): ZIO[OrderService, OrderError, Unit] =
    ZIO.serviceWithZIO(_.cancelOrder(id))
```

The trait's signatures use only public-package types: DTOs, ID types, the
error enum. No `R` in the trait — dependencies belong to the `Live`
constructor.

## Public DTOs

Callers — other contexts, HTTP routes — never see the domain model. The
signature uses DTOs: flat, serialisation-friendly, stable.

**Inputs** are shaped for what the caller provides; fields are often primitives
that the `Live` parses into domain types:

```scala
final case class CheckoutInput(
  customerId:      CustomerId,
  shippingAddress: AddressInput,
  items:           List[LineItemInput]
)

final case class LineItemInput(catalogueNumber: String, quantity: Int)
```

**Outputs (views)** are read models shaped for what the caller displays — not
the entity:

```scala
final case class OrderView(
  id:         OrderId,
  customerId: CustomerId,
  status:     String,          // not OrderStatus — that type is internal
  items:      List[LineItemView],
  total:      BigDecimal,
  currency:   String
)
```

**IDs** (`OrderId`, `CustomerId`) are opaque types in the public package. They
appear in both inputs and outputs and are the stable handle across contexts.

> **Note:** DTOs are the one place where primitives are allowed — they are the
> parse boundary. Once inside the `Live`, everything is a domain type (see
> [Domain Modeling](120-domain-modeling.md)).

## The `Live` implementation

The `Live` is the only place where DTOs, domain types and ports meet. Its job
is translation and orchestration:

**DTO in → parse → domain logic → persist → view out**

```scala
package com.myco.ordering
package impl

import zio.*

private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  config:    OrderingConfig
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items   <- ZIO.fromEither(parseItems(input.items))
      address <- ZIO.fromEither(parseAddress(input.shippingAddress))
      _       <- ZIO.cond(items.size <= config.maxItemsPerOrder, (), OrderError.TooManyItems(items.size))
      id      <- Random.nextUUID.map(uuid => Order.Id(uuid.toString))
      order    = Order(id, Order.Data(input.customerId, address, items, OrderStatus.Unpaid))
      _       <- orderRepo.save(order)
    yield toView(order)

  override def getOrder(id: OrderId): IO[OrderError, OrderView] =
    findOrder(id).map(toView)

  override def cancelOrder(id: OrderId): IO[OrderError, Unit] =
    for
      order <- findOrder(id)
      _     <- order.data.status match
                 case OrderStatus.Cancelled => ZIO.fail(OrderError.OrderAlreadyCancelled(id))
                 case _                     => orderRepo.save(order.cancel)
    yield ()

  private def findOrder(id: OrderId): IO[OrderError, Order] =
    orderRepo.findById(Order.Id(id.value)).someOrFail(OrderError.OrderNotFound(id))

  private def parseItems(inputs: List[LineItemInput]): Either[OrderError, NonEmptyChunk[LineItem]] = ???
  private def parseAddress(input: AddressInput): Either[OrderError, Address] = ???
  private def toView(order: Order): OrderView = ???

object OrderServiceLive:
  val layer: URLayer[OrderRepository & OrderingConfig, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
```

- Parse and map helpers are `private`. Changing the domain model means
  changing these helpers and nothing else.
- Domain rules (`order.cancel`, price calculation) are pure methods on the
  domain model, not on the `Live`.
- The layer's output type is the **trait**, so callers can't depend on the
  implementation.

## Service method conventions

**Signatures**

```scala
def checkout(input: CheckoutInput): IO[OrderError, OrderView]      // can fail with a domain error
def listRecentOrders(customerId: CustomerId): UIO[List[OrderView]] // no domain failure
```

Never `Task` — see [Error Model](210-error-model.md).

**Naming** — verb-first, domain language: `checkout`, `cancelOrder`,
`shipOrder`, `calculatePrice`. Never `process`, `handle`, `execute`,
`doAction`, `manage`.

**Authorization** — the service receives the caller's identity as a typed
parameter (`userId: UserId`) and enforces ownership rules itself; routes only
authenticate (see [API Design](350-api-design.md#authentication-and-authorization)).

## Self-contained and stateless

- Every dependency is a constructor parameter, injected by `ZLayer`. No global
  objects, no singletons, no service locators.
- No mutable fields. State lives in repositories (or a `Ref` inside an
  in-memory adapter), never in the `Live`:

```scala
// Wrong — state in the service:
private[ordering] final class OrderServiceLive extends OrderService:
  private var orderCount = 0

// Right — state behind a port:
private[ordering] final case class OrderServiceLive(orderRepo: OrderRepository) extends OrderService
```

- Configuration is a dependency (`OrderingConfig`), not a mutable field (see
  [Configuration](340-configuration.md)).

## Multiple services in one context

Several public services may live in the same core when they share the domain
vocabulary and evolve together:

```
ordering-core/com/myco/ordering/
├── OrderService.scala
├── FulfillmentService.scala
├── OrderError.scala
├── FulfillmentError.scala
└── impl/
    ├── Order.scala
    ├── Shipment.scala
    ├── OrderRepository.scala
    ├── OrderServiceLive.scala
    └── FulfillmentServiceLive.scala   ← uses OrderRepository directly
```

`FulfillmentServiceLive` uses `OrderRepository` and `Order` directly — same
`impl` package, no anti-corruption layer. When to merge vs separate: see
[Bounded Contexts](320-bounded-contexts.md#merging-heuristic).
