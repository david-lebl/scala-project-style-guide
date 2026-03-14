# \[WIP\] 11 — Extraction

> Modulith → microservice, client stubs, remote adapters, and event-driven splits.

---

## 1. Why the Module Split Enables Extraction

The two-layer module structure (`*-core` + `*-infra`) is designed from day one for extraction. A bounded context is already a self-contained hexagon:

- `*-core` contains the public contract, domain model, and business logic.
- `*-infra` contains infrastructure adapters (persistence, HTTP, cross-context bridges).
- Other contexts communicate through the public service trait only — never through internals.
- Anti-corruption ports mean consumers define their own snapshot types, insulating them from changes in the extracted service.

Extraction is a **deployment decision**, not an architecture rewrite.

---

## 2. Extraction Steps

### Step 1: Publish `*-core` as a library artefact

The core module becomes a published library (Maven/Ivy). Other contexts already depend on it only for its public package.

```scala
// ordering-core/build.sbt addition
publishTo := Some("internal" at "https://repo.myco.com/maven2")
```

The public types (`OrderService`, `OrderId`, `OrderView`, `OrderError`) become the **client-side contract**.

### Step 2: Move `*-core` + `*-infra` to their own deployable

Create a new repository or deployable project containing:

```
ordering-service/
├── ordering-core/       ← as before
├── ordering-infra/      ← as before (persistence, HTTP routes)
└── ordering-app/        ← new: own Main, server, health checks
    └── Main.scala
```

The extracted service exposes its functionality over HTTP/gRPC using the routes already defined in `*-infra/impl.http`.

### Step 3: Create a remote adapter in the monolith

In the consuming monolith, replace the in-process `OrderingAdapter` with a remote adapter that calls the extracted service over the network:

```scala
// shipping-infra: .../impl/adapters/OrderingRemoteAdapter.scala

package com.myco.shipping
package impl
package adapters

import com.myco.ordering.{OrderId, OrderView}
import zio.*
import zio.http.*

final case class OrderingRemoteAdapter(
  client:  Client,
  baseUrl: URL
) extends OrderingPort:

  override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
    val url = baseUrl / "orders" / orderId
    for
      response <- client.request(Request.get(url))
      result   <- response.status match
        case Status.Ok       => response.body.to[OrderView].map(v => Some(toSnapshot(v)))
        case Status.NotFound => ZIO.none
        case other           => ZIO.fail(new RuntimeException(s"Ordering returned $other"))
    yield result

  private def toSnapshot(view: OrderView): OrderSnapshot = ???

object OrderingRemoteAdapter:
  val layer: URLayer[Client & OrderingServiceConfig, OrderingPort] =
    ZLayer.fromFunction(OrderingRemoteAdapter.apply)
```

### Step 4: Zero changes to domain code

`ShippingServiceLive` depends on `OrderingPort` — it doesn't know or care whether the port calls a local service or a remote one. **No domain code changes.**

---

## 3. Before / After

```
BEFORE (modulith)                        AFTER (microservice extracted)
┌─────────────────────────────┐         ┌────────────────────────────┐
│ app                         │         │ ordering-service            │
│  ├─ ordering-infra          │  ──►    │  ├─ ordering-core           │
│  │   └─ OrderingAdapter     │         │  ├─ ordering-infra          │
│  ├─ shipping-infra          │         │  └─ ordering-app (Main)     │
│  └─ …                       │         └────────────────────────────┘
└─────────────────────────────┘
                                        ┌────────────────────────────┐
                                        │ main-app (monolith)         │
                                        │  ├─ shipping-infra          │
                                        │  │   └─ OrderingRemoteAdapter│
                                        │  └─ …                       │
                                        └────────────────────────────┘
```

The only change: `OrderingAdapter` (in-process) → `OrderingRemoteAdapter` (HTTP). Everything else — `ShippingServiceLive`, `OrderingPort`, `OrderSnapshot` — stays identical.

---

## 4. Remote Adapter Concerns

### Timeout and retry

The remote adapter should handle transient failures:

```scala
override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
  callOrderingService(orderId)
    .timeout(5.seconds)
    .retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
```

### Circuit breaker

For more resilient communication, wrap calls in a circuit breaker (e.g. using `zio-resilience` or a simple `Ref`-based state machine):

```scala
// Conceptual — actual implementation depends on library choice
override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
  circuitBreaker.protect(callOrderingService(orderId))
```

### Serialisation contract

The remote adapter and the extracted service must agree on the JSON/protobuf format. Since both use the same public DTOs from `ordering-core`, the contract is already defined.

If using Tapir, you can generate both server and client from the same endpoint definition:

