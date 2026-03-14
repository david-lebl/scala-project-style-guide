# \[WIP\] 07 — Dependency Injection

> ZLayer patterns, module composition, test layers, and avoiding common pitfalls.

---

## 1. ZLayer as the DI Mechanism

ZIO's `ZLayer` provides **compile-time verified** dependency injection. If a layer is missing, the code won't compile. No runtime surprises, no reflection, no XML.

---

## 2. Layer Definition Conventions

Every service and repository implementation exposes a `val layer` in its companion object:

```scala
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository,
  idGen:     IdGenerator
) extends OrderService:
  // …

object OrderServiceLive:
  val layer: URLayer[OrderRepository & IdGenerator, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
```

`ZLayer.fromFunction` inspects the constructor's parameter types and automatically wires them from the environment. This is the most common and recommended pattern.


TODO: prefer `ZLayer.derive[OrderServiceLive]`

### Layers that need initialisation

If construction involves side effects (e.g. connecting to a database), use `ZLayer { … }` or `ZLayer.fromZIO`:

```scala
object PostgresOrderRepository:
  val layer: RLayer[javax.sql.DataSource, OrderRepository] =
    ZLayer:
      for
        ds <- ZIO.service[javax.sql.DataSource]
        _  <- ZIO.attemptBlocking(ds.getConnection.close()) // test connection
      yield PostgresOrderRepository(ds)
```

---

### Automatic resolution with `.provide`

ZIO can resolve the layer graph automatically:

```scala
val program: ZIO[OrderService, OrderError, OrderView] =
  OrderService.checkout(input)

program.provide(
  OrderServiceLive.layer,
  InMemoryOrderRepository.layer,
  UUIDIdGenerator.layer
)
// ZIO figures out: IdGen + Repo → OrderServiceLive → OrderService
```

This is the recommended approach for application wiring and tests.

---

## 4. Test Layers

### In-memory implementations

Every repository port and anti-corruption port gets an in-memory `ULayer` for testing:

```scala
object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(InMemoryOrderRepository(_)))
```

### Stub ports

For anti-corruption ports, create stubs that return fixed data or fail predictably:

```scala
object StubOrderingPort:
  def layer(orders: Map[String, OrderSnapshot]): ULayer[OrderingPort] =
    ZLayer.succeed:
      new OrderingPort:
        def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
          ZIO.succeed(orders.get(orderId))

  val empty: ULayer[OrderingPort] = layer(Map.empty)
```

### Fixed config/data

```scala
val testConfig: ULayer[OrderingConfig] =
  ZLayer.succeed(OrderingConfig(maxItemsPerOrder = 100))
```

### Composing a full test environment

```scala
suite("ShippingServiceLive")(
  test("ships an order"):
    for
      result <- ShippingService.shipOrder(input)
    yield assertTrue(result.status == "Shipped")
).provide(
  ShippingServiceLive.layer,
  InMemoryShipmentRepository.layer,
  StubOrderingPort.layer(Map("ord-1" -> testOrderSnapshot))
)
```

---

## 5. Per-Module Layer Bundles

To avoid sprawling `.provide` blocks in the `app` module, each module can expose a composed layer bundle:

```scala
// ordering-core companion or dedicated object
object OrderingLayers:
  val live: URLayer[OrderRepository & IdGenerator, OrderService] =
    OrderServiceLive.layer

  val test: ULayer[OrderService] =
    InMemoryOrderRepository.layer ++ UUIDIdGenerator.layer >>> OrderServiceLive.layer
```

```scala
// ordering-infra
object OrderingInfraLayers:
  val postgres: RLayer[javax.sql.DataSource, OrderRepository] =
    PostgresOrderRepository.layer
```

```scala
// app
object Main extends ZIOAppDefault:
  override val run =
    program.provide(
      OrderingLayers.live,
      OrderingInfraLayers.postgres,
      ShippingLayers.live,
      ShippingInfraLayers.postgres,
      OrderingAdapter.layer,
      HikariDataSource.layer,
      Server.default
    )
```

---

## 6. Avoiding God Wiring

As the application grows, the `app` module's `.provide` block can become enormous. Strategies to manage it:

### Group by context

```scala
val orderingLayers =
  OrderServiceLive.layer ++
  PostgresOrderRepository.layer ++
  UUIDIdGenerator.layer

val shippingLayers =
  ShippingServiceLive.layer ++
  PostgresShipmentRepository.layer ++
  OrderingAdapter.layer

program.provide(
  orderingLayers,
  shippingLayers,
  dataSourceLayer,
  Server.default
)
```

### Visualise the graph

During development, use ZIO's built-in graph visualisation:

```scala
ZLayer.Debug.mermaid  // prints a Mermaid diagram of the layer graph to the console
```

This helps spot unexpected dependencies and circular references.

---

## 8. Summary

1. **`ZLayer.fromFunction`** for constructor-based injection — the default pattern.
2. **`ULayer` for test doubles.** In-memory repos and stub ports never fail to construct.
3. **Horizontal (`++`) and vertical (`>>>`)** composition for combining layers.
4. **Automatic resolution** with `.provide` for wiring.
5. **Per-module bundles** to keep the `app` wiring manageable.
6. **Depend on traits**, not concrete classes.
7. **The compiler catches missing layers.** Trust it.

---

*See also: [04 — Service Design](04-service-design.md), [08 — Configuration](08-configuration.md), [09 — Testing](09-testing.md)*
