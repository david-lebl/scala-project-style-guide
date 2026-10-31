# Dependency Injection

Wiring with `ZLayer`: how each implementation exposes its layer, how layers
compose, how the `app` module assembles the graph, and how tests swap
implementations. Read this before adding a dependency to a constructor or
touching `.provide`.

## Dependencies

- `"dev.zio" %% "zio"` — `ZLayer`

---

## `ZLayer` is the only DI mechanism

Dependencies are constructor parameters. `ZLayer` builds them, verifies the
graph at compile time (a missing layer is a compile error), and manages the
lifecycle of resources it creates. No DI framework, no reflection, no
`given` services, no global objects.

## Layer conventions

Every implementation exposes a `val layer` in its companion object. The output
type is the **trait** (port or service), never the implementation class:

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  config:    OrderingConfig
) extends OrderService:
  ...

object OrderServiceLive:
  val layer: URLayer[OrderRepository & OrderingConfig, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
```

| Construction | Use |
|--------------|-----|
| `ZLayer.fromFunction(Impl.apply)` | plain constructor, no effects — the default |
| `ZLayer.derive[Impl]` | same, derived from the constructor (returns `Impl`; widen with `.project` or an explicit type if needed) |
| `ZLayer.succeed(value)` | a constant (fixed config, stub) |
| `ZLayer(effect)` / `ZLayer.fromZIO` | construction needs an effect (create a `Ref`, validate connectivity) |
| `ZLayer.scoped(effect)` | construction acquires a resource or starts a background fiber (see [Resources and Lifecycle](220-resources-and-lifecycle.md)) |

```scala
object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(InMemoryOrderRepository(_)))
```

Layer types use the aliases: `ULayer[Out]`, `URLayer[In, Out]`, `TaskLayer[Out]`,
`RLayer[In, Out]`. A layer that can fail at startup (config loading,
connecting) is fine — startup failure is the correct behavior.

> **Important:** wiring happens only in `app` and in tests. Core and infra
> modules define layers; they never call `.provide`, `.provideLayer` or build
> the graph themselves.

## Assembling the graph with `.provide`

`.provide` resolves the graph automatically from a flat list and reports
missing or ambiguous layers at compile time:

```scala
object Main extends ZIOAppDefault:
  override val run: ZIO[Any, Any, Any] =
    Server.serve(OrderRoutes.routes ++ ShippingRoutes.routes).provide(
      // config
      OrderingConfig.layer,
      PostgresConfig.layer,
      // infrastructure
      DataSourceLayer.live,
      PostgresOrderRepository.layer,
      // services
      OrderServiceLive.layer,
      ShippingServiceLive.layer,
      OrderingAdapter.layer,
      Server.default
    )
```

Use `ZLayer.Debug.mermaid` (or `.tree`) inside `.provide` during development to
print the graph.

Manual composition, when needed: `a ++ b` (horizontal: both outputs), `a >>> b`
(vertical: feed `a` into `b`), `a >+> b` (feed and keep `a`'s output).

## Per-module bundles

When the `app` wiring grows, group by context rather than by layer kind:

```scala
// ordering-infra
object OrderingModule:
  val live: RLayer[DataSource, OrderService] =
    ZLayer.makeSome[DataSource, OrderService](
      OrderingConfig.layer,
      PostgresOrderRepository.layer,
      OrderServiceLive.layer
    )
```

```scala
// app
program.provide(DataSourceLayer.live, OrderingModule.live, ShippingModule.live, Server.default)
```

A bundle's output is the context's **public** service; its internals
(repository, config) stay hidden from `app` and from other contexts.

## Test layers

Tests swap implementations by providing different layers to the same code:

```scala
// in-memory port
InMemoryOrderRepository.layer

// stub for an anti-corruption port
object StubOrderingPort:
  def layer(orders: Map[OrderRef, OrderSnapshot]): ULayer[OrderingPort] =
    ZLayer.succeed:
      new OrderingPort:
        def getOrder(orderId: OrderRef): UIO[Option[OrderSnapshot]] = ZIO.succeed(orders.get(orderId))

// fixed config
val testConfig: ULayer[OrderingConfig] = ZLayer.succeed(OrderingConfig(maxItemsPerOrder = 100))
```

```scala
suite("ShippingServiceLive")(...).provide(
  ShippingServiceLive.layer,
  InMemoryShipmentRepository.layer,
  StubOrderingPort.layer(Map(orderRef -> snapshot))
)
```

See [Testing](400-testing.md) for when to share layers across tests.

## Rules

1. Depend on traits, never on `Live` / adapter classes.
2. One `layer` per implementation, in its companion.
3. No `R` in service or port method signatures — only in accessors and layers.
4. Trust the compiler: if `.provide` compiles, the graph is complete.
