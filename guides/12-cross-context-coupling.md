# 12 — Cross-Context Coupling Strategies

> A practical guide to choosing the right level of isolation between bounded contexts — from shared domain to full anti-corruption layer.

---

## 1. The Spectrum

There is no single "correct" way to connect two bounded contexts. The right choice depends on how tightly the services are coupled in reality — in their vocabulary, their change cadence, and their team ownership.

This guide presents **three strategies** along a coupling spectrum:

```
MOST COUPLED ◄──────────────────────────────────────────► MOST INDEPENDENT

  Strategy A              Strategy B                    Strategy C
  Same bounded            Direct dependency,            Anti-corruption
  context,                Live in infra                 layer (port +
  multiple services                                     adapter + snapshot)
```

Each strategy is valid. Each has trade-offs. Pick the one that matches your actual situation, not the one that looks most architecturally impressive.

---

## 2. Strategy A — Multiple Services in One Bounded Context

### When to use

- The services share **most of their domain vocabulary** (>50% of types).
- They evolve **in lockstep** — a change to one almost always requires a change to the other.
- The **same team** owns both services.
- They will likely **always be deployed together**.

### Structure

Both services live in the same `*-core` module, sharing the `impl` package:

```
ordering-core/
└── com/myco/ordering/
    ├── OrderService.scala              ← public
    ├── FulfillmentService.scala        ← public (second service)
    ├── OrderView.scala                 ← shared
    ├── FulfillmentView.scala
    ├── OrderError.scala
    ├── FulfillmentError.scala
    └── impl/
        ├── Order.scala                   ← shared domain entity
        ├── Shipment.scala
        ├── OrderRepository.scala
        ├── ShipmentRepository.scala
        ├── OrderServiceLive.scala
        └── FulfillmentServiceLive.scala  ← uses OrderRepository directly
```

### How it works

`FulfillmentServiceLive` depends on `OrderRepository` directly — same `impl` package, same domain model. No translation, no adapter, no boundary:

```scala
package com.myco.ordering
package impl

import zio.*

private[ordering] final case class FulfillmentServiceLive(
  orderRepo:    OrderRepository,
  shipmentRepo: ShipmentRepository
) extends FulfillmentService:

  override def fulfillOrder(orderId: OrderId): IO[FulfillmentError, FulfillmentView] =
    for
      order    <- orderRepo.findById(Order.Id(orderId.value))
                    .someOrFail(FulfillmentError.OrderNotFound(orderId))
      shipment <- ZIO.fromEither(createShipment(order))   // works with Order directly
      _        <- shipmentRepo.save(shipment)
    yield toView(shipment)

object FulfillmentServiceLive:
  val layer: URLayer[OrderRepository & ShipmentRepository, FulfillmentService] =
    ZLayer.fromFunction(FulfillmentServiceLive.apply)
```

### Trade-offs

| Advantage | Disadvantage |
|-----------|-------------|
| Simplest possible code — no translation, no adapter | Cannot extract one service independently |
| Shared domain model means no duplication | Changes to shared types affect both services |
| Easy to test — one set of in-memory layers | Module grows larger over time |
| Domain logic stays in `*-core` | Harder to reason about boundaries as the context grows |

### Extraction path

If you later need to split them, you refactor into Strategy B or C at that point. Since both services are in the same module, the refactoring is local.

---

## 3. Strategy B — Direct Dependency, Live in Infra

### When to use

- The services have **distinct but overlapping** vocabulary.
- They are **loosely coupled** but not independent enough to justify full anti-corruption types.
- You want to keep things **simple** while still having separate core modules.
- You accept that the **consumer recompiles** when the producer's public contract changes.
- You don't need the consumer's domain tests to be **isolated** from the producer's classpath.

### Structure

Each context has its own `*-core`, but the `Live` implementation that bridges them lives in the consumer's `*-infra` (second layer):

