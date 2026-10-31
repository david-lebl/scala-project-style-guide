# Resources and Lifecycle

Acquiring and releasing resources safely with `Scope`, wiring application-wide
resources as scoped layers, the application entry point, and long-running
background processes. Read this before opening a connection pool, file, client,
or starting anything that runs for the life of the application.

## Dependencies

- `"dev.zio" %% "zio"` — `Scope`, `ZIO.acquireRelease`, `ZLayer.scoped`,
  `Schedule`, `ZIOAppDefault`

---

## Acquire and release with `Scope`

`ZIO.acquireRelease(acquire)(release)` pairs an acquisition with its release
and adds a `Scope` requirement to the effect. The release runs exactly once
when the scope closes, whether the work succeeds, fails, or is interrupted.
Resources are released in reverse acquisition order.

```scala
def openReader(path: Path): ZIO[Scope, IOException, BufferedReader] =
  ZIO.acquireRelease(
    ZIO.attemptBlocking(Files.newBufferedReader(path)).refineToOrDie[IOException]
  )(reader => ZIO.succeedBlocking(reader.close()))
```

For `AutoCloseable` values use the shorthand `ZIO.fromAutoCloseable(acquire)`.

> **Important:** the release action must not fail — its type is `URIO`. Handle
> or `.orDie` close errors inside it. Acquisition is uninterruptible, so keep it
> short.

## Local resources: `ZIO.scoped`

When a resource is needed only for one operation, close the scope inside that
operation. `ZIO.scoped` removes the `Scope` requirement and releases everything
acquired inside when the block ends:

```scala
def countLines(path: Path): IO[IOException, Long] =
  ZIO.scoped:
    openReader(path).flatMap(reader => ZIO.attemptBlocking(reader.lines().count()).refineToOrDie[IOException])
```

Don't leak `Scope` into a signature unless the caller is meant to decide the
resource's lifetime. A service method never returns `ZIO[Scope, ...]`.

## Application resources: `ZLayer.scoped`

Resources that live as long as the application (connection pools, HTTP
clients, Kafka producers) are scoped layers. The layer's scope is the
application's scope; release happens on shutdown, in reverse order.

```scala
object DataSourceLayer:
  val live: RLayer[PostgresConfig, DataSource] =
    ZLayer.scoped:
      for
        config <- ZIO.service[PostgresConfig]
        pool   <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(HikariDataSource(hikariConfig(config))))
      yield pool
```

Adapters then depend on `DataSource` through their own layer (see [Dependency
Injection](330-dependency-injection.md)); they never open or close the pool
themselves.

## Application entry point

`ZIOAppDefault` is the root scope: it runs `run`, and on SIGTERM interrupts it,
which closes every scoped layer in reverse order.

```scala
object Main extends ZIOAppDefault:
  override val run: ZIO[Any, Any, Any] =
    (startBackgroundJobs *> Server.serve(allRoutes))
      .provide(
        ServerConfig.layer,
        DataSourceLayer.live,
        PostgresOrderRepository.layer,
        OrderServiceLive.layer,
        Server.live
      )
```

`Server.serve` never returns; for a worker without a server, end the program
with `ZIO.never` so the scope (and its daemons) stays open until shutdown.

## Background processes

A periodic job is an effect repeated on a `Schedule`, forked into a scope so it
is interrupted when the scope closes:

```scala
private[ordering] final case class OutboxPublisher(outbox: OutboxRepository, bus: EventBus):

  val publishPending: UIO[Unit] =
    outbox.pending.flatMap(events => ZIO.foreachDiscard(events)(bus.publish))

  val runForever: UIO[Unit] =
    publishPending
      .catchAllCause(cause => ZIO.logErrorCause("Outbox publishing failed", cause))
      .repeat(Schedule.spaced(5.seconds))
      .unit

private[ordering] object OutboxPublisher:
  val layer: URLayer[OutboxRepository & EventBus, OutboxPublisher] =
    ZLayer.scoped:
      for
        outbox    <- ZIO.service[OutboxRepository]
        bus       <- ZIO.service[EventBus]
        publisher  = OutboxPublisher(outbox, bus)
        _         <- publisher.runForever.forkScoped
      yield publisher
```

- `.repeat(Schedule.spaced(d))` waits `d` between runs; `Schedule.fixed(d)`
  keeps a fixed rate.
- `.forkScoped` ties the fiber to the layer's scope: shutdown interrupts it.
- `catchAllCause` **per iteration** logs and continues; without it, one failed
  iteration ends the loop for good.

> **Warning:** never use `.forkDaemon` for application work. A daemon fiber
> belongs to no scope, survives its parent, and is not interrupted at shutdown.
> Use `.forkScoped` (or a parent that joins the fiber).

> **Warning:** an interrupted fiber can't recover from its own interruption, so
> `catchAllCause` doesn't keep the loop alive at shutdown — that is what makes
> graceful shutdown work. Never wrap the loop in `.uninterruptible`.

## Graceful shutdown

Shutdown is interruption. Write effects so that interruption is safe:

- Hold resources only through `acquireRelease`/scoped layers, never with
  hand-written `try/finally` or `ensuring` ladders.
- Use `ZIO.uninterruptible` / `.uninterruptible` only around a few
  instructions that must complete together (e.g. "write + mark as sent"), never
  around a loop or I/O wait.
- For workers that must drain a queue before exit, register the drain as a
  finalizer of the worker's layer (see [Concurrency and
  Streaming](230-concurrency-and-streaming.md#queues-and-hubs)).
