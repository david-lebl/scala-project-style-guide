# Bounded Contexts

How bounded contexts talk to each other without coupling their cores: the
Independence Rule, the anti-corruption layer (port + adapter), snapshot types,
when to merge contexts instead, and event-driven decoupling. Read this before
one context calls, imports, or reacts to another.

## Dependencies

- `"dev.zio" %% "zio"`

---

## The Independence Rule

**Core modules never depend on other core modules.** Every `*-core` depends
only on `commons`.

If `shipping-core` depended on `ordering-core`:

- neither context could be extracted to its own service without untangling it;
- the moment ordering needs shipping data, the dependency becomes a cycle;
- shipping's domain code would be written in ordering's vocabulary.

The only cross-context path is **port in the consumer's core → adapter in the
consumer's infra**. More code (a port, a snapshot type, an adapter) is the
accepted price for independent evolution, testing and extraction.

## Anti-corruption layer

### Step 1: port in the consumer's core

The consumer states what it needs, in **its own types**:

```scala
// shipping-core
package com.myco.shipping
package impl

import zio.*

// Shipping's view of an order — only the fields shipping cares about.
private[shipping] final case class OrderSnapshot(
  orderId:    OrderRef,
  customerId: RecipientId,
  items:      NonEmptyChunk[OrderSnapshot.Item]
)
private[shipping] object OrderSnapshot:
  final case class Item(sku: Sku, quantity: Quantity)

private[shipping] trait OrderingPort:
  def getOrder(orderId: OrderRef): UIO[Option[OrderSnapshot]]
```

### Step 2: the `Live` depends on the port

```scala
private[shipping] final case class ShippingServiceLive(
  orderingPort: OrderingPort,
  shipmentRepo: ShipmentRepository
) extends ShippingService:

  override def shipOrder(input: ShipOrderInput): IO[ShippingError, ShipmentView] =
    for
      snapshot <- orderingPort.getOrder(OrderRef(input.orderId))
                    .someOrFail(ShippingError.OrderNotFound(input.orderId))
      shipment <- ZIO.fromEither(Shipment.create(snapshot, input))
      _        <- shipmentRepo.save(shipment)
    yield toView(shipment)
```

### Step 3: adapter in the consumer's infra

`shipping-infra` may depend on `ordering-core` — only the adapter sees it:

```scala
// shipping-infra
package com.myco.shipping
package impl
package adapters

import com.myco.ordering.{OrderError as ExtOrderError, OrderId, OrderService, OrderView}
import zio.*

final case class OrderingAdapter(orderService: OrderService) extends OrderingPort:

  override def getOrder(orderId: OrderRef): UIO[Option[OrderSnapshot]] =
    orderService.getOrder(OrderId(orderId.value))
      .map(view => Some(toSnapshot(view)))
      .catchSome:
        case _: ExtOrderError.OrderNotFound => ZIO.none
      .orDieWith(err => RuntimeException(s"Ordering failed: ${err.message}"))

  private def toSnapshot(view: OrderView): OrderSnapshot = ???

object OrderingAdapter:
  val layer: URLayer[OrderService, OrderingPort] =
    ZLayer.fromFunction(OrderingAdapter.apply)
```

### Error translation

The adapter is both the translation boundary and the `.orDie` boundary. A
foreign error becomes:

- `None` — for "not found" the consumer expects;
- the consumer's own error case — only when it maps to a specific domain case;
- a defect — for everything else.

> **Important:** never expose another context's error type, DTO or ID in your
> core. The port returns `UIO[A]` or `IO[<OwnError>, A]`, never `Task`, never
> the foreign error.

## Snapshot types, not foreign DTOs

Don't reuse `OrderView` inside shipping, even if the fields match today:

- **Vocabulary** — shipping may call it a "fulfillment request".
- **Fields** — shipping needs a subset.
- **Evolution** — ordering can add fields without forcing shipping to change.
- **Extraction** — when ordering moves out, only the adapter changes.

Exception: truly universal types from `commons` (`Money`, `Country`).

## Merging heuristic

Separate contexts are not free. If two services share most of their
vocabulary, they belong in **one context** with a shared `impl` package (see
[Service Design](300-service-design.md#multiple-services-in-one-context)).

| Signal | Action |
|--------|--------|
| share more than half their domain vocabulary | merge |
| data models change in lockstep | merge |
| same domain experts own both | merge |
| different vocabularies overlapping only at the edges | separate + ACL |
| different change cadence or deployment needs | separate |

If the snapshot types mirror the other context's DTOs one-to-one and the
adapter is pure boilerplate, that's the signal to merge.

## Event-driven decoupling

Published events are part of the publishing context's public contract:

```scala
package com.myco.ordering

enum OrderEvent:
  case OrderPlaced(orderId: OrderId, items: List[LineItemView])
  case OrderCancelled(orderId: OrderId)
```

The consumer's infra subscribes and translates each event into its own domain
before calling its own core:

```scala
// shipping-infra
final case class OrderEventConsumer(shipping: ShippingCommands):
  def handle(event: OrderEvent): UIO[Unit] = event match
    case OrderEvent.OrderPlaced(orderId, items) =>
      shipping.preparePending(toPendingShipment(orderId, items))
    case OrderEvent.OrderCancelled(orderId) =>
      shipping.cancelPending(OrderRef(orderId.value))
```

In a modulith, a `Hub[OrderEvent]` distributes events in-process; after
extraction a broker replaces it without touching either core. Persist the
event together with the state change (an outbox in the same transaction) when
it must not be lost; consumers must be idempotent.

| | Synchronous port | Asynchronous events |
|--|------------------|---------------------|
| consistency | immediate | eventual |
| coupling | runtime dependency | decoupled |
| failure handling | caller handles | retry, dead-letter |
| best for | queries, validation | notifications, reactions |