```
ordering-core/                            ← independent
└── com/myco/ordering/
    ├── OrderService.scala                  ← public trait
    ├── OrderView.scala
    ├── OrderError.scala
    └── impl/
        ├── Order.scala
        ├── OrderRepository.scala
        ├── OrderServiceLive.scala
        └── InMemoryOrderRepository.scala

shipping-core/                            ← independent (no ordering dep!)
└── com/myco/shipping/
    ├── ShippingService.scala               ← public trait
    ├── ShipmentView.scala
    ├── ShippingError.scala
    └── impl/
        ├── Shipment.scala
        ├── ShipmentRepository.scala
        └── InMemoryShipmentRepository.scala
        # NOTE: NO ShippingServiceLive here — it moves to infra

shipping-infra/                           ← depends on shipping-core AND ordering-core
└── com/myco/shipping/
    └── impl/
        ├── ShippingServiceLive.scala       ← Live impl lives HERE
        ├── postgres/
        │   └── PostgresShipmentRepository.scala
        └── http/
            └── ShippingRoutes.scala
```

### Build definition

```scala
lazy val orderingCore = project.in(file("modules/ordering-core"))
  .dependsOn(commons)

lazy val shippingCore = project.in(file("modules/shipping-core"))
  .dependsOn(commons)                   // does NOT depend on ordering-core

lazy val shippingInfra = project.in(file("modules/shipping-infra"))
  .dependsOn(shippingCore, orderingCore) // depends on BOTH cores
```

`shipping-core` remains independent. The cross-context dependency exists only in the second layer.

### How it works

`ShippingServiceLive` lives in `shipping-infra` and uses ordering's public `OrderService` trait directly:

```scala
package com.myco.shipping
package impl

// Chained clause auto-imports: ShippingService, ShipmentView, ShippingError,
// Shipment, ShipmentRepository from shipping's packages.

import com.myco.ordering.{OrderService, OrderView, OrderId, OrderError as ExtOrderError}
import zio.*

final case class ShippingServiceLive(
  orderService: OrderService,       // ordering's public trait — used directly
  shipmentRepo: ShipmentRepository
) extends ShippingService:

  override def shipOrder(input: ShipOrderInput): IO[ShippingError, ShipmentView] =
    for
      orderView <- orderService.getOrder(OrderId(input.orderId))
                     .mapError(mapOrderError)
      shipment  <- ZIO.fromEither(createShipment(orderView, input))
      _         <- shipmentRepo.save(shipment)
    yield toView(shipment)

  // Translate ordering's errors to shipping's errors at this boundary
  private def mapOrderError(err: ExtOrderError): ShippingError =
    err match
      case ExtOrderError.OrderNotFound(_) => ShippingError.OrderNotFound(err.details)
      case other                          => ShippingError.UpstreamFailure(other.message)

  // Uses OrderView directly — no separate snapshot type
  private def createShipment(
    orderView: OrderView,
    input:     ShipOrderInput
  ): Either[ShippingError, Shipment] =
    // Build a Shipment from ordering's OrderView fields
    ???

object ShippingServiceLive:
  val layer: URLayer[OrderService & ShipmentRepository, ShippingService] =
    ZLayer.fromFunction(ShippingServiceLive.apply)
```

### Testing

Since `ShippingServiceLive` lives in `*-infra`, you need ordering-core on the test classpath. You can stub `OrderService` directly:

```scala
object StubOrderService:
  def layer(orders: Map[OrderId, OrderView]): ULayer[OrderService] =
    ZLayer.succeed:
      new OrderService:
        def checkout(input: CheckoutInput) = ???
        def getOrder(id: OrderId) =
          ZIO.fromOption(orders.get(id))
            .mapError(_ => OrderError.OrderNotFound(id))
        def cancelOrder(id: OrderId) = ???

suite("ShippingServiceLive")(
  test("ships an existing order"):
    for
      result <- ShippingService.shipOrder(ShipOrderInput("ord-1"))
    yield assertTrue(result.status == "Shipped")
).provide(
  ShippingServiceLive.layer,
  InMemoryShipmentRepository.layer,
  StubOrderService.layer(Map(OrderId("ord-1") -> testOrderView))
)
```

