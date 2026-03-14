# Worker Module Architecture

Visual guide to the Worker module architecture and design patterns.

## Module Dependency Graph

```
┌──────────────────────────────────────────────────────────────────┐
│                         04-app                                    │
│  ┌────────────────┐           ┌─────────────────┐               │
│  │  WorkerApp     │           │  WorkerDemo     │               │
│  │  (HTTP Server) │           │  (CLI Demo)     │               │
│  └────────────────┘           └─────────────────┘               │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             │ depends on
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                         03-impl                                   │
│  ┌──────────────────────────────────────────────────────────────┤
│  │  WorkerLayers (Pre-wired bundles)                             │
│  │  - production: PostgreSQL layers                              │
│  │  - inMemory: In-memory layers                                 │
│  │  - httpRoutes: HTTP routes                                    │
│  └──────────────────────────────────────────────────────────────┤
└────┬─────────────────────┬───────────────────────┬───────────────┘
     │                     │                       │
     │ depends on          │ depends on            │ depends on
     ▼                     ▼                       ▼
┌──────────┐      ┌─────────────────┐     ┌──────────────────────┐
│  02-db   │      │ 02-http-server  │     │      01-core         │
│──────────┤      │─────────────────┤     │──────────────────────┤
│ Postgres │      │ WorkerRoutes    │     │ Domain Model         │
│ Impls    │      │ WorkerDTOs      │     │ Use Cases (Commands) │
│          │      │                 │     │ Queries (Reads)      │
│          │      │                 │     │ Scheduler (Jobs)     │
│          │      │                 │     │ Errors               │
│          │      │                 │     │ Internal/            │
│          │      │                 │     │   - Repositories     │
│          │      │                 │     │   - In-Memory Impls  │
└──────────┘      └─────────────────┘     └──────────────────────┘
     │                     │                       ▲
     │                     │                       │
     └─────────────────────┴───────────────────────┘
              all depend on 01-core
```

## Layer Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Layer (04-app)                │
│  HTTP Server (WorkerApp) | CLI Demo (WorkerDemo)                │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ uses
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Composition Layer (03-impl)                  │
│  Pre-wired Bundles: production | inMemory | httpRoutes          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ wires
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Infrastructure Layer (02-*)                   │
│  ┌─────────────┐         ┌──────────────┐                       │
│  │   02-db     │         │ 02-http      │                       │
│  │  Postgres   │         │  REST API    │                       │
│  │  Repository │         │  DTOs        │                       │
│  │  Heartbeat  │         │  Routes      │                       │
│  └─────────────┘         └──────────────┘                       │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ implements
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Domain Layer (01-core)                     │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Public API                                                 │  │
│  │  - Worker (domain model)                                   │  │
│  │  - WorkerError (typed errors)                              │  │
│  │  - WorkerUseCases (commands)                               │  │
│  │  - WorkerQueries (reads)                                   │  │
│  │  - WorkerScheduler (background jobs)                       │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Internal (package-private)                                 │  │
│  │  - WorkerRepository (interface)                            │  │
│  │  - HeartbeatStore (interface)                              │  │
│  │  - InMemoryWorkerRepository (impl)                         │  │
│  │  - InMemoryHeartbeatStore (impl)                           │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Use Case Flow: Register Worker

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ HTTP POST /workers
       │ {id, region, capacity}
       ▼
┌─────────────────────────┐
│   WorkerRoutes          │ (02-http-server)
│   - Parse DTO           │
│   - Convert to Config   │
└──────┬──────────────────┘
       │ WorkerUseCases.registerWorker
       ▼
┌─────────────────────────┐
│   WorkerUseCases        │ (01-core)
│   - Validate not exists │───┐
│   - Validate config     │   │
│   - Create worker       │   │ WorkerRepository
│   - Save worker         │◄──┘ operations
└──────┬──────────────────┘
       │ IO[WorkerError, Worker]
       ▼
