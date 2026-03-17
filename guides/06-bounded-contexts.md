# 06 — Bounded Contexts

> Anti-corruption layers, cross-context communication, merging heuristic, and event-driven decoupling.

---

## 1. The Independence Rule

**Core modules never depend on other core modules.** Every `*-core` depends only on `commons`. This is non-negotiable.

Why? Because if `shipping-core` depends on `ordering-core`:

- You can't extract either context to a microservice without untangling the dependency.
- If ordering later needs shipping data, you get a circular dependency.
- Shipping's domain code is polluted with ordering's vocabulary.

The only cross-context path: **port in core → adapter in infra**.

---

## 2. Anti-Corruption Layer — Full Pattern

When a bounded context needs data from another context, it defines its own port — a trait in its own language — and the infra module provides the adapter.

### Step 1: Port in the consumer's core

The shipping context defines what it needs from the outside world using **its own types**:

```scala
// shipping-core: .../impl/OrderingPort.scala

package com.myco.shipping
package impl

import zio.*

// Shipping's view of an order — only the fields shipping cares about.
// This is NOT com.myco.ordering.OrderView. Different context, different type.
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

### Step 2: Live service uses the port

The `Live` service depends on `OrderingPort`, not on `OrderService`:

```scala
// shipping-core: .../impl/ShippingServiceLive.scala

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

### Step 3: Adapter in the consumer's infra

The adapter bridges the port to the real service. It lives in `shipping-infra`, which is allowed to depend on `ordering-core`:

```scala
// shipping-infra: .../impl/adapters/OrderingAdapter.scala

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

### Error translation in the adapter

The adapter is the **translation boundary** and the `.orDie` boundary. Foreign errors are converted to:

- `Option.None` — for "not found" cases the consumer expects.
- Defect (via `.orDie`) — for unexpected foreign errors. The adapter owns this conversion, not the service.
- The consumer's own domain error — only if the foreign error maps to a specific domain case.

**Never expose another context's error type** in your domain code. The port returns `UIO` (or `IO[ConsumerDomainError, A]`), never `Task`.

---

## 3. Snapshot Types vs Reusing Public DTOs

You might be tempted to skip the snapshot type and use `OrderView` directly inside shipping. Don't.

**Reasons to define your own snapshot:**

- **Different vocabulary.** Shipping might call it a "fulfillment request", not an "order view".
- **Different fields.** Shipping doesn't need the order's total or currency.
- **Independent evolution.** If ordering adds fields to `OrderView`, shipping shouldn't be forced to update.
- **Extraction readiness.** When ordering becomes a microservice, the adapter changes but the port and snapshot stay the same.

**The exception:** truly universal types from `commons` (like `Money` or `Country`) can be used across contexts without wrapping.

---

## 4. Multiple Services in One Bounded Context

If two services share most of their domain vocabulary and evolve together, they belong in the **same bounded context** with a shared `impl` package:

```
ordering-core/
└── com/myco/ordering/
    ├── OrderService.scala
    ├── FulfillmentService.scala    ← second public service
    ├── OrderError.scala
    ├── FulfillmentError.scala
    └── impl/
        ├── Order.scala               ← shared domain entity
        ├── Shipment.scala
        ├── OrderRepository.scala
        ├── ShipmentRepository.scala
        ├── OrderServiceLive.scala
        └── FulfillmentServiceLive.scala
```

`FulfillmentServiceLive` can use `OrderRepository` directly — same `impl` package, same domain model. No anti-corruption layer, no adapter, no translation. This is **much simpler** when the coupling is genuine.

### The merging heuristic

| Signal | Action |
|--------|--------|
| Services share >50% of domain vocabulary | Merge into one context |
| Data models change in lockstep | Merge |
| Same domain experts own both | Merge |
| Different vocabularies with edge overlap | Separate contexts + ACL |
| Different change cadences | Separate |
| Different deployment needs | Separate |

If you started with separate contexts and the anti-corruption layer feels like pure boilerplate (snapshot types mirror the other context's DTOs exactly), that's a strong signal to merge.

---

## 5. Event-Driven Decoupling

For asynchronous communication, define events in the **public package** of the publishing context:

```scala
// ordering-core: .../com/myco/ordering/OrderEvent.scala
package com.myco.ordering

enum OrderEvent:
  case OrderPlaced(orderId: OrderId, items: List[LineItemView])
  case OrderCancelled(orderId: OrderId)
```

The consumer subscribes via its anti-corruption adapter. The adapter translates the event into the consumer's own domain types:

```scala
// shipping-infra: .../impl/adapters/OrderEventConsumer.scala

package com.myco.shipping
package impl
package adapters

import com.myco.ordering.OrderEvent
import zio.*

final case class OrderEventConsumer(
  shipmentRepo: ShipmentRepository
) /* extends some event handler trait */:

  def handle(event: OrderEvent): Task[Unit] = event match
    case OrderEvent.OrderPlaced(orderId, items) =>
      // translate to shipping's domain and process
      val snapshot = OrderSnapshot(orderId.value, ???, items.map(…), ???)
      // create pending shipment, etc.
      ???
    case OrderEvent.OrderCancelled(orderId) =>
      // cancel pending shipment
      ???
```

### Choosing sync vs async

| Characteristic | Synchronous (direct call) | Asynchronous (events) |
|---------------|---------------------------|----------------------|
| Consistency | Strong (immediate) | Eventual |
| Coupling | Runtime dependency on other service | Decoupled |
| Failure handling | Caller handles failure | Retry, dead-letter |
| Complexity | Lower | Higher (event bus, idempotency) |
| Best for | Queries, validation checks | Notifications, reactions |

Use synchronous calls (via anti-corruption port) when you need the result immediately. Use events when you're notifying other contexts about something that happened.

---

## 6. Cross-Context Data Flow

```
┌─────────────────────────────────────────────────────────┐
│                    shipping-core                         │
│                                                         │
│  ShippingServiceLive → OrderingPort → OrderSnapshot     │
│                           ↑                             │
└───────────────────────────┼─────────────────────────────┘
                            │ implements
┌───────────────────────────┼─────────────────────────────┐
│                    shipping-infra                         │
│                                                         │
│  OrderingAdapter(orderService: OrderService)             │
│       ↓                                                 │
│  orderService.getOrder(…) → OrderView → toSnapshot(…)   │
│                                                         │
└─────────────────────────────────────────────────────────┘
                            │ calls
┌─────────────────────────────────────────────────────────┐
│                    ordering-core                         │
│                                                         │
│  OrderService.getOrder(…) → OrderView                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

No arrow between `shipping-core` and `ordering-core`. The adapter is the only bridge.

---

## 7. Summary

1. **Core modules never depend on each other.** Cross-context communication uses port + adapter.
2. **Ports use the consumer's own types.** `OrderSnapshot`, not `OrderView`.
3. **Adapters translate at the boundary.** Foreign errors → `Option`/defects/local errors.
4. **Merge when coupling is genuine.** Multiple services in one context is simpler than a thin ACL.
5. **Events for async, ports for sync.** Choose based on consistency and coupling needs.

---

*See also: [01 — Project Structure](01-project-structure.md), [04 — Service Design](04-service-design.md), [11 — Extraction](11-extraction.md)*
