# Extraction

Moving a bounded context out of the modulith into its own service without
rewriting its internals: the steps, remote adapters, event-driven and partial
extraction, and the prerequisites. Read this before planning an extraction or
designing anything that would make one harder.

## Dependencies

- `"dev.zio" %% "zio-http"` — `Client` for remote adapters
- `"dev.zio" %% "zio-json"` — wire format

---

## Why the structure makes extraction mechanical

A context built per [Project Structure](100-project-structure.md) is already a
self-contained hexagon:

- `*-core` holds the public contract, domain model and business logic;
- `*-infra` holds every adapter;
- other contexts reach it only through its public trait, via their own
  anti-corruption ports (see [Bounded Contexts](320-bounded-contexts.md)).

Extraction is a **deployment decision**, not an architecture rewrite. Only
adapters change.

## Steps

1. **Publish `*-core` as a library.** Its public package (`OrderService`,
   `OrderId`, `OrderView`, `OrderError`) becomes the client-side contract.
2. **Move `*-core` + `*-infra` to their own deployable** with a new
   `*-app` module (`Main`, server, health checks). The routes in
   `*-infra/impl.http` already expose the service.
3. **Replace the in-process adapter with a remote one** in each consumer's
   infra. It implements the **same port**:

```scala
package com.myco.shipping
package impl
package adapters

import com.myco.ordering.OrderView
import zio.*
import zio.http.*
import zio.json.*

final case class OrderingRemoteAdapter(client: Client, config: OrderingClientConfig) extends OrderingPort:

  override def getOrder(orderId: OrderRef): UIO[Option[OrderSnapshot]] =
    fetch(orderId)
      .timeoutFail(RemoteCallFailed(s"ordering timed out for $orderId"))(config.timeout)
      .retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
      .orDie

  private def fetch(orderId: OrderRef): Task[Option[OrderSnapshot]] =
    ZIO.scoped:
      client.request(Request.get(config.baseUrl / "v1" / "orders" / orderId.value)).flatMap: response =>
        response.status match
          case Status.Ok       => response.body.asString.flatMap(decodeView).map(view => Some(toSnapshot(view)))
          case Status.NotFound => ZIO.none
          case other           => ZIO.fail(RemoteCallFailed(s"ordering returned $other"))

  // JsonDecoder[OrderView] comes with the published contract or is defined in this package.
  private def decodeView(body: String): Task[OrderView] =
    ZIO.fromEither(body.fromJson[OrderView]).mapError(RemoteCallFailed(_))

  private def toSnapshot(view: OrderView): OrderSnapshot = ???

private[adapters] final case class RemoteCallFailed(reason: String) extends Exception(reason)

object OrderingRemoteAdapter:
  val layer: URLayer[Client & OrderingClientConfig, OrderingPort] =
    ZLayer.fromFunction(OrderingRemoteAdapter.apply)
```

4. **Zero domain changes.** `ShippingServiceLive` depends on `OrderingPort`; it
   can't tell a local call from a remote one.

## Remote adapter concerns

- **Timeouts** on every call (`timeoutFail`), from config.
- **Retries** with backoff via `Schedule`, only for idempotent requests.
- **Circuit breaker** for unstable dependencies — a library or a small
  `Ref`-based state machine inside the adapter.
- **Contract** — both sides use the public DTOs of the published `*-core`, so
  the wire format is defined once.
- **Error translation** stays the same as in-process: 404 → `None`, other
  failures → defect at the adapter boundary.

## Event-driven extraction

Instead of synchronous calls, the consumer keeps a local projection fed by the
extracted service's events:

1. The extracted service publishes its public `OrderEvent`s to a broker
   (written to an outbox in the same transaction as the state change).
2. The consumer's infra subscribes and upserts a local projection.
3. The port implementation reads the projection. The port itself — and the
   consumer's core — is unchanged.

```scala
final case class ProjectedOrderingAdapter(projection: OrderProjectionRepository) extends OrderingPort:
  override def getOrder(orderId: OrderRef): UIO[Option[OrderSnapshot]] =
    projection.find(orderId)
```

## Partial extraction

- **Extract writes, keep reads local** — commands go to the new service; the
  monolith serves queries from an event-fed projection (a natural CQRS split).
- **Extract hot paths first** — the context that needs independent scaling.

## Pre-extraction checklist

- [ ] The context has its own `*-core` and `*-infra`.
- [ ] No other `*-core` depends on its `*-core`.
- [ ] All cross-context communication goes through anti-corruption ports.
- [ ] It owns its database schema — no tables shared with other contexts.
- [ ] Its HTTP routes exist and are tested.
- [ ] Its error codes are stable and documented.
- [ ] Health check, metrics and structured logging are in place.
- [ ] Its `*-core` public package can be versioned and published.

Any unchecked item is a prerequisite, not a nice-to-have.

## After extraction

- Propagate trace IDs between services; structured logs carry service name and
  trace ID.
- Migrate data with copy → change-data-capture or dual-write → cut over reads →
  drop old tables.
- Keep the in-process adapter available (behind config) until the remote one is
  proven; rollback is swapping the layer back.
