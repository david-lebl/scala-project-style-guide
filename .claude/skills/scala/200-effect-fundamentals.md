# Effect Fundamentals

How to write side-effecting code as values with ZIO: which type to return,
how to suspend effects, how to obtain time and randomness lawfully, and where
the program actually runs. Read this before writing any code that does I/O.

## Dependencies

- `"dev.zio" %% "zio"` — `ZIO`, `ZLayer`, `Clock`, `Random`, `ZIOAppDefault`

---

## Effects are values

A `ZIO[R, E, A]` is a lazy, immutable *description* of a program that needs an
environment `R`, may fail with `E`, and succeeds with `A`. Nothing happens
until the runtime executes it. Because it's a value, it can be stored, passed,
retried, raced, timed out or composed, and it stays referentially transparent
(see [Functional Principles](130-functional-principles.md)).

Use the aliases that say exactly what can happen:

| Alias | Expands to | Use for |
|-------|------------|---------|
| `UIO[A]` | `ZIO[Any, Nothing, A]` | can't fail with an expected error (repository ports, pure lookups) |
| `IO[E, A]` | `ZIO[Any, E, A]` | domain operations: service methods |
| `Task[A]` | `ZIO[Any, Throwable, A]` | raw third-party calls *inside* an adapter, before `.orDie`/`.refineOrDie` |
| `URIO[R, A]` / `ZIO[R, E, A]` | | accessors, `app` code, tests |

> **Important:** service and port signatures never mention their dependencies
> in `R`. Dependencies are constructor parameters of the `Live` class (see
> [Service Design](300-service-design.md)); `R` appears only in accessor
> methods, layers and the `app` module.

## Suspending side effects

Every side effect is wrapped at the point it happens, with the constructor
that matches its behavior:

| Situation | Constructor |
|-----------|-------------|
| pure value, already computed | `ZIO.succeed(a)` (by-name, so cheap side-effect-free code is fine) |
| synchronous code that may throw | `ZIO.attempt(...)` |
| blocking I/O (JDBC, file, legacy client) | `ZIO.attemptBlocking(...)` |
| `Either` / `Option` from pure code | `ZIO.fromEither`, `ZIO.fromOption`, `.someOrFail` |
| callback API | `ZIO.async` |
| `CompletableFuture` / `Future` | `ZIO.fromCompletableFuture`, `ZIO.fromFuture` |
| expected failure | `ZIO.fail(error)` |

> **Warning:** blocking calls outside `attemptBlocking` stall a thread of ZIO's
> small default executor and can starve every other fiber. Every JDBC call,
> file read or blocking client call goes through `ZIO.attemptBlocking`.

## Composing with for-comprehensions

Sequence dependent steps with `for`/`yield`. Use `=` for pure intermediate
values and `<-` only for effects:

```scala
override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
  for
    items   <- ZIO.fromEither(parseItems(input.items))
    address <- ZIO.fromEither(parseAddress(input.shippingAddress))
    id      <- Random.nextUUID.map(uuid => Order.Id(uuid.toString))
    order    = Order(id, Order.Data(input.customerId, address, items, OrderStatus.Unpaid))
    _       <- orderRepo.save(order)
  yield toView(order)
```

Collections of effects use `ZIO.foreach` / `ZIO.foreachDiscard` (sequential)
or the `Par` variants (see [Concurrency and
Streaming](230-concurrency-and-streaming.md)) — never a `for` loop that runs
effects for their side effect.

## Lawful APIs: time, randomness, identifiers

Code that reads the clock or a random source is not pure unless the source is
a dependency. In ZIO, `Clock` and `Random` are built-in services that the test
runtime replaces with `TestClock` and `TestRandom`, so use them and never the
JVM statics:

```scala
// Wrong — hidden non-determinism, impossible to assert in a test:
def place(order: Order): UIO[Confirmation] =
  ZIO.succeed(Confirmation(UUID.randomUUID(), Instant.now()))

// Right — deterministic under TestClock / TestRandom:
def place(order: Order): UIO[Confirmation] =
  for
    id  <- Random.nextUUID
    now <- Clock.instant
  yield Confirmation(id, now)
```

When identifiers have a domain-specific format (sequences, prefixed codes),
define an `IdGenerator` port in `impl` with a `Live` and a deterministic test
implementation, and inject it like any other dependency.

> **Important:** `UUID.randomUUID()`, `Instant.now()`, `LocalDate.now()`,
> `System.currentTimeMillis()`, `scala.util.Random` and `Thread.sleep` are
> forbidden in main code. Use `Random.nextUUID`, `Clock.instant`,
> `Clock.localDateTime`, `Clock.currentTime`, `Random.nextIntBounded` and
> `ZIO.sleep`.

## Discarding results

Make discarding explicit: `.unit` when you need `UIO[Unit]` from an effect,
`ZIO.foreachDiscard` instead of `ZIO.foreach(...).unit`, `*>` to sequence and
keep the right side. A non-`Unit` effect used as a statement is a bug — it is
never run. `-Wnonunit-statement` catches it:

```scala
// Wrong — the log effect is built and thrown away; nothing is logged:
def cancel(id: OrderId): IO[OrderError, Unit] =
  ZIO.logInfo(s"cancelling $id")
  doCancel(id)

// Right:
def cancel(id: OrderId): IO[OrderError, Unit] =
  ZIO.logInfo(s"cancelling $id") *> doCancel(id)
```

## The edge of the world

The program runs in exactly one place: the `app` module's `ZIOAppDefault`.

```scala
object Main extends ZIOAppDefault:
  override val run: ZIO[Any, Any, Any] =
    program.provide(OrderServiceLive.layer, PostgresOrderRepository.layer, ...)
```

> **Important:** never call `Unsafe.unsafe`, `Runtime.default.unsafe.run`,
> `.unsafeRun*` or `Await` in core or infra code. The only exceptions are
> bridging to a framework callback that must return synchronously, isolated in
> one adapter and commented.

`Future` does not appear in core or infra signatures; convert at the adapter
with `ZIO.fromFuture`.

## Logging

Use ZIO's logging (`ZIO.logInfo`, `ZIO.logWarning`, `ZIO.logErrorCause`,
`ZIO.logAnnotate`) so log calls are effects and carry fiber context. Log at
boundaries — HTTP handlers, event consumers, background loops — not inside
domain logic. Back it with a logging backend layer in `app` (e.g.
`zio-logging-slf4j2`).