Note that you must stub the entire `OrderService` trait — including methods you don't use — because you depend on the full trait, not a narrow port.

### Can this be extracted to a microservice?

**Yes.** `OrderService` is a trait. You swap the layer:

```scala
// Before extraction: provide OrderServiceLive.layer (in-process)
// After extraction:  provide OrderServiceHttpClient.layer (remote)

final case class OrderServiceHttpClient(
  client:  Client,
  baseUrl: URL
) extends OrderService:

  override def getOrder(id: OrderId): IO[OrderError, OrderView] =
    for
      response <- client.request(Request.get(baseUrl / "orders" / id.value)).orDie
      view     <- response.status match
        case Status.Ok       => response.body.to[OrderView].orDie
        case Status.NotFound => ZIO.fail(OrderError.OrderNotFound(id))
        case other           => ZIO.die(new RuntimeException(s"Ordering returned $other"))
    yield view

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] = ???
  override def cancelOrder(id: OrderId): IO[OrderError, Unit] = ???

object OrderServiceHttpClient:
  val layer: URLayer[Client & OrderingServiceConfig, OrderService] =
    ZLayer.fromFunction(OrderServiceHttpClient.apply)
```

**The important nuance:** the HTTP client must implement the **entire `OrderService` trait**, including methods that shipping doesn't use (`checkout`, `cancelOrder`). These could throw `NotImplementedError` or delegate to the remote service — either way, you're carrying the weight of the full interface.

### Trade-offs

| Advantage | Disadvantage |
|-----------|-------------|
| No snapshot types or port traits to maintain | Shipping's domain logic uses ordering's types (`OrderView`, `OrderError`) |
| Less code than Strategy C | If ordering adds a field to `OrderView` → shipping recompiles |
| Extraction still possible — swap the layer | If ordering adds an error case → shipping must update `mapOrderError` |
| Straightforward to understand | `Live` in infra means domain logic is split across two modules |
| | Stubbing tests must implement the full `OrderService` trait |
| | Remote client must implement methods the consumer doesn't use |

---

## 4. Strategy C — Anti-Corruption Layer (Port + Adapter + Snapshot)

### When to use

- The contexts have **distinct vocabularies** with only edge overlap.
- They have **different teams** or different change cadences.
- You want shipping's domain tests to run **without ordering-core on the classpath**.
- You want the consumer to be **immune** to changes in the producer's public contract.
- You are **planning to extract** one of the contexts into a microservice.

### Structure

The consumer's core defines its own port with its own types. The adapter in the consumer's infra bridges to the producer:

```
shipping-core/                            ← independent, no ordering dep
└── com/myco/shipping/
    └── impl/
        ├── Shipment.scala
        ├── ShipmentRepository.scala
        ├── OrderingPort.scala              ← port in shipping's language
        ├── ShippingServiceLive.scala       ← uses OrderingPort, not OrderService
        └── InMemoryShipmentRepository.scala

shipping-infra/                           ← depends on shipping-core AND ordering-core
└── com/myco/shipping/
    └── impl/
        └── adapters/
            └── OrderingAdapter.scala       ← implements OrderingPort using OrderService
```

### How it works

The port defines exactly what shipping needs, in shipping's own types:

```scala
package com.myco.shipping
package impl

import zio.*

private[shipping] final case class OrderSnapshot(
  orderId:    String,
  customerId: String,
  items:      List[OrderSnapshot.Item],
  total:      BigDecimal
)
private[shipping] object OrderSnapshot:
  final case class Item(catalogueNumber: String, quantity: Int)

private[shipping] trait OrderingPort:
  def getOrder(orderId: String): UIO[Option[OrderSnapshot]]
```

The `Live` service uses the port:

```scala
package com.myco.shipping
package impl

import zio.*

private[shipping] final case class ShippingServiceLive(
  orderingPort: OrderingPort,
  shipmentRepo: ShipmentRepository
) extends ShippingService:

  override def shipOrder(input: ShipOrderInput): IO[ShippingError, ShipmentView] =
    for
      snapshot <- orderingPort.getOrder(input.orderId)
                    .someOrFail(ShippingError.OrderNotFound(input.orderId))
      shipment <- ZIO.fromEither(createShipment(snapshot, input))
      _        <- shipmentRepo.save(shipment)
    yield toView(shipment)

object ShippingServiceLive:
  val layer: URLayer[OrderingPort & ShipmentRepository, ShippingService] =
    ZLayer.fromFunction(ShippingServiceLive.apply)
```

The adapter in infra:

```scala
package com.myco.shipping
package impl
package adapters

import com.myco.ordering.{OrderService, OrderView, OrderId, OrderError as ExtOrderError}
import zio.*

final case class OrderingAdapter(
  orderService: OrderService
) extends OrderingPort:

  override def getOrder(orderId: String): UIO[Option[OrderSnapshot]] =
    orderService.getOrder(OrderId(orderId))
      .map(view => Some(toSnapshot(view)))
      .catchSome:
        case _: ExtOrderError.OrderNotFound => ZIO.succeed(None)
      .orDie  // unexpected foreign errors → defect at the adapter boundary

  private def toSnapshot(view: OrderView): OrderSnapshot =
    OrderSnapshot(
      orderId    = view.id.value,
      customerId = view.customerId.value,
      items      = view.items.map(i => OrderSnapshot.Item(i.catalogueNumber, i.quantity)),
      total      = view.total
    )

object OrderingAdapter:
  val layer: URLayer[OrderService, OrderingPort] =
    ZLayer.fromFunction(OrderingAdapter.apply)
```

### Testing

The port is narrow and uses shipping's own types, so stubs are trivial:

```scala
object StubOrderingPort:
  def layer(orders: Map[String, OrderSnapshot]): ULayer[OrderingPort] =
    ZLayer.succeed:
      new OrderingPort:
        def getOrder(orderId: String): UIO[Option[OrderSnapshot]] =
          ZIO.succeed(orders.get(orderId))

  val empty: ULayer[OrderingPort] = layer(Map.empty)

suite("ShippingServiceLive")(
  test("ships an existing order"):
    for
      result <- ShippingService.shipOrder(ShipOrderInput("ord-1"))
    yield assertTrue(result.status == "Shipped")
).provide(
  ShippingServiceLive.layer,
  InMemoryShipmentRepository.layer,
  StubOrderingPort.layer(Map("ord-1" -> testSnapshot))
  // Note: ordering-core is NOT on this test's classpath
)
```

### Extraction

When ordering is extracted to a microservice, only the adapter changes:

```scala
// Before: OrderingAdapter(orderService: OrderService)   — in-process
// After:  OrderingRemoteAdapter(client: Client)          — HTTP call

final case class OrderingRemoteAdapter(
  client: Client, baseUrl: URL
) extends OrderingPort:

  override def getOrder(orderId: String): UIO[Option[OrderSnapshot]] =
    (for
      response <- client.request(Request.get(baseUrl / "orders" / orderId))
      result   <- response.status match
        case Status.Ok       => response.body.to[OrderView].map(v => Some(toSnapshot(v)))
        case Status.NotFound => ZIO.succeed(None)
        case other           => ZIO.fail(new RuntimeException(s"Ordering returned $other"))
    yield result).orDie  // infra failure → defect at the adapter boundary
```

The remote adapter only implements the **narrow port** (`getOrder` returning `Option[OrderSnapshot]`), not the full `OrderService` trait. This is significantly simpler than Strategy B's remote client.

### Trade-offs