┌─────────────────────────┐
│   WorkerRepository      │ (internal)
│   - PostgreSQL or       │
│   - In-Memory impl      │
└──────┬──────────────────┘
       │ Success/Error
       ▼
┌─────────────────────────┐
│   Response              │
│   {worker: {...}}       │
└─────────────────────────┘
```

## Use Case Flow: Heartbeat → Activation

```
Time: 0s                                 Time: 10s
┌────────────────┐                      ┌──────────────────┐
│    Client      │                      │   Scheduler      │
└────────┬───────┘                      │  (Background)    │
         │                              └────────┬─────────┘
         │ POST /workers/{id}/heartbeat         │
         ▼                                       │
┌──────────────────────┐                        │
│  WorkerUseCases      │                        │
│  .recordHeartbeat()  │                        │
│  - Verify exists     │                        │
│  - Record timestamp  │                        │
│  - NO state change!  │                        │
└──────────────────────┘                        │
         │                                       │
         │ Success                               │
         ▼                                       │
┌────────────────┐                              │
│  200 OK        │                              │
└────────────────┘                              │
                                                 │
         Scheduler runs every 10s ──────────────┤
                                                 ▼
                                      ┌─────────────────────────┐
                                      │  WorkerScheduler        │
                                      │  .activationJob()       │
                                      │  - Get Pending workers  │
                                      │  - Check heartbeats     │
                                      │  - Activate if exists   │
                                      └─────────┬───────────────┘
                                                │
                                                │ WorkerUseCases
                                                │ .activatePendingWorkers()
                                                ▼
                                      ┌─────────────────────────┐
                                      │  Worker Status          │
                                      │  Pending → Active       │
                                      └─────────────────────────┘
```

## CQRS Separation

```
┌─────────────────────────────────────────────────────────────────┐
│                      WorkerUseCases (Commands)                   │
│  Modify state - return IO[WorkerError, Result]                  │
├─────────────────────────────────────────────────────────────────┤
│  registerWorker(id, config)        → IO[WorkerError, Worker]    │
│  unregisterWorker(id)              → IO[WorkerError, Unit]      │
│  recordHeartbeat(id)               → IO[WorkerError, Unit]      │
│  activatePendingWorkers()          → IO[WorkerError, List[Id]]  │
│  markStaleWorkersOffline(timeout)  → IO[WorkerError, List[Id]]  │
│  reactivateOfflineWorkers(timeout) → IO[WorkerError, List[Id]]  │
│  updateWorkerStatus(...)           → IO[WorkerError, Worker]    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      WorkerQueries (Queries)                     │
│  Read state - return IO[WorkerError, Result]                    │
├─────────────────────────────────────────────────────────────────┤
│  getWorker(id)                     → IO[WorkerError, Worker]    │
│  getActiveWorkers                  → IO[WorkerError, List[...]] │
│  getWorkersByStatus(status)        → IO[WorkerError, List[...]] │
│  getAllWorkers                     → IO[WorkerError, List[...]] │
│  getLastHeartbeat(id)              → IO[WorkerError, Instant]   │
│  getHeartbeatHistory(id, limit)    → IO[WorkerError, List[...]] │
│  getWorkersWithMetadata(status?)   → IO[WorkerError, List[...]] │
└─────────────────────────────────────────────────────────────────┘
```

## Scheduler Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      WorkerScheduler.startScheduledJobs()        │
│  Starts 3 background fibers that run indefinitely                │
└──┬────────────────────┬─────────────────────┬────────────────────┘
   │                    │                     │
   │ Fork daemon        │ Fork daemon         │ Fork daemon
   ▼                    ▼                     ▼
┌──────────────┐  ┌──────────────────┐  ┌────────────────────┐
│ Activation   │  │ Stale Detection  │  │  Reactivation      │
│ Job          │  │ Job              │  │  Job               │
│              │  │                  │  │                    │
│ Every 10s    │  │ Every 30s        │  │  Every 30s         │
│              │  │                  │  │                    │
│ Pending →    │  │ Active →         │  │  Offline →         │
│   Active     │  │   Offline        │  │    Active          │
│              │  │                  │  │                    │
│ (if has      │  │ (if no heartbeat │  │  (if recent        │
│  heartbeat)  │  │  within timeout) │  │   heartbeat)       │
└──────────────┘  └──────────────────┘  └────────────────────┘
```

