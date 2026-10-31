# Concurrency and Streaming

Running work in parallel, sharing state between fibers, coordinating
producers and consumers, and processing unbounded data. Read this before
forking a fiber, introducing shared state, or processing a collection whose
size you don't control.

## Dependencies

- `"dev.zio" %% "zio"` — fibers, `Ref`, `Queue`, `Hub`, `Promise`, `Semaphore`,
  `Schedule`
- `"dev.zio" %% "zio-streams"` — `ZStream`, `ZSink`, `ZPipeline`

---

## Choosing a primitive

Use the highest-level primitive that fits:

| Need | Use |
|------|-----|
| a few independent effects, all results needed | `a <&> b`, `a.zipPar(b)`, `ZIO.collectAllPar` |
| the same effect over a collection, bounded parallelism | `ZIO.foreachPar(xs)(f).withParallelism(n)` |
| first result wins, others cancelled | `a.race(b)`, `ZIO.raceAll` |
| upper bound on duration | `.timeout(d)` / `.timeoutFail(err)(d)` |
| shared state with pure updates | `Ref` |
| shared state whose update runs an effect | `Ref.Synchronized` |
| producer/consumer hand-off with back-pressure | bounded `Queue` |
| one producer, many subscribers | `Hub` |
| one-shot signal or result between fibers | `Promise` |
| limit concurrent access to a resource | `Semaphore` |
| unbounded or large data, incremental processing | `ZStream` |
| periodic or retried work | `Schedule` with `.repeat` / `.retry` |

> **Important:** never use `Thread`, `ExecutorService`, `synchronized`, `Lock`,
> `volatile`, `java.util.concurrent` queues or `AtomicReference` in main code.
> They block threads ZIO doesn't know about, ignore interruption, and escape
> structured concurrency.

## Structured concurrency

Prefer combinators (`zipPar`, `foreachPar`, `race`) to manual forking: they
fork, join, propagate failure and interrupt the losers for you.

When you do fork, the fiber's lifetime must match a scope:

| Operator | Lifetime | Use |
|----------|----------|-----|
| `.fork` | interrupted when the parent fiber ends | join before returning |
| `.forkScoped` | interrupted when the enclosing `Scope` closes | background work owned by a scoped layer |
| `.forkDaemon` | global; survives everything | never in application code |

```scala
// Wrong — returns a running fiber; nobody owns it, nobody joins it:
def loadBoth(userId: UserId, accountId: AccountId): UIO[(Fiber[Nothing, User], Fiber[Nothing, Account])] =
  loadUser(userId).fork <*> loadAccount(accountId).fork

// Right — concurrency is contained and joined before returning:
def loadBoth(userId: UserId, accountId: AccountId): UIO[(User, Account)] =
  loadUser(userId) <&> loadAccount(accountId)
```

> **Warning:** never return a `Fiber` or an object that owns running fibers to
> be driven by the caller. Its lifetime then matches no scope, and
> cancellation, error propagation and cleanup become manual. Return a plain
> value, or start the fiber inside a scoped layer.

## Shared state: `Ref`

`Ref[A]` is an atomic, fiber-safe mutable reference whose operations are
effects. Model the state as an immutable value and update it with pure
functions:

```scala
private[ordering] final case class InMemoryOrderRepository(ref: Ref[Map[Order.Id, Order]])
    extends OrderRepository:
  override def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  override def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))
```

- Read-modify-write is `update`, `modify` (returns a value computed from the
  old state), `getAndUpdate` or `updateAndGet`.
- The function passed to `update`/`modify` may be retried under contention:
  it MUST be pure — no logging, no I/O.
- When the update itself needs an effect (e.g. call a service to compute the
  new state), use `Ref.Synchronized` and `updateZIO`/`modifyZIO`, which
  serialise updates.

> **Warning:** never `ref.get` followed by `ref.set(newState)`. Another fiber
> can update between the two calls, and its change is silently lost.

