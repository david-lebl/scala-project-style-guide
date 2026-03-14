# \[WIP\] 09 — Testing

> In-memory layers, property-based testing, integration tests with containers, and test conventions.

---

## 1. Testing Philosophy

| What | How | Where |
|------|-----|-------|
| Pure domain functions | Direct `assert` / `assertTrue`, no layers | `*-core/src/test` |
| Service `Live` logic | zio-test with in-memory layers | `*-core/src/test` |
| Error cases | `.exit` + `fails(…)` assertions | `*-core/src/test` |
| Persistence adapters | Integration tests with test containers | `*-infra/src/test` |
| HTTP routes | Integration tests or contract tests | `*-infra/src/test` |
| Cross-context adapters | Stub the foreign service, test the mapping | `*-infra/src/test` |

**No mocks.** Every repository port and anti-corruption port gets an in-memory implementation. These are real implementations with real behavior, not mock objects that return canned responses. You can verify state, simulate failures, and trust the results.

---

## 2. In-Memory Layers

### Repository test doubles

Because repository ports work with domain entities directly, in-memory implementations are trivial:

```scala
private[ordering] final case class InMemoryOrderRepository(
  ref: Ref[Map[Order.Id, Order]]
) extends OrderRepository:

  def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))

object InMemoryOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer(Ref.make(Map.empty[Order.Id, Order]).map(InMemoryOrderRepository(_)))
```

### Anti-corruption port stubs

For ports that call other contexts, create stubs with configurable data:

```scala
object StubOrderingPort:
  def layer(orders: Map[String, OrderSnapshot]): ULayer[OrderingPort] =
    ZLayer.succeed:
      new OrderingPort:
        def getOrder(orderId: String): Task[Option[OrderSnapshot]] =
          ZIO.succeed(orders.get(orderId))

  val empty: ULayer[OrderingPort] = layer(Map.empty)
```

### Simulating failures

```scala
object FailingOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer.succeed:
      new OrderRepository:
        def save(order: Order): Task[Unit] =
          ZIO.fail(new RuntimeException("DB connection lost"))
        def findById(id: Order.Id): Task[Option[Order]] =
          ZIO.fail(new RuntimeException("DB connection lost"))
```

---

## 3. Testing Domain Logic

### Pure functions — no layers needed

```scala
suite("parseAddress")(
  test("rejects blank city"):
    val input = AddressInput("US", "", "10001", "123 Main", "")
    val result = OrderServiceLive.parseAddress(input) // if exposed for testing
    assertTrue(result == Left(OrderError.InvalidAddress("city is required")))
)
```

### Smart constructors

```scala
suite("Ratio")(
  test("accepts non-negative values"):
    assertTrue(Ratio.parse(BigDecimal("0.5")) == Right(Ratio(BigDecimal("0.5")))),

  test("rejects negative values"):
    assertTrue(Ratio.parse(BigDecimal("-1")).isLeft)
)
```

---

## 4. Testing Services

### Happy path

```scala
suite("OrderServiceLive.checkout")(
  test("creates an order with valid input"):
    val input = CheckoutInput(
      customerId = CustomerId("cust-1"),
      shippingAddress = AddressInput("US", "New York", "10001", "123 Main", ""),
      items = List(LineItemInput("SKU-001", 2))
    )
    for
      view <- OrderService.checkout(input)
    yield assertTrue(
      view.customerId == CustomerId("cust-1"),
      view.status == "Unpaid",
      view.items.size == 1
    )
).provide(
  OrderServiceLive.layer,
  InMemoryOrderRepository.layer,
  UUIDIdGenerator.layer
)
```

### Error cases with `.exit`

The `.exit` method captures the outcome without throwing, so you can assert on specific error cases:

```scala
test("fails with EmptyItemList when items are empty"):
  val input = CheckoutInput(customerId, address, items = List.empty)
  for
    result <- OrderService.checkout(input).exit
  yield assert(result)(fails(equalTo(OrderError.EmptyItemList)))
```

### Asserting on error subtypes and fields

```scala
test("fails with InsufficientStock carrying context"):
  for
    result <- OrderService.checkout(tooManyItemsInput).exit
  yield assert(result)(fails(
    isSubtype[OrderError.InsufficientStock](
      hasField("available", _.available, equalTo(3))
    )
  ))
```

### Verifying side effects (repository state)

```scala
test("saves the order to the repository"):
  for
    view  <- OrderService.checkout(validInput)
    saved <- ZIO.serviceWithZIO[OrderRepository](_.findById(Order.Id(view.id.value)))
  yield assertTrue(saved.isDefined)
```

---

## 5. Testing Persistence Adapters

### Test containers

