# Scala/ZIO Project Style Guide - AI Agent Context

> **Purpose**: This document provides comprehensive architectural patterns and best practices for Scala/ZIO projects. Use this as context when working on Scala codebases to ensure consistency and adherence to best practices.

---

## Table of Contents

1. [Core Principles](#core-principles)
2. [Error Modeling](#error-modeling)
3. [Project Structure](#project-structure)
4. [Service Patterns](#service-patterns)
5. [Architecture Layers](#architecture-layers)
6. [Code Templates](#code-templates)
7. [Decision Trees](#decision-trees)

---

## Core Principles

### Golden Rules

1. **Error Modeling**: One error type per domain extending `NoStackTrace` with descriptive messages
2. **Module Organization**: Separate core domain logic from infrastructure implementations
3. **Service Design**: Start with Service Pattern, use Use Case Pattern for complex domains
4. **Code Organization**: Self-contained for utilities (<500 lines), domain-driven for business logic
5. **Type Safety**: Use opaque types (Scala 3) for type-safe identifiers with zero runtime overhead

### Dependency Flow

```
Application (04-app)
    ↓
Implementation Bundles (03-impl)
    ↓
Infrastructure (02-db, 02-http, 02-s3, etc.)
    ↓
Core Domain (01-core)
```

**Rule**: Core domain NEVER depends on infrastructure. Infrastructure depends on core.

---

## Error Modeling

### Pattern: Domain-Wide Error Type

**Always use one sealed trait per domain** (not per service):

```scala
package com.mycompany.worker

import scala.util.control.NoStackTrace

sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  case class NotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class InvalidState(id: Worker.Id, current: Worker.Status, expected: Worker.Status) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $current, expected $expected"

  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause
```

**Benefits**:
- Better composability with `flatMap`, `zipPar`
- Type-safe error channels: `IO[WorkerError, Worker]`
- Automatic Throwable conversion via `NoStackTrace`
- No stack trace overhead

**When to split**: Only for microservices or truly independent domains.

---

## Project Structure

### Monorepo Layout (Recommended)

```
project-root/
├── build.sbt
├── modules/
│   ├── <domain>/
│   │   ├── 01-c-core/           # Domain logic (entities, traits, errors)
│   │   ├── 02-o-db/             # Database implementation
│   │   ├── 02-i-http-server/    # REST API
│   │   ├── 02-o-http-client/    # External API clients
│   │   ├── 03-c-impl/           # Pre-wired bundles
│   │   └── 03-c-it/             # Integration tests
│   └── shared/
│       └── 01-u-core/           # Shared utilities
└── apps/
    └── 04-c-server/             # Main application
```

### Prefix Convention

Layers prefix:
- `01-*` - Core domain (pure, no infrastructure)
- `02-*` - Infrastructure (db, http, s3, kafka)
- `03-*` - Wiring & testing
- `04-*` - Applications

Flow prefix:
- `-c-` - common (input+output)
- `-i-` - input port
- `-o-` - output port
- `-u-` - util

**Important**: Prefixes are ONLY in folder paths, not module names.

```sbt
// ✅ Correct
lazy val workerCore = project.in(file("modules/worker/01-c-core"))
  .settings(name := "worker-core")  // No prefix in name

// ❌ Wrong
.settings(name := "01-c-core")
```

### Package Organization

```scala
// ✅ Public API (top-level package)
package com.mycompany.worker

case class Worker(id: Worker.Id, status: Worker.Status)
trait WorkerService
trait WorkerRepository
sealed trait WorkerError

// ✅ Private implementation (internal package)
package com.mycompany.worker.internal

private[worker] final case class WorkerServiceLive(
  repository: WorkerRepository
) extends WorkerService
```

---

## Service Patterns

### Pattern 1: Service Pattern (Default - 80% of cases)

Use for: Related operations, CRUD-focused, simple to medium complexity

```scala
// Service interface
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def unregister(id: Worker.Id): IO[WorkerError, Unit]
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

// Implementation
package com.mycompany.worker.internal

private[worker] final case class WorkerServiceLive(
  repository: WorkerRepository
) extends WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker] =
    for
      workerId <- ZIO.succeed(Worker.Id(id))
      _        <- repository.findById(workerId).flatMap {
                    case Some(_) => ZIO.fail(WorkerError.AlreadyExists(workerId))
                    case None    => ZIO.unit
                  }
      worker   = Worker(workerId, Worker.Status.Pending, config)
      _        <- repository.save(worker)
    yield worker

// Layer
object WorkerService:
  def layer: ZLayer[WorkerRepository, Nothing, WorkerService] =
    ZLayer.fromFunction(internal.WorkerServiceLive.apply)

  // Accessor
  def register(id: String, config: Map[String, String]): ZIO[WorkerService, WorkerError, Worker] =
    ZIO.serviceWithZIO[WorkerService](_.register(id, config))
```

### Pattern 2: Use Case Pattern (Complex domains)

Use for: DDD, CQRS, independent operations, large-scale systems

```scala
object WorkerUseCases:

  // Simple use case (direct parameters)
  def registerWorker(id: String, config: Map[String, String]): ZIO[WorkerRepository, WorkerError, Worker] =
    for
      workerId <- ZIO.succeed(Worker.Id(id))
      _        <- WorkerRepository.findById(workerId).flatMap {
                    case Some(_) => ZIO.fail(WorkerError.AlreadyExists(workerId))
                    case None    => ZIO.unit
                  }
      worker   = Worker(workerId, Worker.Status.Pending, config)
      _        <- WorkerRepository.save(worker)
    yield worker

  // Complex use case (Input/Output case classes for 3+ parameters)
  case class RegisterWorkerInput(id: String, config: Map[String, String], metadata: WorkerMetadata)
  case class RegisterWorkerOutput(worker: Worker, registeredAt: Instant)

  def registerWorker(input: RegisterWorkerInput): ZIO[WorkerRepository & Clock, WorkerError, RegisterWorkerOutput] =
    for
      workerId <- ZIO.succeed(Worker.Id(input.id))
      _        <- WorkerRepository.findById(workerId).flatMap {
                    case Some(_) => ZIO.fail(WorkerError.AlreadyExists(workerId))
                    case None    => ZIO.unit
                  }
      now      <- Clock.instant
      worker   = Worker(workerId, Worker.Status.Pending, input.config)
      _        <- WorkerRepository.save(worker)
    yield RegisterWorkerOutput(worker, now)
```

### Pattern 3: Command/Event Handler Pattern (CQRS/Event Sourcing)

Use for: Event sourcing, audit trails, saga orchestration, compliance requirements

```scala
object WorkerService:
  // Events (past tense, immutable facts)
  enum Event:
    case WorkerRegistered(id: Worker.Id, registeredAt: Instant)
    case WorkerUnregistered(id: Worker.Id, unregisteredAt: Instant)
    case HeartbeatRecorded(id: Worker.Id, recordedAt: Instant)

  // Commands (GADT for type safety)
  enum Command[+E]:
    case RegisterWorker(id: Worker.Id, config: Map[String, String]) extends Command[Event.WorkerRegistered]
    case UnregisterWorker(id: Worker.Id) extends Command[Event.WorkerUnregistered]
    case RecordHeartbeat(id: Worker.Id) extends Command[Event.HeartbeatRecorded]

  // Handler interface
  trait Handler:
    def handle[E](command: Command[E]): IO[WorkerError, E]

  // Implementation
  final class Live(repository: WorkerRepository, eventPublisher: EventPublisher) extends Handler:
    def handle[E](command: Command[E]): IO[WorkerError, E] =
      command match
        case Command.RegisterWorker(id, config) => registerWorker(id, config)
        case Command.UnregisterWorker(id) => unregisterWorker(id)
        case Command.RecordHeartbeat(id) => recordHeartbeat(id)

    private def registerWorker(id: Worker.Id, config: Map[String, String]): IO[WorkerError, Event.WorkerRegistered] =
      for
        _      <- validateWorkerNotExists(id)
        now    <- Clock.instant
        worker = Worker(id, Worker.Status.Pending, config)
        _      <- repository.save(worker)
        event  = Event.WorkerRegistered(id, now)
        _      <- eventPublisher.publish(event)
      yield event
```

**Use Handler Pattern when**:
- ✅ Event sourcing or CQRS
- ✅ Complete audit trail needed
- ✅ Command replay for debugging
- ✅ Commands/events cross service boundaries

**Don't use when**:
- ❌ Simple CRUD operations
- ❌ Performance-critical paths
- ❌ Rapid prototyping

---

## Architecture Layers

### Layer 1: Core Domain Types

Located in: `01-c-core/src/main/scala/com/mycompany/<domain>/`

```scala
// Entity with type-safe IDs (opaque types for zero overhead)
case class Worker(
  id: Worker.Id,
  status: Worker.Status,
  config: Worker.Config
)

object Worker:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  enum Status:
    case Pending, Active, Stopped

  case class Config(settings: Map[String, String])

// Service interface (@accessible for ZIO accessor generation)
trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]
  def delete(id: Worker.Id): IO[WorkerError, Unit]

// Errors (sealed trait hierarchy)
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  case class NotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class AlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker '${id.value}' already exists"
```

### Layer 2: Validated Domain Models (Smart Constructors)

Located in: `01-core/src/main/scala/com/mycompany/<domain>/validated/`

```scala
// Validated model with private constructor
case class WorkerConfig private (settings: Map[String, String])

object WorkerConfig:
  private val requiredKeys = Set("region", "environment")

  // Smart constructor returns Either
  def apply(settings: Map[String, String]): Either[WorkerError.InvalidConfig, WorkerConfig] =
    val missingKeys = requiredKeys -- settings.keySet
    if missingKeys.isEmpty then
      Right(new WorkerConfig(settings))
    else
      Left(WorkerError.InvalidConfig(s"Missing required keys: ${missingKeys.mkString(", ")}"))

// Domain-specific composite model
case class ValidatedWorker(
  worker: Worker,
  config: WorkerConfig
)
```

### Layer 3: Domain-Specific Services (Service Wrappers)

Located in: `01-core/src/main/scala/com/mycompany/<domain>/service/`

**Pattern**: Encapsulate dependencies, provide clean API without ZIO environment leakage

```scala
final class WorkerManager(
  workerService: WorkerService,
  repository: WorkerRepository,
  configValidator: ConfigValidator
):

  // Clean API - returns IO[WorkerError, Result] instead of ZIO[Env, Error, Result]
  def registerValidatedWorker(
    id: String,
    settings: Map[String, String]
  ): IO[WorkerError, ValidatedWorker] =
    (for
      // 1. Validate configuration
      config <- ZIO.fromEither(WorkerConfig(settings))

      // 2. Register worker
      worker <- workerService.register(id, settings)

      // 3. Return composite
    yield ValidatedWorker(worker, config))
      .provideEnvironment(
        ZEnvironment(workerService).add(repository).add(configValidator)
      )

object WorkerManager:
  val layer: ZLayer[WorkerService & WorkerRepository & ConfigValidator, Nothing, WorkerManager] =
    ZLayer.derive[WorkerManager]
```

### Layer 4: Infrastructure Implementations

#### 4.1 Adapters to External Domains

Located in: `02-c-<infra>/src/main/scala/com/mycompany/<domain>/adapter/`

```scala
// Adapter translates between domains
final class WorkerRepositoryAdapter(
  processRepo: ProcessRepo[CrudIO],  // External domain
  ds: DataSource
) extends WorkerRepository:

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    processRepo
      .findByCode(ProcessCode(id.value))  // Translate to external domain
      .orDie
      .map(_.map(toWorker))               // Map back to our domain
      .provideEnvironment(ZEnvironment(ds))

  private def toWorker(process: Process): Worker =
    Worker(
      id = Worker.Id(process.code),
      status = toWorkerStatus(process.status),
      config = Worker.Config(process.settings)
    )

object WorkerRepositoryAdapter:
  val layer: ZLayer[ProcessRepo[CrudIO] & DataSource, Nothing, WorkerRepository] =
    ZLayer.derive[WorkerRepositoryAdapter]
```

#### 4.2 Database Implementations (Quill)

Located in: `02-o-db/src/main/scala/com/mycompany/<domain>/db/`

```scala
final class QuillWorkerRepository(ds: DataSource) extends WorkerRepository:
  import com.mycompany.infra.rdb.QuillContext._

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    queryWorkerById(id.value)
      .orDie
      .map(_.map(_.toDomain))
      .provideEnvironment(ZEnvironment(ds))

  override def save(worker: Worker): IO[WorkerError, Unit] =
    run(
      quote(
        query[WorkerDao]
          .insertValue(lift(WorkerDao.fromDomain(worker)))
      )
    )
    .orDie
    .unit
    .provideEnvironment(ZEnvironment(ds))

  private def queryWorkerById(id: String) = run(
    quote(
      query[WorkerDao]
        .filter(_.id == lift(id))
    )
  )

object QuillWorkerRepository:
  val layer: ZLayer[DataSource, Nothing, WorkerRepository] =
    ZLayer.derive[QuillWorkerRepository]

// DAO (Data Access Object)
private case class WorkerDao(
  id: String,
  status: String,
  settings: String  // JSON or serialized config
)

private object WorkerDao:
  def fromDomain(worker: Worker): WorkerDao =
    WorkerDao(
      id = worker.id.value,
      status = worker.status.toString,
      settings = worker.config.settings.toString  // Serialize
    )

  extension (dao: WorkerDao)
    def toDomain: Worker = Worker(
      id = Worker.Id(dao.id),
      status = Worker.Status.valueOf(dao.status),
      config = Worker.Config(parseSettings(dao.settings))  // Deserialize
    )
```

#### 4.3 HTTP Server (ZIO HTTP)

Located in: `02-o-http-server/src/main/scala/com/mycompany/<domain>/http/`

**Important**: Always use separate DTOs from domain models

```scala
package com.mycompany.worker.http

import zio.http._
import zio.json._

// DTOs (separate from domain!)
case class RegisterWorkerRequest(
  id: String,
  settings: Map[String, String]
) derives JsonCodec

case class WorkerResponse(
  id: String,
  status: String,
  config: Map[String, String]
) derives JsonCodec

object WorkerResponse:
  def fromDomain(worker: Worker): WorkerResponse =
    WorkerResponse(
      id = worker.id.value,
      status = worker.status.toString,
      config = worker.config.settings
    )

// Routes
object WorkerRoutes:
  def routes: Routes[WorkerService, Nothing] = Routes(

    // POST /workers
    Method.POST / "workers" -> handler { (req: Request) =>
      for
        dto    <- req.body.as[RegisterWorkerRequest]
        worker <- WorkerService.register(dto.id, dto.settings)
        resp   = WorkerResponse.fromDomain(worker)
      yield Response.json(resp.toJson)
    },

    // GET /workers/:id
    Method.GET / "workers" / string("id") -> handler { (id: String, req: Request) =>
      for
        worker <- WorkerService.getWorker(Worker.Id(id))
        resp   = WorkerResponse.fromDomain(worker)
      yield Response.json(resp.toJson)
    }
  )
```

#### 4.4 Implementation Bundles

Located in: `03-c-impl/src/main/scala/com/mycompany/<domain>/impl/`

```scala
package com.mycompany.worker.impl

import zio._

object WorkerLayers:

  // Production configuration
  val production: ZLayer[DataSource, Nothing, WorkerService] =
    QuillWorkerRepository.layer >+>
    WorkerService.layer

  // In-memory for testing
  val inMemory: ZLayer[Any, Nothing, WorkerService] =
    InMemoryWorkerRepository.layer >+>
    WorkerService.layer

  // All HTTP routes
  val httpRoutes: Routes[WorkerService, Nothing] =
    WorkerRoutes.routes

  // Complete application layer (all dependencies)
  val fullStack: ZLayer[DataSource, Nothing, WorkerService & WorkerManager] =
    production >+>
    ConfigValidator.layer >+>
    WorkerManager.layer
```

---

## Code Templates

### Template 1: Domain-Driven Service

```scala
// File: Worker.scala
package com.mycompany.worker

case class Worker(
  id: Worker.Id,
  status: Worker.Status,
  config: Map[String, String]
)

object Worker:
  case class Id(value: String) extends AnyVal

  enum Status:
    case Pending, Active, Stopped

// File: WorkerError.scala
package com.mycompany.worker

import scala.util.control.NoStackTrace

sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  case class NotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class AlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker '${id.value}' already exists"

  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause

// File: WorkerService.scala
package com.mycompany.worker

import zio._

trait WorkerService:
  def create(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def get(id: Worker.Id): IO[WorkerError, Worker]
  def delete(id: Worker.Id): IO[WorkerError, Unit]

object WorkerService:
  def layer: ZLayer[WorkerRepository, Nothing, WorkerService] =
    ZLayer.fromFunction(internal.WorkerServiceLive.apply)

  def create(id: String, config: Map[String, String]): ZIO[WorkerService, WorkerError, Worker] =
    ZIO.serviceWithZIO[WorkerService](_.create(id, config))

// File: WorkerRepository.scala
package com.mycompany.worker

import zio._

trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]
  def delete(id: Worker.Id): IO[WorkerError, Unit]

// File: internal/WorkerServiceLive.scala
package com.mycompany.worker.internal

import com.mycompany.worker._
import zio._

private[worker] final case class WorkerServiceLive(
  repository: WorkerRepository
) extends WorkerService:

  def create(id: String, config: Map[String, String]): IO[WorkerError, Worker] =
    for
      workerId <- ZIO.succeed(Worker.Id(id))
      existing <- repository.findById(workerId)
      _        <- existing match
                    case Some(_) => ZIO.fail(WorkerError.AlreadyExists(workerId))
                    case None    => ZIO.unit
      worker   = Worker(workerId, Worker.Status.Pending, config)
      _        <- repository.save(worker)
    yield worker

  def get(id: Worker.Id): IO[WorkerError, Worker] =
    repository.findById(id).flatMap {
      case Some(worker) => ZIO.succeed(worker)
      case None => ZIO.fail(WorkerError.NotFound(id))
    }

  def delete(id: Worker.Id): IO[WorkerError, Unit] =
    repository.delete(id)
```

### Template 2: Self-Contained Service (Utilities)

```scala
// File: CacheService.scala
package com.mycompany.util

import zio._
import scala.util.control.NoStackTrace
import java.time.Instant

object CacheService:

  // Models
  case class CacheKey(value: String) extends AnyVal
  case class CacheEntry[A](key: CacheKey, value: A, expiresAt: Option[Instant])

  // Errors
  sealed trait Error extends NoStackTrace:
    def message: String
    override def getMessage: String = message

  object Error:
    case class KeyNotFound(key: CacheKey) extends Error:
      val message = s"Cache key '${key.value}' not found"

    case class Expired(key: CacheKey) extends Error:
      val message = s"Cache key '${key.value}' has expired"

  // Service Interface
  trait Service:
    def get[A](key: CacheKey): IO[Error, A]
    def put[A](key: CacheKey, value: A, ttl: Option[Duration]): IO[Error, Unit]
    def delete(key: CacheKey): UIO[Unit]

  // Private Storage
  private trait Store:
    def get[A](key: CacheKey): UIO[Option[CacheEntry[A]]]
    def put[A](entry: CacheEntry[A]): UIO[Unit]
    def delete(key: CacheKey): UIO[Unit]

  // Private Implementation
  private case class Live(store: Store, clock: Clock) extends Service:
    def get[A](key: CacheKey): IO[Error, A] =
      for
        maybeEntry <- store.get[A](key)
        entry      <- ZIO.fromOption(maybeEntry).orElseFail(Error.KeyNotFound(key))
        now        <- clock.instant
        _          <- entry.expiresAt match
                        case Some(expiresAt) if now.isAfter(expiresAt) =>
                          ZIO.fail(Error.Expired(key))
                        case _ =>
                          ZIO.unit
      yield entry.value

    def put[A](key: CacheKey, value: A, ttl: Option[Duration]): IO[Error, Unit] =
      for
        now       <- clock.instant
        expiresAt = ttl.map(duration => now.plus(duration))
        entry     = CacheEntry(key, value, expiresAt)
        _         <- store.put(entry)
      yield ()

    def delete(key: CacheKey): UIO[Unit] =
      store.delete(key)

  // In-memory store implementation
  private case class InMemoryStore(ref: Ref[Map[CacheKey, CacheEntry[Any]]]) extends Store:
    def get[A](key: CacheKey): UIO[Option[CacheEntry[A]]] =
      ref.get.map(_.get(key).asInstanceOf[Option[CacheEntry[A]]])

    def put[A](entry: CacheEntry[A]): UIO[Unit] =
      ref.update(_ + (entry.key -> entry.asInstanceOf[CacheEntry[Any]]))

    def delete(key: CacheKey): UIO[Unit] =
      ref.update(_ - key)

  // Layer
  val inMemory: ZLayer[Any, Nothing, Service] =
    ZLayer.scoped {
      for
        ref   <- Ref.make(Map.empty[CacheKey, CacheEntry[Any]])
        store = InMemoryStore(ref)
        clock <- ZIO.clock
      yield Live(store, clock)
    }

  // Accessors
  def get[A](key: CacheKey): ZIO[Service, Error, A] =
    ZIO.serviceWithZIO[Service](_.get(key))

  def put[A](key: CacheKey, value: A, ttl: Option[Duration] = None): ZIO[Service, Error, Unit] =
    ZIO.serviceWithZIO[Service](_.put(key, value, ttl))

  def delete(key: CacheKey): ZIO[Service, Nothing, Unit] =
    ZIO.serviceWithZIO[Service](_.delete(key))
```

### Template 3: Database Repository (Quill)

```scala
// File: db/QuillWorkerRepository.scala
package com.mycompany.worker.db

import com.mycompany.worker._
import zio._
import javax.sql.DataSource

final class QuillWorkerRepository(ds: DataSource) extends WorkerRepository:
  import com.mycompany.infra.rdb.QuillContext._

  override def save(worker: Worker): IO[WorkerError, Unit] =
    run(
      quote(
        query[WorkerDao].insertValue(lift(WorkerDao.fromDomain(worker)))
          .onConflictUpdate(_.id)(
            (t, e) => t.status -> e.status,
            (t, e) => t.config -> e.config
          )
      )
    )
    .mapError(e => WorkerError.RepositoryError("save", e))
    .unit
    .provideEnvironment(ZEnvironment(ds))

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    run(
      quote(
        query[WorkerDao].filter(_.id == lift(id.value))
      )
    )
    .mapError(e => WorkerError.RepositoryError("findById", e))
    .map(_.headOption.map(_.toDomain))
    .provideEnvironment(ZEnvironment(ds))

  override def delete(id: Worker.Id): IO[WorkerError, Unit] =
    run(
      quote(
        query[WorkerDao].filter(_.id == lift(id.value)).delete
      )
    )
    .mapError(e => WorkerError.RepositoryError("delete", e))
    .unit
    .provideEnvironment(ZEnvironment(ds))

object QuillWorkerRepository:
  val layer: ZLayer[DataSource, Nothing, WorkerRepository] =
    ZLayer.derive[QuillWorkerRepository]

// DAO
private case class WorkerDao(
  id: String,
  status: String,
  config: String
)

private object WorkerDao:
  def fromDomain(worker: Worker): WorkerDao =
    WorkerDao(
      id = worker.id.value,
      status = worker.status.toString,
      config = worker.config.mkString(",")  // Serialize as needed
    )

  extension (dao: WorkerDao)
    def toDomain: Worker = Worker(
      id = Worker.Id(dao.id),
      status = Worker.Status.valueOf(dao.status),
      config = dao.config.split(",").toList.map(_.split("=")).map(a => a(0) -> a(1)).toMap
    )
```

---

## Decision Trees

### Decision 1: Which Service Pattern?

```
Is it CQRS/Event Sourcing? ────────────────────► Handler Pattern
    │
    No
    ↓
Is it a complex domain with independent operations? ─► Use Case Pattern
    │
    No
    ↓
Default ───────────────────────────────────────► Service Pattern
```

### Decision 2: Self-Contained vs Domain-Driven?

```
Is it a utility/helper? ──────────────────────► Self-Contained
(e.g., ID generator, validator)
    │
    No
    ↓
< 300-500 lines total? ───────────────────────► Self-Contained
    │
    No
    ↓
Single implementation only? ──────────────────► Self-Contained
    │
    No
    ↓
Multiple implementations needed? ─────────────► Domain-Driven
(e.g., DB, HTTP, Kafka)
    │
    OR
    ↓
Infrastructure dependencies? ─────────────────► Domain-Driven
(e.g., database, external APIs)
    │
    OR
    ↓
Core business domain? ────────────────────────► Domain-Driven
(e.g., User, Order, Payment)
    │
    OR
    ↓
Multiple developers? ─────────────────────────► Domain-Driven
    │
    OR
    ↓
When in doubt ────────────────────────────────► Domain-Driven
```

### Decision 3: When to Use Input/Output Case Classes?

```
Simple use case (1-2 parameters)? ────────────► Direct parameters
    │
    No
    ↓
Many parameters (3+)? ────────────────────────► Input/Output case classes
    │
    OR
    ↓
Exposed via REST API? ────────────────────────► Separate DTOs (always!)
    │
    OR
    ↓
Need evolution flexibility? ──────────────────► Input/Output case classes
    │
    OR
    ↓
Following CQRS with explicit commands? ───────► Input/Output case classes
```

### Decision 4: When to Split Error Types?

```
All services in same deployment? ─────────────► Single domain error
    │
    No
    ↓
Services deployed independently? ─────────────► Split by service
(microservices)
    │
    OR
    ↓
Different API boundaries? ────────────────────► Split by boundary
(REST vs gRPC)
    │
    OR
    ↓
Very large domain (100+ error cases)? ────────► Consider splitting domain first
```

---

## Common Patterns Summary

| Pattern | Location | Purpose |
|---------|----------|---------|
| **Opaque Types** | Entities | Type-safe IDs with zero overhead (Scala 3) |
| **Sealed Traits** | Error.scala | Exhaustive error handling |
| **Smart Constructors** | validated/*.scala | Validation at construction |
| **Repository Trait** | *Repository.scala | Dependency inversion |
| **Service Trait** | *Service.scala | Use case abstraction |
| **Orchestrator Class** | service/*.scala | Configurable workflows |
| **Service Wrapper** | service/*Manager.scala | Dependency encapsulation |
| **Adapter** | adapter/*.scala | Cross-domain translation |
| **ZLayer** | All objects | Dependency injection |
| **DTOs** | http/*.scala | API boundary separation |

---

## Best Practices Checklist

When reviewing or writing Scala/ZIO code, ensure:

- [ ] **Errors extend `NoStackTrace`** with descriptive messages
- [ ] **One error type per domain** (not per service)
- [ ] **Core modules have no infrastructure dependencies**
- [ ] **DTOs separate from domain models** (for API boundaries)
- [ ] **Internal implementations use `private[domain]`**
- [ ] **Opaque types for type-safe IDs (Scala 3) or AnyVal (Scala 2)**
- [ ] **Repository/Service traits for abstraction**
- [ ] **ZLayer for dependency injection**
- [ ] **Smart constructors for validation**
- [ ] **Sealed traits for exhaustive pattern matching**
- [ ] **Package organization: public API in top-level, internals in `internal/`**
- [ ] **Folder prefixes (01-, 02-, 03-) in paths only, not module names**

---

## Key Takeaways for AI Agents

1. **Always start with the domain**: Define entities, errors, and service interfaces in `01-core` first
2. **Separate concerns**: Core domain logic should never depend on infrastructure
3. **Use type safety**: Value classes for IDs, sealed traits for errors and enums
4. **Provide layers**: Every implementation should have a `ZLayer` for DI
5. **DTOs at boundaries**: Never expose domain models directly via HTTP/gRPC
6. **Descriptive errors**: Every error case should have a clear message
7. **Follow the prefix convention**: 01-core, 02-infra, 03-impl, 04-app
8. **Encapsulate dependencies**: Use service wrappers for clean APIs
9. **Test with layers**: Use `provideLayer` or `provide` for testing
10. **Document with types**: Use meaningful names and type aliases

---

**Version**: 1.0
**Last Updated**: 2025-01-09
**Source**: https://github.com/yourorg/scala-project-style-guide

This document is optimized for AI agent consumption and should be passed as context when working on Scala/ZIO projects.