## Error Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        Domain Layer (01-core)                    │
│  All operations return: IO[WorkerError, A]                      │
├─────────────────────────────────────────────────────────────────┤
│  sealed trait WorkerError extends NoStackTrace                  │
│    - WorkerAlreadyExists                                         │
│    - WorkerNotFound                                              │
│    - InvalidConfiguration                                        │
│    - InvalidStateTransition                                      │
│    - HeartbeatFailed                                             │
│    - RepositoryError(operation, detail)                          │
│    - StorageError(operation, detail)                             │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ Used by all layers
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Infrastructure Layer (02-*)                   │
│  Maps technical errors → WorkerError                            │
├─────────────────────────────────────────────────────────────────┤
│  PostgresWorkerRepository:                                       │
│    ZIO.attemptBlocking { /* SQL */ }                            │
│      .mapError(e => WorkerError.RepositoryError("save", e))    │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ Propagates domain errors
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    HTTP Layer (02-http-server)                   │
│  Maps WorkerError → HTTP Status                                 │
├─────────────────────────────────────────────────────────────────┤
│  WorkerRoutes:                                                   │
│    - WorkerNotFound        → 404 Not Found                      │
│    - WorkerAlreadyExists   → 409 Conflict                       │
│    - InvalidConfiguration  → 400 Bad Request                    │
│    - InvalidStateTransition → 400 Bad Request                   │
│    - RepositoryError       → 500 Internal Server Error          │
└─────────────────────────────────────────────────────────────────┘
```

## Key Design Principles

### 1. Package-Private Repositories
```scala
// Internal - not accessible outside worker package
private[worker] trait WorkerRepository

// Public - accessible to users
object WorkerUseCases:
  def registerWorker(...): ZIO[WorkerRepository, WorkerError, Worker]
```

### 2. Typed Errors Throughout
```scala
// Repository returns WorkerError, not Throwable
trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]

// Implementation maps errors
def save(worker: Worker): IO[WorkerError, Unit] =
  ZIO.attemptBlocking { /* DB */ }
    .mapError(e => WorkerError.RepositoryError("save", e.getMessage))
```

### 3. Scheduled State Transitions
```scala
// Heartbeat does NOT change state
def recordHeartbeat(id: Worker.Id): ZIO[...] =
  for
    _ <- verifyExists(id)
    now <- Clock.instant
    _ <- HeartbeatStore.record(id, now)
    // No state transition!
  yield ()

// Scheduler handles state transitions
def activationJob: ZIO[...] =
  WorkerUseCases.activatePendingWorkers()
    .schedule(Schedule.fixed(10.seconds))
```

### 4. Use Case Composition
```scala
// Easily compose use cases
val workflow = for
  worker <- WorkerUseCases.registerWorker(id, config)
  _ <- WorkerUseCases.recordHeartbeat(worker.id)
  _ <- WorkerScheduler.runAllChecks(config)
  active <- WorkerQueries.getActiveWorkers
yield active
```

## Benefits of This Architecture

✅ **Separation of Concerns** - Commands, queries, and jobs are independent
✅ **Encapsulation** - Repositories hidden, use cases are public API
✅ **Testability** - Each use case testable in isolation
✅ **Composability** - Use cases easily combined for workflows
✅ **Type Safety** - Typed errors, opaque types, enums throughout
✅ **Scalability** - Scheduled jobs more efficient than reactive
✅ **Flexibility** - Easy to swap implementations (in-memory, PostgreSQL)
✅ **Predictability** - State transitions happen at known intervals