> **Warning:** a `Ref` is not a lifecycle flag. Don't poll a `Ref[Boolean]` to
> stop a loop — interrupt the fiber or close its scope.

## Queues and hubs

A bounded `Queue` hands work from producers to a consumer with back-pressure:
`offer` suspends when full, `take` suspends when empty. Always bound queues;
an unbounded queue turns a slow consumer into an out-of-memory error.

```scala
private[ordering] final case class EmailWorker(queue: Queue[EmailRequest]):
  def submit(request: EmailRequest): UIO[Unit] = queue.offer(request).unit

private[ordering] object EmailWorker:
  val layer: URLayer[EmailGateway, EmailWorker] =
    ZLayer.scoped:
      for
        gateway <- ZIO.service[EmailGateway]
        queue   <- Queue.bounded[EmailRequest](1024)
        send     = (r: EmailRequest) =>
                     gateway.send(r).catchAllCause(c => ZIO.logErrorCause("Email failed", c))
        _       <- ZIO.addFinalizer(queue.takeAll.flatMap(ZIO.foreachDiscard(_)(send)))
        _       <- queue.take.flatMap(send).forever.forkScoped
      yield EmailWorker(queue)
```

Finalizers run in reverse registration order: on shutdown the scope first
interrupts the consumer, then the drain finalizer sends what is left.
`catchAllCause` around each item keeps one bad item from killing the worker.

Use a `Hub` (`Hub.bounded`) when every subscriber must see every message
(in-process domain events). Use a `Promise` when one fiber waits for a single
result or signal from another.

## Streams

`ZStream[R, E, A]` is a lazy, back-pressured, resource-safe sequence of
effects. Use it whenever data is unbounded or too large to hold in memory —
query results, files, message topics, paginated APIs.

> **Important:** never materialize unbounded data (`runCollect`, `ZIO.foreach`
> over a full result set, `List` of all rows). Stream it, or page it at the
> repository port.

```scala
ZStream.fromIterable(orderIds)
  .mapZIOPar(8)(orderService.getOrder)          // bounded parallelism, ordered
  .grouped(100)                                 // batches of 100
  .mapZIO(batch => reportSink.write(batch))
  .runDrain
```

| Need | Operator |
|------|----------|
| parallel effectful map | `mapZIOPar(n)` (ordered), `mapZIOParUnordered(n)` |
| combine sources | `merge`, `mergeAll(n)` |
| thread state through elements | `mapAccum`, `mapAccumZIO` |
| batch by size or time | `grouped(n)`, `groupedWithin(n, d)` |
| periodic ticks | `ZStream.tick(d)`, `ZStream.fromSchedule` |
| resource for the stream's life | `ZStream.scoped`, `ZStream.acquireReleaseWith` |
| pull pages from an API | `ZStream.paginateZIO` |

`mapAccum` replaces a mutable accumulator:

```scala
events.mapAccum(Cart.empty)((cart, event) =>
  val next = cart.applyEvent(event)
  (next, next.total)
)
```

## Blocking and CPU-bound work

- Blocking I/O: `ZIO.attemptBlocking` (interrupt-aware variant:
  `ZIO.attemptBlockingInterrupt`). It runs on ZIO's blocking pool so it can't
  starve the default executor.
- CPU-bound work: the runtime yields between effect steps, but a single long
  `ZIO.succeed(heavyComputation)` holds its thread until done and can't be
  interrupted. Split long computations into chunks with `ZIO.yieldNow` between
  them, or move them off the default executor with `.onExecutor(cpuExecutor)`.

## Interruption

- Interruption is how cancellation, timeouts, `race` and shutdown work. Code
  is interruptible by default; keep it that way.
- Mark only tiny critical sections `.uninterruptible`, never I/O waits or loops.
- Release logic goes in `acquireRelease` / `ensuring` / `addFinalizer`, which
  run on interruption too.
