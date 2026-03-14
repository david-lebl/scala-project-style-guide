# Design Decisions & Rationale

This document explains the key design decisions in the Worker module and how they address the requirements.

## Your Feedback & Our Solutions

### 1. Use Case Pattern for Suitable Cases ✅

**Your Feedback:**
> "I would prefer to use a use case pattern for suitable cases"

**Our Solution:**

We implemented the **Use Case Pattern with CQRS separation**:

```scala
// Commands (WorkerUseCases.scala)
object WorkerUseCases:
  def registerWorker(id: String, config: Worker.Config): ZIO[...] = ...
  def recordHeartbeat(id: Worker.Id): ZIO[...] = ...
  def activatePendingWorkers(): ZIO[...] = ...

// Queries (WorkerQueries.scala)
object WorkerQueries:
  def getWorker(id: Worker.Id): ZIO[...] = ...
  def getActiveWorkers: ZIO[...] = ...
  def getWorkersWithMetadata(status?: Option[Status]): ZIO[...] = ...
```

**Why This Pattern?**

- ✅ **Clear separation** - Commands modify state, queries read state
- ✅ **Minimal dependencies** - Each use case only needs what it uses
- ✅ **Easy composition** - Use cases combine naturally
- ✅ **Perfect for scheduling** - Use cases map directly to scheduled jobs

---

### 2. Scheduled Checking Instead of Reactive ✅

**Your Feedback:**
> "There is missing logic for marking stale workers (preferably scheduled checking), and reactivating worker on heartbeat is not optimal, and better to be scheduled as periodical check"

**Our Solution:**

Implemented `WorkerScheduler` with **3 independent background jobs**:

```scala
object WorkerScheduler:
  case class Config(
    activationCheckInterval: Duration = Duration.ofSeconds(10),
    staleCheckInterval: Duration = Duration.ofSeconds(30),
    reactivationCheckInterval: Duration = Duration.ofSeconds(30),
    heartbeatTimeout: Duration = Duration.ofMinutes(2)
  )

  def startScheduledJobs(config: Config): ZIO[...] =
    ZIO.collectAllParDiscard(List(
      activationJob(config).forkDaemon,
      staleWorkerDetectionJob(config).forkDaemon,
      reactivationJob(config).forkDaemon
    ))
```

**Job Details:**

1. **Activation Job** (every 10s)
   - Finds workers in `Pending` status
   - Checks if they have heartbeats
   - Transitions `Pending → Active`

2. **Stale Detection Job** (every 30s)
   - Finds workers with stale heartbeats (> timeout)
   - Transitions `Active → Offline`

3. **Reactivation Job** (every 30s)
   - Finds workers in `Offline` status
   - Checks if they have recent heartbeats
   - Transitions `Offline → Active`

**Heartbeat is Pure Recording:**

```scala
def recordHeartbeat(id: Worker.Id): ZIO[...] =
  for
    _ <- WorkerRepository.findById(id).someOrFail(WorkerNotFound(id))
    now <- Clock.instant
    _ <- HeartbeatStore.record(id, now)
    // NO state transitions here!
  yield ()
```

**Benefits:**

- ✅ **Predictable** - State changes happen at known intervals
- ✅ **Efficient** - Batch processing vs. checking on every heartbeat
- ✅ **Decoupled** - Heartbeat recording doesn't trigger side effects
- ✅ **Scalable** - Single scheduler handles all workers
- ✅ **Testable** - Can trigger scheduler manually for testing

---

### 3. Package-Private Repositories ✅

**Your Feedback:**
> "Repositories should be package private and not accessible to the user"

**Our Solution:**

Repositories are in `internal/` package with `private[worker]` visibility:

```scala
// internal/WorkerRepository.scala
package com.mycompany.worker.internal

private[worker] trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]
  // ...

private[worker] object WorkerRepository:
  // Accessor methods for ZIO environment
  def save(worker: Worker): ZIO[WorkerRepository, WorkerError, Unit] =
    ZIO.serviceWithZIO[WorkerRepository](_.save(worker))
```

**Users Interact Via Use Cases:**