| Advantage | Disadvantage |
|-----------|-------------|
| Shipping is immune to ordering's contract evolution | More code: port trait + snapshot type + adapter class |
| Domain logic stays in `*-core`, testable in isolation | Snapshot type may feel like duplication of OrderView |
| Stub is trivial (one method, shipping's types) | Indirection when tracing cross-context flows |
| Remote adapter only implements the narrow port | Must maintain adapter when ordering's contract changes |
| Clean extraction — swap one adapter | |

---

## 5. Comparison Matrix

| Concern | Strategy A (Same context) | Strategy B (Direct dep) | Strategy C (ACL) |
|---------|--------------------------|------------------------|------------------|
| **Core modules independent?** | N/A (one module) | Yes — dep only in infra | Yes — dep only in infra |
| **Consumer uses producer's types?** | Shares domain types | Uses public DTOs + error enum | Uses own snapshot types |
| **Producer contract change impact** | Direct — shared code | Recompile consumer's infra | Adapter absorbs — core untouched |
| **Where does Live logic live?** | `*-core/impl` | `*-infra` | `*-core/impl` |
| **Test isolation** | Full — one module | Needs producer on classpath | Full — stub the narrow port |
| **Test stub complexity** | N/A (shared repos) | Full trait stub | One-method port stub |
| **Extraction effort** | Must refactor to B or C first | Swap layer (full trait impl) | Swap adapter (narrow port impl) |
| **Remote client scope** | N/A | Must implement full `OrderService` | Only implements `OrderingPort` |
| **Code overhead** | Minimal | Moderate (error mapping) | Highest (port + snapshot + adapter) |
| **Best for** | Tightly coupled, same team | Loosely coupled, pragmatic | Independent, different teams |

---

## 6. Decision Flowchart

```
Do the two services share most of their domain vocabulary?
├── YES → Do they always change together?
│         ├── YES → Strategy A: Same bounded context
│         └── NO  → Consider splitting, start with Strategy B
│
└── NO  → Is the producer's public contract stable?
          ├── YES → Strategy B: Direct dependency, Live in infra
          │         (simpler, extraction still possible)
          │
          └── NO  → Do different teams own the contexts?
                    ├── YES → Strategy C: Anti-corruption layer
                    │         (full isolation, clean extraction)
                    │
                    └── NO  → How soon will you extract?
                              ├── SOON → Strategy C
                              └── NOT SOON → Strategy B
                                             (refactor to C when needed)
```

---

## 7. Migrating Between Strategies

The strategies are not permanent choices. You can migrate as your needs change:

### A → B (splitting a context)

1. Create a new `*-core` for the extracted service.
2. Move the relevant domain types, repository port, and public trait.
3. Move the `Live` implementation into the consumer's `*-infra`.
4. The consumer now uses the producer's public trait directly.

### B → C (adding anti-corruption)

1. Define a port trait in the consumer's `*-core/impl` with the consumer's own types.
2. Define snapshot types that carry only what the consumer needs.
3. Move domain logic from `*-infra` back into `*-core/impl`, rewriting it to use the port.
4. Create an adapter in `*-infra/impl/adapters` that bridges the port to the producer's public service.
5. Update tests to use a stub port instead of a stub service.

### C → B (simplifying)

1. Remove the port trait and snapshot types from `*-core/impl`.
2. Move `Live` from `*-core/impl` to `*-infra`.
3. Replace port usage with direct `OrderService` usage.
4. Update tests to stub the full `OrderService` trait.

### B → A (merging contexts)

1. Move all domain types, ports, and `Live` implementations into a single `*-core`.
2. Remove the separate `*-core` module.
3. Remove cross-context error mapping — the services now share error types (or have separate enums in the same module).

---

## 8. Strategy B in Detail — Practical Patterns

Since Strategy B is the middle ground that many teams will use, here are some practical patterns for making it work well.

### 8.1 Error mapping at the boundary

When `ShippingServiceLive` (in infra) calls `OrderService`, it must translate ordering's errors into shipping's errors:

```scala
private def mapOrderError(err: ExtOrderError): ShippingError =
  err match
    case ExtOrderError.OrderNotFound(id) =>
      ShippingError.OrderNotFound(id.value)
    case ExtOrderError.OrderAlreadyCancelled(id) =>
      ShippingError.OrderNotShippable(id.value, "Order is cancelled")
    case other =>
      // Unexpected ordering errors → generic upstream failure
      ShippingError.UpstreamFailure(other.message)
```

Keep this mapping in a dedicated `private` method. When ordering adds a new error case, the Scala compiler warns you here (if using exhaustive matching).

### 8.2 Extracting only what you need from OrderView

Don't let `OrderView`'s full structure leak into shipping's domain logic. Extract the fields you need early:

```scala
override def shipOrder(input: ShipOrderInput): IO[ShippingError, ShipmentView] =
  for
    orderView <- orderService.getOrder(OrderId(input.orderId))
                   .mapError(mapOrderError)
    // Extract only what shipping needs — right at the boundary
    shippingData = extractShippingData(orderView)
    shipment  <- ZIO.fromEither(createShipment(shippingData, input))
    _         <- shipmentRepo.save(shipment)
  yield toView(shipment)

// Local type — not a full snapshot, just a data holder for this method
private case class ShippingData(customerId: String, itemCount: Int, totalWeight: BigDecimal)

private def extractShippingData(view: OrderView): ShippingData =
  ShippingData(
    customerId  = view.customerId.value,
    itemCount   = view.items.map(_.quantity).sum,
    totalWeight = ??? // calculate from items
  )
```

This is a lightweight version of Strategy C's snapshot type — inline, private, no separate trait. It gives you some insulation without the full ACL ceremony.

### 8.3 Testing without ordering's Live

You don't need `OrderServiceLive` in your tests. Stub the trait:

```scala
object StubOrderService:
  def withOrders(orders: Map[OrderId, OrderView]): ULayer[OrderService] =
    ZLayer.succeed:
      new OrderService:
        def getOrder(id: OrderId) =
          ZIO.fromOption(orders.get(id))
            .mapError(_ => OrderError.OrderNotFound(id))
        def checkout(input: CheckoutInput) = ZIO.die(new NotImplementedError)
        def cancelOrder(id: OrderId) = ZIO.die(new NotImplementedError)
```

The unused methods throw `NotImplementedError` — if a test accidentally calls them, it fails loudly rather than silently.

---

## 9. Hybrid Approaches

Real systems often use different strategies for different relationships:

```
┌─────────────┐     Strategy A      ┌────────────────┐
│  Ordering +  │ ◄─ (same context) ─► Fulfillment    │
│  domain      │                    │  (shared impl)  │
└──────┬───────┘                    └────────────────┘
       │
       │ Strategy B (direct dep, Live in infra)
       │
┌──────▼───────┐
│  Shipping    │
│  (own core)  │
└──────┬───────┘
       │
       │ Strategy C (full ACL)
       │
┌──────▼───────┐
│  Billing     │
│  (own core,  │
│   own team)  │
└──────────────┘
```

- **Ordering ↔ Fulfillment:** Strategy A — they share the `Order` entity and always change together.
- **Shipping → Ordering:** Strategy B — loosely coupled, same team, extraction not imminent.
- **Billing → Ordering:** Strategy C — different team, different change cadence, billing must be stable.

This is perfectly normal. The strategies are tools, not religions.

---

## 10. Summary

1. **Strategy A (same context):** Simplest. Use when services share vocabulary and evolve together. No isolation, no extraction without refactoring first.

2. **Strategy B (direct dependency):** Middle ground. Core modules stay independent, `Live` moves to infra. Consumer uses producer's public types directly. Extraction is possible by swapping the layer, but the remote client must implement the full trait.

3. **Strategy C (anti-corruption layer):** Most isolated. Consumer defines its own port and snapshot types. Immune to producer changes. Extraction requires only swapping a narrow adapter. More code upfront.

4. **Choose based on reality:** vocabulary overlap, team structure, change cadence, extraction timeline.

5. **Strategies are not permanent.** Migrate A→B→C as coupling decreases, or C→B→A as you discover the contexts are more related than you thought.

6. **Hybrid is normal.** Different pairs of contexts can use different strategies in the same system.

---

*See also: [06 — Bounded Contexts](06-bounded-contexts.md), [04 — Service Design](04-service-design.md), [11 — Extraction](11-extraction.md)*