Use [testcontainers-scala](https://github.com/testcontainers/testcontainers-scala) to spin up a real database:

```scala
import com.dimafeng.testcontainers.PostgreSQLContainer
import zio.*
import zio.test.*

object PostgresOrderRepositorySpec extends ZIOSpecDefault:

  val postgresLayer: TaskLayer[javax.sql.DataSource] =
    ZLayer.scoped:
      ZIO.acquireRelease(
        ZIO.attemptBlocking:
          val container = PostgreSQLContainer()
          container.start()
          createDataSource(container.jdbcUrl, container.username, container.password)
      )(ds => ZIO.attemptBlocking(ds.close()).orDie)

  def spec = suite("PostgresOrderRepository")(
    test("round-trips an order"):
      val order = Order(Order.Id("test-1"), Order.Data(…))
      for
        _     <- ZIO.serviceWithZIO[OrderRepository](_.save(order))
        found <- ZIO.serviceWithZIO[OrderRepository](_.findById(order.id))
      yield assertTrue(found.contains(order))
  ).provide(
    PostgresOrderRepository.layer,
    postgresLayer,
    FlywayMigration.layer   // run migrations before tests
  ) @@ TestAspect.sequential
```

### Round-trip property

The most important persistence test: save a domain entity, load it back, assert equality.

```scala
test("round-trip preserves all fields"):
  check(orderGen): order =>
    for
      _      <- ZIO.serviceWithZIO[OrderRepository](_.save(order))
      loaded <- ZIO.serviceWithZIO[OrderRepository](_.findById(order.id))
    yield assertTrue(loaded.contains(order))
```

---

## 6. Property-Based Testing

ZIO Test has built-in generators. Use them for domain types:

```scala
val orderIdGen: Gen[Any, Order.Id] =
  Gen.uuid.map(u => Order.Id(u.toString))

val lineItemGen: Gen[Any, LineItem] =
  for
    sku  <- Gen.alphaNumericString
    qty  <- Gen.int(1, 100)
    price <- Gen.bigDecimal(BigDecimal("0.01"), BigDecimal("9999.99"))
  yield LineItem(LineItem.CatalogueNumber(sku), price, qty)

val orderGen: Gen[Any, Order] =
  for
    id    <- orderIdGen
    items <- Gen.listOfBounded(1, 10)(lineItemGen)
    // …
  yield Order(id, Order.Data(…, items, OrderStatus.Unpaid))
```

### Useful properties to verify

| Property | Example |
|----------|---------|
| Round-trip | `save(entity); findById(id) == Some(entity)` |
| Idempotent save | `save(entity); save(entity); findById(id) == Some(entity)` |
| Smart constructor | `parse(value).map(_.value) == Right(value)` for valid input |
| Serialisation round-trip | `DAO.fromDomain(entity).toDomain == entity` |

---

## 7. Test Structure Conventions

```
ordering-core/
└── src/test/scala/com/myco/ordering/
    ├── OrderErrorSpec.scala               ← error code/message tests
    └── impl/
        ├── OrderServiceLiveSpec.scala     ← service unit tests
        ├── RatioSpec.scala                ← smart constructor tests
        └── PriceCalculationSpec.scala     ← pure domain logic tests

ordering-infra/
└── src/test/scala/com/myco/ordering/
    └── impl/
        ├── postgres/
        │   └── PostgresOrderRepositorySpec.scala  ← integration tests
        └── http/
            └── OrderRoutesSpec.scala              ← HTTP integration tests
```

Tests use the same chained package clause pattern as production code, giving them access to `private[ordering]` types.

### Shared test utilities

If multiple modules share test helpers (custom generators, assertion utilities), create a `test-commons` module:

```scala
lazy val testCommons = project.in(file("modules/test-commons"))
  .settings(commonDeps)
  .dependsOn(commons)

lazy val orderingCore = project.in(file("modules/ordering-core"))
  .settings(commonDeps)
  .dependsOn(commons, testCommons % Test)
```

---

## 8. Anti-Patterns

| Anti-pattern | Why it's bad | Do this instead |
|-------------|-------------|----------------|
| Mocking repository traits with Mockito | Brittle, verifies calls not behavior, hides bugs | In-memory implementation |
| Testing implementation details | Tests break on refactors that don't change behavior | Test through the public service trait |
| Integration tests for pure domain logic | Slow, flaky, hard to isolate failures | Unit tests with in-memory layers |
| Skipping error case tests | "It compiles" ≠ "it works" | One test per error enum case |
| Shared mutable state across tests | Order-dependent failures, flaky CI | Fresh `Ref` per test (ZLayer reconstructed per test) |
| Testing DAO mapping with the full service stack | Too many moving parts | Test `DAO.fromDomain(x).toDomain == x` directly |

---

## 9. Summary

1. **No mocks.** In-memory implementations are real implementations.
2. **Unit-test services** with in-memory layers in `*-core/src/test`.
3. **Integration-test persistence** with test containers in `*-infra/src/test`.
4. **`.exit` + `fails(…)`** to assert on specific error cases.
5. **Property-based tests** for round-trips and invariants.
6. **One test per error case.** The compiler ensures exhaustiveness in code; tests ensure correctness in behavior.
7. **Fresh state per test.** ZLayer reconstruction gives you isolated test environments.

---

*See also: [04 — Service Design](04-service-design.md), [07 — Dependency Injection](07-dependency-injection.md), [03 — Error Model](03-error-model.md)*