```scala
// Public API - users call this
WorkerUseCases.registerWorker("worker-1", config)

// Internal - users never see WorkerRepository
// Use cases access repository via ZIO environment
def registerWorker(id: String, config: Worker.Config):
  ZIO[WorkerRepository, WorkerError, Worker] =
    for
      _ <- WorkerRepository.exists(workerId) // Internal accessor
      // ...
    yield worker
```

**Benefits:**

- ✅ **Encapsulation** - Repository is implementation detail
- ✅ **Flexibility** - Can change storage without breaking API
- ✅ **Single entry point** - Use cases are the public interface
- ✅ **Clear boundaries** - Domain logic vs. infrastructure

---

### 4. Typed Repository Errors ✅

**Your Feedback:**
> "Cannot repositories directly return the typed enum error?"

**Our Solution:**

All repository operations return `IO[WorkerError, A]`:

```scala
private[worker] trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]  // Not IO[Throwable, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]
  def delete(id: Worker.Id): IO[WorkerError, Unit]
```

**Implementation Maps Errors:**

```scala
// PostgresWorkerRepository.scala
override def save(worker: Worker): IO[WorkerError, Unit] =
  ZIO.attemptBlocking {
    // PostgreSQL operations
  }.mapError(e =>
    WorkerError.RepositoryError("save", e.getMessage)
  )

// InMemoryWorkerRepository.scala
override def delete(id: Worker.Id): IO[WorkerError, Unit] =
  storeRef.get.flatMap { store =>
    if store.contains(id) then
      storeRef.update(_ - id)
    else
      ZIO.fail(WorkerError.WorkerNotFound(id))  // Domain error directly
  }
```

**Benefits:**

- ✅ **Type safety** - Errors are part of the type signature
- ✅ **Composability** - All operations use same error type
- ✅ **No error mapping** - Use cases don't need to convert errors
- ✅ **Clear contracts** - Know exactly what errors can occur

**Error Flow:**

```
Repository Error (Throwable)
    ↓ mapError
Domain Error (WorkerError)
    ↓ propagates
Use Case returns IO[WorkerError, A]
    ↓ propagates
HTTP routes handle WorkerError
    ↓ maps to
HTTP Status Code
```

---

## Additional Design Decisions

### Why CQRS (Commands vs Queries)?

```scala
// Commands - modify state
object WorkerUseCases:
  def registerWorker(...): ZIO[...] = ...
  def recordHeartbeat(...): ZIO[...] = ...

// Queries - read state
object WorkerQueries:
  def getWorker(...): ZIO[...] = ...
  def getActiveWorkers: ZIO[...] = ...
```

**Benefits:**

- ✅ **Clarity** - Obvious which operations modify vs. read
- ✅ **Optimization** - Can optimize reads separately
- ✅ **Scalability** - Can scale reads independently
- ✅ **Testability** - Test commands and queries separately

---

### Why Separate Heartbeat Store?

```scala
trait WorkerRepository       // Worker entities
trait HeartbeatStore        // Heartbeat history
```

**Benefits:**

- ✅ **Separation of concerns** - Different data, different access patterns
- ✅ **Independent scaling** - Heartbeats write-heavy, workers read-heavy
- ✅ **Different storage** - Could use time-series DB for heartbeats
- ✅ **Clear boundaries** - Worker lifecycle vs. heartbeat tracking

---

### Why Opaque Types?

```scala
object Worker:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id
```

**Benefits:**

- ✅ **Type safety** - Can't accidentally pass wrong string
- ✅ **Zero runtime cost** - Compiles to plain String
- ✅ **IDE support** - Better autocomplete and navigation
- ✅ **Refactoring safety** - Type errors if used incorrectly

---

### Why Domain-Wide Errors?

```scala
sealed trait WorkerError extends NoStackTrace:
  def message: String

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError
  case class InvalidConfiguration(field: String, reason: String) extends WorkerError
  // ... all errors for worker domain
```

**Benefits:**

- ✅ **Single error type** - Easy to compose with flatMap, zipPar
- ✅ **No error mapping** - All operations use same error type
- ✅ **Descriptive messages** - Each error has clear message
- ✅ **Efficient** - NoStackTrace avoids stack trace overhead

---

## Comparison: Before vs After

### Before (Initial Design)

