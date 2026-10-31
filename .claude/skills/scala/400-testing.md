# Testing

What to test where, with zio-test: pure domain functions without layers,
services against in-memory layers, errors as values, deterministic time and
randomness, property-based tests, and integration tests for adapters. Read
this before writing or changing a test.

## Dependencies

- `"dev.zio" %% "zio-test"` — `ZIOSpecDefault`, `assertTrue`, `Gen`, `check`,
  `TestClock`, `TestRandom`
- `"dev.zio" %% "zio-test-sbt"` — sbt runner (`testFrameworks +=
  new TestFramework("zio.test.sbt.ZTestFramework")`)
- `"com.dimafeng" %% "testcontainers-scala-postgresql"` — adapter integration
  tests (infra only)

---

## What goes where

| What | How | Where |
|------|-----|-------|
| pure domain functions, smart constructors | `assertTrue`, no layers | `*-core/src/test` |
| `Live` service logic | zio-test + in-memory layers | `*-core/src/test` |
| error cases | `.exit` + `fails(...)` | `*-core/src/test` |
| persistence adapters | test containers | `*-infra/src/test` |
| HTTP routes | run `Routes` against stubbed services | `*-infra/src/test` |
| anti-corruption adapters | stub the foreign service, test the mapping | `*-infra/src/test` |

Test sources mirror the main package tree with chained package clauses, so they
can reach `private[<context>]` types.

## No mocks

