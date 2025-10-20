# Self-Contained Services vs Domain-Driven Services

A comparison of two architectural approaches: everything-in-one-file vs structured domain organization.

---

## Table of Contents

1. [Overview](#overview)
2. [Self-Contained Service Pattern](#self-contained-service-pattern)
3. [Domain-Driven Pattern](#domain-driven-pattern)
4. [Comparison](#comparison)
5. [When to Use Each](#when-to-use-each)
6. [Migration Path](#migration-path)
7. [Hybrid Approaches](#hybrid-approaches)

---

## Overview

### Two Approaches:

**Self-Contained Service (Single File)**:
```scala
// Everything in one object/file
object CacheService:
  // Models
  case class CacheEntry(...)

  // Errors
  sealed trait Error

  // Interface
  trait Service

  // Implementation
  case class Live(...) extends Service

  // Repository
  private trait Store
  private case class InMemoryStore(...) extends Store
```

**Domain-Driven (Package Structure)**:
```
worker/
  ├── Worker.scala           (model)
  ├── WorkerError.scala      (errors)
  ├── WorkerService.scala    (interface)
  ├── WorkerRepository.scala (repository interface)
  └── internal/
      └── WorkerServiceLive.scala
```

---

## Self-Contained Service Pattern

### Structure: Everything in One File/Object

```scala
package com.mycompany.util

import zio.*
import scala.util.control.NoStackTrace

/** Self-contained cache service.
  *
  * All components (models, errors, interface, implementation, storage)
  * are contained within this single object.
  */
object CacheService:

  // ============================================================================
  // Public API - Models
  // ============================================================================

  case class CacheKey(value: String) extends AnyVal

  case class CacheEntry[A](
    key: CacheKey,
    value: A,
    expiresAt: Option[Instant]
  )

  // ============================================================================
  // Public API - Errors
  // ============================================================================

  sealed trait Error extends NoStackTrace:
    def message: String
    override def getMessage: String = message

  object Error:
    case class KeyNotFound(key: CacheKey) extends Error:
      val message = s"Cache key '${key.value}' not found"

    case class Expired(key: CacheKey, expiredAt: Instant) extends Error:
      val message = s"Cache entry for '${key.value}' expired at $expiredAt"

    case class StorageError(operation: String, cause: Throwable) extends Error:
      val message = s"Cache storage error during '$operation': ${cause.getMessage}"
      override def getCause: Throwable = cause

  // ============================================================================
  // Public API - Service Interface
  // ============================================================================

  trait Service:
    def get[A](key: CacheKey): IO[Error, A]
    def put[A](key: CacheKey, value: A, ttl: Option[Duration] = None): IO[Error, Unit]
    def remove(key: CacheKey): IO[Error, Unit]
    def clear(): IO[Error, Unit]
    def contains(key: CacheKey): IO[Error, Boolean]
    def size: IO[Error, Int]

  // ============================================================================
  // Private - Storage Interface
  // ============================================================================

  private trait Store:
    def get[A](key: CacheKey): UIO[Option[CacheEntry[A]]]
    def put[A](key: CacheKey, value: A, expiresAt: Option[Instant]): UIO[Unit]
    def remove(key: CacheKey): UIO[Unit]
    def clear(): UIO[Unit]
    def keys: UIO[Set[CacheKey]]

  // ============================================================================
  // Private - In-Memory Store Implementation
  // ============================================================================

  private case class InMemoryStore(
    ref: Ref[Map[CacheKey, CacheEntry[Any]]]
  ) extends Store:

    override def get[A](key: CacheKey): UIO[Option[CacheEntry[A]]] =
      ref.get.map(_.get(key).map(_.asInstanceOf[CacheEntry[A]]))

    override def put[A](key: CacheKey, value: A, expiresAt: Option[Instant]): UIO[Unit] =
      val entry = CacheEntry(key, value, expiresAt)
      ref.update(_.updated(key, entry))

    override def remove(key: CacheKey): UIO[Unit] =
      ref.update(_ - key)

    override def clear(): UIO[Unit] =
      ref.set(Map.empty)

    override def keys: UIO[Set[CacheKey]] =
      ref.get.map(_.keySet)

  // ============================================================================
  // Private - Service Implementation
  // ============================================================================

  private case class Live(store: Store, clock: Clock) extends Service:

    override def get[A](key: CacheKey): IO[Error, A] =
      for
        maybeEntry <- store.get[A](key)
        entry <- ZIO.fromOption(maybeEntry).orElseFail(Error.KeyNotFound(key))
        now <- clock.instant
        value <- entry.expiresAt match
          case Some(expiry) if now.isAfter(expiry) =>
            store.remove(key) *>
              ZIO.fail(Error.Expired(key, expiry))
          case _ =>
            ZIO.succeed(entry.value)
      yield value

    override def put[A](key: CacheKey, value: A, ttl: Option[Duration]): IO[Error, Unit] =
      for
        now <- clock.instant
        expiresAt = ttl.map(d => now.plusMillis(d.toMillis))
        _ <- store.put(key, value, expiresAt)
      yield ()

    override def remove(key: CacheKey): IO[Error, Unit] =
      store.remove(key).mapError(e => Error.StorageError("remove", e))

    override def clear(): IO[Error, Unit] =
      store.clear().mapError(e => Error.StorageError("clear", e))

    override def contains(key: CacheKey): IO[Error, Boolean] =
      store.get(key).map(_.nonEmpty)

    override def size: IO[Error, Int] =
      store.keys.map(_.size)

  // ============================================================================
  // Public API - Layer Constructors
  // ============================================================================

  /** In-memory cache layer */
  val inMemory: ZLayer[Any, Nothing, Service] =
    ZLayer.fromZIO(
      for
        ref <- Ref.make(Map.empty[CacheKey, CacheEntry[Any]])
        store = InMemoryStore(ref)
        clock <- ZIO.clock
      yield Live(store, clock)
    )

  // ============================================================================
  // Public API - ZIO Accessor Methods
  // ============================================================================

  def get[A](key: CacheKey): ZIO[Service, Error, A] =
    ZIO.serviceWithZIO[Service](_.get(key))

  def put[A](key: CacheKey, value: A, ttl: Option[Duration] = None): ZIO[Service, Error, Unit] =
    ZIO.serviceWithZIO[Service](_.put(key, value, ttl))

  def remove(key: CacheKey): ZIO[Service, Error, Unit] =
    ZIO.serviceWithZIO[Service](_.remove(key))

  def clear(): ZIO[Service, Error, Unit] =
    ZIO.serviceWithZIO[Service](_.clear())

  def contains(key: CacheKey): ZIO[Service, Error, Boolean] =
    ZIO.serviceWithZIO[Service](_.contains(key))

  def size: ZIO[Service, Error, Int] =
    ZIO.serviceWithZIO[Service](_.size)

end CacheService

// ============================================================================
// Usage Example
// ============================================================================

object CacheServiceExample extends ZIOAppDefault:

  val program: ZIO[CacheService.Service, CacheService.Error, Unit] =
    for
      _ <- CacheService.put(CacheService.CacheKey("user:1"), "John Doe", Some(Duration.fromMinutes(5)))
      name <- CacheService.get[String](CacheService.CacheKey("user:1"))
      _ <- Console.printLine(s"Cached name: $name").orDie

      size <- CacheService.size
      _ <- Console.printLine(s"Cache size: $size").orDie

      _ <- CacheService.clear()
    yield ()

  override def run =
    program.provide(CacheService.inMemory)
      .catchAll(error => Console.printLineError(s"Error: ${error.message}").orDie)
```

### Characteristics:

✅ **Pros**:
- **Self-contained**: Everything in one place
- **Easy to find**: No hunting through files
- **Simple**: No complex package structure
- **Copy-paste friendly**: Can extract entire object
- **Good for utilities**: Perfect for helper services
- **Less boilerplate**: No separate files for each component

❌ **Cons**:
- **Large files**: Can become unwieldy (500+ lines)
- **Hard to scale**: Difficult to add multiple implementations
- **Tight coupling**: Everything coupled together
- **Testing**: Hard to test individual components
- **Collaboration**: Merge conflicts in single file
- **No infrastructure flexibility**: Stuck with in-file implementation

---

## Domain-Driven Pattern

### Structure: Package-Based Organization

```
worker/
├── Worker.scala                    # Domain model
├── WorkerError.scala              # Domain errors
├── WorkerService.scala            # Service interface
├── WorkerRepository.scala         # Repository interface
├── WorkerUseCases.scala          # Use cases (optional)
└── internal/
    ├── WorkerServiceLive.scala    # Service implementation
    └── InMemoryWorkerRepository.scala  # Default repo
```

### Example: Worker Domain

**`Worker.scala`** - Domain Model:
```scala
package com.mycompany.worker

import java.time.Instant

case class Worker(
  id: Worker.Id,
  status: Worker.Status,
  config: Map[String, String],
  registeredAt: Instant
)

object Worker:
  opaque type Id <: String = String

  object Id:
    def apply(value: String): Worker.Id = value
    extension (id: Worker.Id)
      def value: String = id

  enum Status:
    case Active, Pending, Offline, Failed

  def create(id: String, config: Map[String, String]): Worker =
    Worker(Id(id), Status.Pending, config, Instant.now())
```

**`WorkerError.scala`** - Domain Errors:
```scala
package com.mycompany.worker

import scala.util.control.NoStackTrace
import java.time.Instant

sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class WorkerAlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker '${id.value}' already exists"

  case class InvalidWorkerState(
    id: Worker.Id,
    currentState: Worker.Status,
    expectedState: Worker.Status
  ) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $currentState, expected $expectedState"

  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause
```

**`WorkerService.scala`** - Service Interface:
```scala
package com.mycompany.worker

import zio.*

trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def unregister(id: Worker.Id): IO[WorkerError, Unit]
  def activate(id: Worker.Id): IO[WorkerError, Worker]
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]
  def getAllWorkers: IO[WorkerError, List[Worker]]

object WorkerService:
  def layer: ZLayer[WorkerRepository, Nothing, WorkerService] =
    ZLayer.fromFunction(internal.WorkerServiceLive.apply)

  // Accessor methods
  def register(id: String, config: Map[String, String]): ZIO[WorkerService, WorkerError, Worker] =
    ZIO.serviceWithZIO[WorkerService](_.register(id, config))

  def getWorker(id: Worker.Id): ZIO[WorkerService, WorkerError, Worker] =
    ZIO.serviceWithZIO[WorkerService](_.getWorker(id))

  // ... other accessors
```

**`WorkerRepository.scala`** - Repository Interface:
```scala
package com.mycompany.worker

import zio.*

trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]
  def delete(id: Worker.Id): IO[WorkerError, Unit]
  def findByStatus(status: Worker.Status): IO[WorkerError, List[Worker]]
  def exists(id: Worker.Id): IO[WorkerError, Boolean]
  def findAll: IO[WorkerError, List[Worker]]
```

**`internal/WorkerServiceLive.scala`** - Implementation:
```scala
package com.mycompany.worker.internal

import com.mycompany.worker.*
import zio.*

private[worker] final case class WorkerServiceLive(
  repository: WorkerRepository
) extends WorkerService:

  override def register(id: String, config: Map[String, String]): IO[WorkerError, Worker] =
    val workerId = Worker.Id(id)
    repository.exists(workerId).flatMap {
      case true => ZIO.fail(WorkerError.WorkerAlreadyExists(workerId))
      case false =>
        val worker = Worker.create(id, config)
        repository.save(worker).as(worker)
    }

  override def unregister(id: Worker.Id): IO[WorkerError, Unit] =
    repository.delete(id)

  override def activate(id: Worker.Id): IO[WorkerError, Worker] =
    for
      worker <- repository.findById(id).someOrFail(WorkerError.WorkerNotFound(id))
      _ <- if worker.status != Worker.Status.Pending then
        ZIO.fail(WorkerError.InvalidWorkerState(id, worker.status, Worker.Status.Pending))
      else
        ZIO.unit
      activated = worker.copy(status = Worker.Status.Active)
      _ <- repository.save(activated)
    yield activated

  override def getWorker(id: Worker.Id): IO[WorkerError, Worker] =
    repository.findById(id).someOrFail(WorkerError.WorkerNotFound(id))

  override def getAllWorkers: IO[WorkerError, List[Worker]] =
    repository.findAll
```

**`internal/InMemoryWorkerRepository.scala`** - Default Implementation:
```scala
package com.mycompany.worker.internal

import com.mycompany.worker.*
import zio.*

object InMemoryWorkerRepository:
  def layer: ZLayer[Any, Nothing, WorkerRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[Worker.Id, Worker]).map(new InMemoryWorkerRepository(_))
    )

private[worker] final class InMemoryWorkerRepository(
  ref: Ref[Map[Worker.Id, Worker]]
) extends WorkerRepository:

  override def save(worker: Worker): IO[WorkerError, Unit] =
    ref.update(_.updated(worker.id, worker))

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    ref.get.map(_.get(id))

  override def delete(id: Worker.Id): IO[WorkerError, Unit] =
    ref.update(_ - id)

  override def findByStatus(status: Worker.Status): IO[WorkerError, List[Worker]] =
    ref.get.map(_.values.filter(_.status == status).toList)

  override def exists(id: Worker.Id): IO[WorkerError, Boolean] =
    ref.get.map(_.contains(id))

  override def findAll: IO[WorkerError, List[Worker]] =
    ref.get.map(_.values.toList)
```

### Characteristics:

✅ **Pros**:
- **Scalable**: Easy to add new services, repositories, implementations
- **Infrastructure flexibility**: Can add DB, HTTP, etc. modules
- **Testable**: Can test components in isolation
- **Collaboration**: Multiple people can work on different files
- **Clear boundaries**: Public API vs internal implementation
- **Refactoring**: Can change internals without breaking public API
- **Multiple implementations**: Easy to add Postgres, Redis, etc.

❌ **Cons**:
- **More files**: More navigation required
- **Boilerplate**: Separate files for each component
- **Complexity**: Requires understanding of package structure
- **Overkill for simple utilities**: Too much structure for simple helpers

---

## Comparison

### Feature Comparison Table

| Aspect | Self-Contained | Domain-Driven |
|--------|----------------|---------------|
| **File count** | 1 file | 5-10+ files |
| **Lines per file** | 200-1000 | 50-200 |
| **Scalability** | ❌ Poor | ✅ Excellent |
| **Infrastructure** | ❌ Hard to add | ✅ Easy (separate modules) |
| **Testing** | ⚠️ Moderate | ✅ Excellent |
| **Collaboration** | ❌ Merge conflicts | ✅ Easy |
| **Learning curve** | ✅ Low | ⚠️ Medium |
| **Copy-paste** | ✅ Easy | ❌ Hard |
| **Refactoring** | ❌ Hard | ✅ Easy |
| **API clarity** | ⚠️ Everything public | ✅ Clear public/private |
| **Best for** | Utilities, helpers | Business domains |

---

### Size Comparison

**Self-Contained Service**:
```
CacheService.scala         (400 lines)
```

**Domain-Driven Service**:
```
Worker.scala               (40 lines)
WorkerError.scala          (50 lines)
WorkerService.scala        (60 lines)
WorkerRepository.scala     (30 lines)
internal/
  WorkerServiceLive.scala  (100 lines)
  InMemoryWorkerRepository.scala (80 lines)
─────────────────────────────────────
Total: 6 files, 360 lines
```

---

## When to Use Each

### Use Self-Contained Service When:

✅ **Utility/Helper Services**:
```scala
object EmailValidator:
  sealed trait Error
  trait Service
  // Simple validation logic
```

✅ **Simple Services** (< 300 lines total):
```scala
object IdGenerator:
  // Just generates IDs
```

✅ **No Infrastructure Dependencies**:
```scala
object JsonParser:
  // Pure logic, no DB/HTTP/etc
```

✅ **Prototyping**:
```scala
object QuickCache:
  // Throwaway code, will refactor later
```

✅ **Single Implementation**:
```scala
object RateLimiter:
  // Only in-memory implementation needed
```

✅ **Shared Across Projects**:
```scala
object ConfigLoader:
  // Copy-paste to other projects
```

**Examples**:
- Cache services
- ID generators
- Validators
- Parsers
- Rate limiters
- Circuit breakers (simple)
- Metrics collectors (simple)

---

### Use Domain-Driven Pattern When:

✅ **Business Domains**:
```
Worker, Order, Customer, Payment, etc.
```

✅ **Multiple Implementations Needed**:
```
worker/
  db/
  http-client/
  kafka/
```

✅ **Infrastructure Dependencies**:
```
worker/
  core/          # Domain
  postgres/      # DB implementation
  rest-api/      # HTTP implementation
```

✅ **Team Collaboration**:
```
# Multiple developers working on same domain
```

✅ **Long-term Projects**:
```
# Will evolve over time
```

✅ **Complex Business Logic** (> 300 lines):
```
# Multiple services, use cases, complex workflows
```

**Examples**:
- User management
- Order processing
- Payment handling
- Inventory management
- Worker orchestration
- File management
- Notification systems

---

## Migration Path

### From Self-Contained to Domain-Driven

**Step 1: Extract Models**
```scala
// Before (in CacheService.scala)
object CacheService:
  case class CacheKey(value: String)
  case class CacheEntry[A](...)

// After
// Cache.scala
package com.mycompany.cache
case class CacheKey(value: String)
case class CacheEntry[A](...)

// CacheService.scala
package com.mycompany.cache
import com.mycompany.cache.*
```

**Step 2: Extract Errors**
```scala
// CacheError.scala
package com.mycompany.cache

sealed trait CacheError extends NoStackTrace
object CacheError:
  case class KeyNotFound(key: CacheKey) extends CacheError
```

**Step 3: Extract Repository Interface**
```scala
// CacheRepository.scala
package com.mycompany.cache

trait CacheRepository:
  def get[A](key: CacheKey): IO[CacheError, Option[A]]
  def put[A](key: CacheKey, value: A): IO[CacheError, Unit]
```

**Step 4: Move Implementation to `internal/`**
```scala
// internal/CacheServiceLive.scala
package com.mycompany.cache.internal

private[cache] final case class CacheServiceLive(
  repository: CacheRepository
) extends CacheService
```

**Step 5: Add Infrastructure Modules**
```
cache/
  core/
  redis/        # New Redis implementation
  postgres/     # New Postgres implementation
```

---

### From Domain-Driven to Self-Contained

Usually not recommended, but if needed:

**Step 1: Create Single Object**
```scala
object CacheService:
  // Copy all components here
```

**Step 2: Inline Models and Errors**
```scala
object CacheService:
  case class CacheKey(...)  // From Cache.scala
  sealed trait Error        // From CacheError.scala
```

**Step 3: Make Repository Private**
```scala
object CacheService:
  private trait Store  // Was CacheRepository
```

**Step 4: Inline Implementation**
```scala
object CacheService:
  private case class Live(...) // Was CacheServiceLive
```

---

## Hybrid Approaches

### Approach 1: Self-Contained with External Repository

Keep service self-contained but accept external repository:

```scala
object CacheService:
  case class CacheKey(value: String)
  sealed trait Error

  trait Service:
    def get[A](key: CacheKey): IO[Error, A]

  // Accept external repository interface
  trait Repository:
    def fetch[A](key: CacheKey): IO[Error, Option[A]]
    def store[A](key: CacheKey, value: A): IO[Error, Unit]

  // Implementation uses repository
  private case class Live(repo: Repository) extends Service:
    def get[A](key: CacheKey): IO[Error, A] =
      repo.fetch[A](key).someOrFail(Error.NotFound(key))

  // Layer requires external repository
  def layer: ZLayer[Repository, Nothing, Service] =
    ZLayer.fromFunction(Live.apply)

  // Provide default in-memory
  object Repository:
    val inMemory: ZLayer[Any, Nothing, Repository] = ???
```

**Benefits**:
- Service remains self-contained
- But allows external repository implementations
- Good middle ground

---

### Approach 2: Domain with Bundled Utilities

Domain structure but include utilities in single file:

```
worker/
  Worker.scala
  WorkerError.scala
  WorkerService.scala
  WorkerRepository.scala
  WorkerUtils.scala          # Self-contained utilities
  internal/
    WorkerServiceLive.scala
```

```scala
// WorkerUtils.scala - self-contained
object WorkerUtils:
  object IdGenerator:
    def generate(): String = UUID.randomUUID().toString

  object Validator:
    def validateConfig(config: Map[String, String]): Boolean = ???
```

---

### Approach 3: Monolithic File with Clear Sections

Single file but clearly sectioned:

```scala
package com.mycompany.cache

import zio.*

// ============================================================================
// PUBLIC API - MODELS
// ============================================================================

case class CacheKey(value: String)
case class CacheEntry[A](...)

// ============================================================================
// PUBLIC API - ERRORS
// ============================================================================

sealed trait CacheError extends NoStackTrace

// ============================================================================
// PUBLIC API - SERVICE
// ============================================================================

trait CacheService:
  def get[A](key: CacheKey): IO[CacheError, A]

object CacheService:
  def layer: ZLayer[CacheRepository, Nothing, CacheService] = ???

// ============================================================================
// PUBLIC API - REPOSITORY
// ============================================================================

trait CacheRepository:
  def fetch[A](key: CacheKey): IO[CacheError, Option[A]]

// ============================================================================
// INTERNAL - IMPLEMENTATION
// ============================================================================

private object internal:
  case class CacheServiceLive(...) extends CacheService
  case class InMemoryRepository(...) extends CacheRepository
```

---

## Real-World Examples

### Example 1: Simple Utility (Self-Contained) ✅

```scala
/** UUID generator - self-contained utility */
object UuidGenerator:
  sealed trait Error extends NoStackTrace:
    def message: String

  object Error:
    case class InvalidFormat(input: String) extends Error:
      val message = s"Invalid UUID format: $input"

  trait Service:
    def generate(): UIO[String]
    def parse(input: String): IO[Error, UUID]

  private case class Live() extends Service:
    def generate(): UIO[String] =
      ZIO.succeed(UUID.randomUUID().toString)

    def parse(input: String): IO[Error, UUID] =
      ZIO.attempt(UUID.fromString(input))
        .mapError(_ => Error.InvalidFormat(input))

  val live: ZLayer[Any, Nothing, Service] =
    ZLayer.succeed(Live())

  def generate(): URIO[Service, String] =
    ZIO.serviceWithZIO[Service](_.generate())
```

**Perfect use case**: Simple, no infrastructure, single implementation

---

### Example 2: Business Domain (Domain-Driven) ✅

```
payment/
  Payment.scala               # Domain model
  PaymentError.scala          # Domain errors
  PaymentService.scala        # Service interface
  PaymentProvider.scala       # Provider interface (Stripe, PayPal, etc.)
  internal/
    PaymentServiceLive.scala
  providers/
    StripeProvider.scala
    PayPalProvider.scala
```

**Perfect use case**: Complex domain, multiple providers, will scale

---

### Example 3: Cache (Either Works)

**Self-Contained** - Good for:
- Simple in-memory cache
- No plans for Redis/Memcached
- Utility across multiple projects

**Domain-Driven** - Good for:
- Need Redis implementation
- Need metrics/monitoring
- Core business feature

---

## Summary: Decision Tree

```
Start
  |
  ├─ Is it a utility/helper? ────────────────────────────────────────────► Self-Contained
  |   (e.g., ID generator, validator, simple cache)
  |
  ├─ Is it < 300 lines total? ───────────────────────────────────────────► Self-Contained
  |
  ├─ Will it have multiple implementations? ─────────────────────────────► Domain-Driven
  |   (e.g., DB, HTTP, Kafka)
  |
  ├─ Does it have infrastructure dependencies? ──────────────────────────► Domain-Driven
  |   (e.g., database, external APIs)
  |
  ├─ Is it a core business domain? ──────────────────────────────────────► Domain-Driven
  |   (e.g., User, Order, Payment, Worker)
  |
  ├─ Will multiple people work on it? ───────────────────────────────────► Domain-Driven
  |
  ├─ Is it for prototyping/throwaway code? ──────────────────────────────► Self-Contained
  |
  └─ When in doubt: ─────────────────────────────────────────────────────► Domain-Driven
      (easier to maintain long-term)
```

---

## Best Practices

### For Self-Contained Services:

1. ✅ Keep under 500 lines
2. ✅ Use clear section comments
3. ✅ Make repository/store private
4. ✅ Provide layer constructors
5. ✅ Include accessor methods
6. ⚠️ Plan migration path if it grows
7. ❌ Don't add infrastructure dependencies

### For Domain-Driven Services:

1. ✅ Separate public API from internal
2. ✅ Use package-private for internal
3. ✅ Keep files focused (< 200 lines)
4. ✅ Infrastructure in separate modules
5. ✅ Clear naming conventions
6. ✅ Document public API
7. ❌ Don't mix domain logic with infrastructure

---

## Conclusion

**Self-Contained Services** are perfect for:
- ✅ Utilities and helpers
- ✅ Simple services
- ✅ Single implementations
- ✅ Prototyping

**Domain-Driven Services** are perfect for:
- ✅ Business domains
- ✅ Complex logic
- ✅ Multiple implementations
- ✅ Long-term projects
- ✅ Team collaboration

**Choose based on**:
- Current size
- Growth potential
- Infrastructure needs
- Team size

**When in doubt**: Start self-contained, migrate to domain-driven when needed!