```scala
// Service Pattern
trait WorkerService:
  def register(...): IO[WorkerError, Worker]
  def heartbeat(...): IO[WorkerError, Unit]  // Auto-activates!
  def getWorker(...): IO[WorkerError, Worker]

// Repositories public
trait WorkerRepository:  // Public!
  def save(...): IO[Throwable, Unit]  // Generic error!

// No scheduler
// Reactive state transitions in heartbeat
```

**Issues:**

- ❌ All operations in one service (less focused)
- ❌ Repositories exposed to users
- ❌ Generic errors (Throwable) from repositories
- ❌ Reactive state transitions (inefficient)
- ❌ No scheduled jobs

---

### After (Current Design)

```scala
// Use Case Pattern with CQRS
object WorkerUseCases:  // Commands
  def registerWorker(...): ZIO[WorkerRepository, WorkerError, Worker]
  def recordHeartbeat(...): ZIO[...] = ...  // Pure recording!

object WorkerQueries:   // Queries
  def getWorker(...): ZIO[WorkerRepository, WorkerError, Worker]

// Repositories package-private
private[worker] trait WorkerRepository:
  def save(...): IO[WorkerError, Unit]  // Typed error!

// Scheduler
object WorkerScheduler:
  def activationJob: ZIO[...]        // Pending → Active
  def staleDetectionJob: ZIO[...]    // Active → Offline
  def reactivationJob: ZIO[...]      // Offline → Active
```

**Improvements:**

- ✅ CQRS separation (commands vs queries)
- ✅ Package-private repositories (encapsulation)
- ✅ Typed errors throughout (no Throwable)
- ✅ Scheduled state transitions (efficient)
- ✅ Background jobs (scalable)

---

## Testing Strategy

### Unit Tests (Use Cases)

```scala
// Test individual use cases in isolation
val testRegister = for
  worker <- WorkerUseCases.registerWorker("test-1", config)
  retrieved <- WorkerQueries.getWorker(worker.id)
yield assert(retrieved == worker)

testRegister.provide(InMemoryWorkerRepository.layer)
```

### Integration Tests (Scheduler)

```scala
// Test scheduler with short intervals
val testScheduler = for
  worker <- WorkerUseCases.registerWorker("test-1", config)
  _ <- WorkerUseCases.recordHeartbeat(worker.id)
  _ <- ZIO.sleep(1.second)
  result <- WorkerScheduler.runAllChecks(
    WorkerScheduler.Config(
      activationCheckInterval = Duration.ofSeconds(1),
      heartbeatTimeout = Duration.ofSeconds(2)
    )
  )
yield assert(result.activated.contains(worker.id))
```

### HTTP Tests (End-to-End)

```scala
// Test full HTTP flow
val testHTTP = for
  _ <- Client.request(
    Request.post(
      URL.root / "workers",
      Body.fromString("""{"id":"test-1","region":"us-east-1","capacity":10}""")
    )
  )
  _ <- Client.request(
    Request.post(URL.root / "workers" / "test-1" / "heartbeat")
  )
  response <- Client.request(
    Request.get(URL.root / "workers" / "active")
  )
yield assert(response.status == Status.Ok)
```

---

## Summary

This design addresses all your feedback while following the style guide:

| Requirement | Solution | Benefit |
|------------|----------|---------|
| Use Case Pattern | ✅ CQRS with commands & queries | Clear separation, easy composition |
| Scheduled Jobs | ✅ 3 background jobs (activation, stale, reactivation) | Predictable, efficient, scalable |
| Package-Private Repos | ✅ `private[worker]` in `internal/` | Encapsulation, flexibility |
| Typed Errors | ✅ `IO[WorkerError, A]` everywhere | Type safety, composability |
| Domain-Wide Errors | ✅ Single `WorkerError` type | Easy composition, clear messages |
| Heartbeat Purity | ✅ No state changes in heartbeat | Decoupled, efficient |
| Opaque Types | ✅ `Worker.Id` opaque type | Type safety, zero cost |
| CQRS | ✅ Commands vs Queries | Clear boundaries, optimization |

The result is a **type-safe, scalable, maintainable** worker management system that follows all style guide patterns! 🎉