```scala
// In ordering-core (public)
val getOrderEndpoint = endpoint.get.in("orders" / path[String]("id")).out(jsonBody[OrderView])

// In ordering-infra (server)
val serverEndpoint = getOrderEndpoint.zServerLogic(id => OrderService.getOrder(OrderId(id)))

// In shipping-infra (client)
val client = SttpClientInterpreter().toClient(getOrderEndpoint, baseUri)
```

---

## 5. Event-Driven Extraction

Instead of synchronous HTTP calls, extract using events:

### Step 1: Publisher emits events

The extracted ordering service publishes events to a message broker (Kafka, RabbitMQ, etc.):

```scala
// ordering-infra — event publishing adapter
private def publishEvent(event: OrderEvent): Task[Unit] =
  kafkaProducer.send(Topic("order-events"), event.toJson)
```

### Step 2: Consumer subscribes

The monolith's shipping context subscribes and updates a local projection:

```scala
// shipping-infra — event consumer adapter

final case class OrderEventConsumer(
  projectionRepo: OrderProjectionRepository
) extends OrderingPort:

  def startConsuming: Task[Unit] =
    kafkaConsumer.subscribe(Topic("order-events")).foreach: event =>
      event match
        case OrderEvent.OrderPlaced(orderId, items) =>
          projectionRepo.upsert(toSnapshot(orderId, items))
        case OrderEvent.OrderCancelled(orderId) =>
          projectionRepo.remove(orderId.value)

  // The port now reads from the local projection
  override def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
    projectionRepo.find(orderId)
```

### Step 3: Port still the same

`ShippingServiceLive` still uses `OrderingPort.getOrder(…)`. Whether it reads from an HTTP call, a local projection, or an in-memory map makes no difference to the domain code.

---

## 6. Partial Extraction

You don't have to extract everything at once. Common patterns:

### Extract writes, keep reads local

The extracted service handles commands (create, cancel). The monolith maintains a read-optimised projection via events:

```
Commands (POST /orders, POST /orders/:id/cancel)
  → ordering-service (extracted)
  → publishes OrderPlaced, OrderCancelled events

Queries (GET /orders/:id, GET /orders?customer=…)
  → main-app (monolith)
  → reads from local projection table
  → updated by event consumer
```

This is a natural CQRS split that reduces the extracted service's surface area.

### Extract hot paths first

If one context has high traffic, extract it first to scale independently. Leave low-traffic contexts in the monolith.

---

## 7. Pre-Extraction Checklist

Before extracting a bounded context, verify:

- [ ] Context has its own `*-core` and `*-infra` modules.
- [ ] No other `*-core` depends on this context's `*-core` (only `*-infra` modules reference it for adapters).
- [ ] All cross-context communication goes through anti-corruption ports.
- [ ] Context has its own database schema — no shared tables with other contexts.
- [ ] HTTP routes are defined and tested in `*-infra/impl.http`.
- [ ] Error types have stable codes suitable for API consumers.
- [ ] Health check, metrics endpoints, and structured logging are in place.
- [ ] The `*-core` public package is versioned and can be published as a library.

If any of these aren't met, address them first — they are prerequisites, not nice-to-haves.

---

## 8. Post-Extraction Considerations

### Observability

- **Distributed tracing.** Propagate trace IDs via HTTP headers between services.
- **Centralised logging.** Structured logs with service name, trace ID, span ID.
- **Health checks.** `/health` endpoint in the extracted service.
- **Metrics.** Request count, latency, error rate per endpoint.

### Data migration

If the extracted service needs its own database, migrate the relevant tables:

1. Copy the schema and data.
2. Set up change data capture or dual-writes during the transition.
3. Cut over reads to the new database.
4. Remove the old tables from the monolith's schema.

### Rollback plan

Keep the in-process adapter code (just commented out or behind a feature flag) until the remote adapter is proven stable. Rollback = swap the adapter back.

---

## 9. Summary

1. **Extraction is mechanical, not architectural.** The module boundaries are already in place.
2. **Three steps.** Publish `*-core` → move `*-core` + `*-infra` to own deployable → swap adapter.
3. **Zero domain code changes.** Only the adapter (port implementation) changes.
4. **Remote adapters handle resilience.** Timeout, retry, circuit breaker.
5. **Events enable async extraction.** Local projections replace synchronous calls.
6. **Partial extraction is fine.** Extract writes or hot paths first.
7. **Verify the checklist** before extracting. Missing prerequisites will bite you.

---

*See also: [06 — Bounded Contexts](06-bounded-contexts.md), [01 — Project Structure](01-project-structure.md), [10 — API Design](10-api-design.md)*