Every port has a real in-memory implementation (`Ref`-backed, see
[Persistence](310-persistence.md#in-memory-implementations)); every
anti-corruption port has a stub layer with fixed data (see [Dependency
Injection](330-dependency-injection.md#test-layers)). Tests verify **behavior and
resulting state**, never which methods were called.

> **Important:** no Mockito, no call-verification mocks. If a test needs to
> know that something was saved, read it back from the in-memory repository.

## One scenario per test

Each test covers exactly one scenario and asserts on its outcome. No
overlapping or redundant tests; no test that walks through several features.
The name says the scenario: `"fails with OrderAlreadyCancelled on double cancel"`.

## Pure domain logic

No layers, no effects:

```scala
suite("Ratio.parse")(
  test("accepts non-negative values"):
    assertTrue(Ratio.parse(BigDecimal("0.5")).isRight),
  test("rejects negative values"):
    assertTrue(Ratio.parse(BigDecimal("-1")) == Left(ValidationError.NegativeRatio(BigDecimal("-1"))))
)
```

## Services with in-memory layers

```scala
package com.myco.ordering
package impl

import zio.*
import zio.test.*
import zio.test.Assertion.*

object OrderServiceLiveSpec extends ZIOSpecDefault:

  private val validInput =
    CheckoutInput(CustomerId("cust-1"), AddressInput("US", "New York", "10001", "123 Main St", ""), List(LineItemInput("SKU-001", 2)))

  def spec = suite("OrderServiceLive")(
    test("checkout creates an unpaid order"):
      for view <- OrderService.checkout(validInput)
      yield assertTrue(view.status == "Unpaid", view.items.size == 1),

    test("checkout fails with EmptyItemList when there are no items"):
      for result <- OrderService.checkout(validInput.copy(items = Nil)).exit
      yield assert(result)(fails(equalTo(OrderError.EmptyItemList))),

    test("cancel fails with OrderAlreadyCancelled on double cancel"):
      for
        created <- OrderService.checkout(validInput)
        _       <- OrderService.cancelOrder(created.id)
        result  <- OrderService.cancelOrder(created.id).exit
      yield assert(result)(fails(isSubtype[OrderError.OrderAlreadyCancelled](anything))),

    test("checkout saves the order"):
      for
        view  <- OrderService.checkout(validInput)
        saved <- ZIO.serviceWithZIO[OrderRepository](_.findById(Order.Id(view.id.value)))
      yield assertTrue(saved.isDefined)
  ).provide(
    OrderServiceLive.layer,
    InMemoryOrderRepository.layer,
    ZLayer.succeed(OrderingConfig(...))
  )
```

- `.provide` on a suite builds the layers **once per test**, so every test
  starts with an empty repository. Use `.provideShared` only for expensive,
  stateless resources (a container), never for in-memory state.
- Errors are values: `.exit` captures the outcome; assert with
  `fails(equalTo(...))` for an exact case or `fails(isSubtype[...](hasField(...)))`
  for fields. Test **one case per error enum case** the method can produce.
- Defects: `dies(anything)` / `dies(isSubtype[...])`. Simulate infrastructure
  failure with a layer whose methods `ZIO.die(...)`.

## Deterministic time and randomness

zio-test replaces `Clock` and `Random` with `TestClock` and `TestRandom`, which
is why main code must use them (see [Effect
Fundamentals](200-effect-fundamentals.md#lawful-apis-time-randomness-identifiers)).

```scala
test("an unpaid order expires after 30 minutes"):
  for
    view   <- OrderService.checkout(validInput)
    _      <- TestClock.adjust(31.minutes)
    _      <- OrderExpiry.expireStale
    result <- OrderService.getOrder(view.id)
  yield assertTrue(result.status == "Expired")
```

- `TestClock.adjust` / `setTime` move time; effects sleeping on the clock wake
  up accordingly, so tests of schedules and timeouts never actually wait.
- `TestRandom.feedUUIDs(...)` / `setSeed(...)` make generated IDs predictable.
- Never `Thread.sleep` or `ZIO.sleep` real time in a test; use `TestClock` (or
  `@@ TestAspect.withLiveClock` only for integration tests).

## Property-based tests

zio-test has built-in generators. Use them for invariants of pure functions,
smart constructors and round-trips:

```scala
val lineItemGen: Gen[Any, LineItem] =
  for
    sku   <- Gen.alphaNumericStringBounded(3, 12)
    qty   <- Gen.int(1, 100)
    price <- Gen.bigDecimal(BigDecimal("0.01"), BigDecimal("9999.99"))
  yield LineItem(LineItem.CatalogueNumber(sku), price, qty)

test("DAO round-trip preserves the order"):
  check(orderGen)(order => assertTrue(OrderDAO.fromDomain(order).toDomain == Right(order)))
```

| Property | Example |
|----------|---------|
| round-trip | `fromDomain(x).toDomain == Right(x)`; `save(x); findById(x.id) == Some(x)` |
| idempotence | `save(x); save(x)` leaves one `x` |
| smart constructor | valid input parses; `parse(v).map(_.value) == Right(v)` |
| invariant | `cart.add(i).total >= cart.total` |

## Persistence adapters

Run against a real database in a container. The container is expensive and
stateless across tests, so share it; give each test fresh data:

```scala
object PostgresOrderRepositorySpec extends ZIOSpecDefault:
  def spec = suite("PostgresOrderRepository")(
    test("round-trips an order"):
      check(orderGen): order =>
        for
          _     <- ZIO.serviceWithZIO[OrderRepository](_.save(order))
          found <- ZIO.serviceWithZIO[OrderRepository](_.findById(order.id))
        yield assertTrue(found.contains(order))
  ).provideShared(
    PostgresContainer.layer,      // ZLayer.scoped around the container
    DataSourceLayer.fromContainer,
    Migrations.layer,
    PostgresOrderRepository.layer
  ) @@ TestAspect.sequential
```

## Anti-patterns

| Anti-pattern | Instead |
|--------------|---------|
| mocking ports with Mockito | in-memory implementation |
| testing private helpers / implementation details | test through the public trait |
| integration tests for pure domain logic | pure unit tests |
| skipping error-case tests | one test per error case |
| state shared between tests | `.provide` builds fresh layers per test |
| real sleeps, `Instant.now()` in tests | `TestClock`, `TestRandom` |
| one test asserting many unrelated things | one scenario per test |
