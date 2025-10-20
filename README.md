# Scala Project Style Guide

> **⚠️ Work in Progress**
> This style guide is actively being developed and refined. Examples and code snippets were generated with AI/LLM assistance and have been reviewed and modified by a human developer.

A comprehensive style guide for building scalable, maintainable Scala applications with ZIO.

---

## Table of Contents

1. [Error Modeling](#1-error-modeling)
2. [Monorepo Structure](#2-monorepo-structure)
3. [Service Pattern vs Use Case Pattern](#3-service-pattern-vs-use-case-pattern)
4. [Self-Contained vs Domain-Driven Services](#4-self-contained-vs-domain-driven-services)
5. [Quick Reference](#5-quick-reference)
6. [Getting Started Checklist](#6-getting-started-checklist)

---

## 1. Error Modeling

### Recommendation: Domain-Wide Errors

**Use a single error type per domain** with descriptive messages and Throwable conversion.

```scala
package com.mycompany.worker

import scala.util.control.NoStackTrace

sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

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

### Key Principles:

1. ✅ **One error type per domain** (not per service/use case)
   - Better composability with `flatMap`, `zipPar`
   - Simpler error handling
   - Easier refactoring

2. ✅ **Include descriptive messages** in each error case
   - User-friendly error reporting
   - Better debugging experience
   - Direct logging support

3. ✅ **Extend NoStackTrace** for automatic Throwable conversion
   - Works with logging frameworks
   - No stack trace overhead
   - Can preserve underlying causes with `getCause`

4. ✅ **Group errors logically** in companion object
   - Registration errors
   - State errors
   - Infrastructure errors

5. ❌ **Avoid service-specific error types** unless services are truly independent
   - Makes composition difficult
   - Requires error conversion
   - More boilerplate

### When to Split Error Types:

Only create separate error types when:
- Services deployed independently (microservices)
- Different API boundaries (REST vs gRPC)
- Very large domains (100+ error cases - consider splitting domain instead)

📖 **[Full Error Modeling Guide →](./docs/ERROR_MODELING_GUIDE.md)**

---

## 2. Monorepo Structure

### Recommendation: Domain-Based Modules with Layer Separation

**For complex projects**, split each domain into layers using numbered prefixes:

```
modules/
├── worker/
│   ├── 01-core/           # Domain logic (no infrastructure dependencies)
│   ├── 02-db/             # Database implementation
│   ├── 02-http-server/    # REST API endpoints
│   ├── 02-http-client/    # External API clients
│   ├── 03-impl/           # Pre-wired implementation bundles
│   └── 03-it/             # Integration tests
├── files/
│   ├── 01-core/
│   ├── 02-db/
│   ├── 02-s3/             # S3 storage implementation
│   ├── 02-local-fs/       # Local filesystem implementation
│   ├── 02-http-server/
│   ├── 03-impl/
│   └── 03-it/
└── shared/
    └── 01-core/           # Shared utilities
apps/
└── 04-server/             # Main application (optional prefix)
```

**Prefix Convention:**
- `01-*` - Domain definition & pure implementation (core, models, errors)
- `02-*` - Infrastructure implementations (db, http-server, http-client, s3, kafka)
- `03-*` - Wiring & testing (impl bundles, integration tests)
- `04-*` - Applications (optional - only in folder structure, not module names)

**Note:** Prefixes are ONLY in folder paths for organization. Module names in `build.sbt` remain unchanged:
```sbt
lazy val workerCore = project.in(file("modules/worker/01-core"))  // Folder path
  .settings(name := "worker-core")  // Module name (no prefix)
```

### Module Organization:

**`<domain>-core`** - Pure domain logic:
```scala
package com.mycompany.worker

// Public API
case class Worker(...)
trait WorkerService
trait WorkerRepository
sealed trait WorkerError

// Private implementation
package com.mycompany.worker.internal
private[worker] case class WorkerServiceLive(...) extends WorkerService
```

**`<domain>-db`** - Database implementation:
```scala
package com.mycompany.worker.db

final class PostgresWorkerRepository(...) extends WorkerRepository

object PostgresWorkerRepository:
  def layer: ZLayer[DataSource, Nothing, WorkerRepository] = ???
```

**`<domain>-http-server`** - REST API:
```scala
package com.mycompany.worker.http

// DTOs (separate from domain models!)
case class RegisterWorkerRequest(...) derives JsonCodec
case class RegisterWorkerResponse(...) derives JsonCodec

// Routes
object WorkerRoutes:
  def routes: Routes[WorkerService, Nothing] = ???
```

**`<domain>-impl`** - Pre-wired bundles:
```scala
package com.mycompany.worker.impl

object WorkerLayers:
  // Production configuration
  val production: ZLayer[DataSource, Nothing, WorkerService] =
    PostgresWorkerRepository.layer >+>
    WorkerService.layer

  // In-memory for testing
  val inMemory: ZLayer[Any, Nothing, WorkerService] =
    InMemoryWorkerRepository.layer >+>
    WorkerService.layer

  // All HTTP routes
  val httpRoutes: Routes[WorkerService, Nothing] =
    WorkerRoutes.routes
```

### Dependency Rules:

```
┌─────────────┐
│    04-app   │  ← Can depend on everything
└──────┬──────┘
       │
┌──────▼──────┐
│    03-impl  │  ← Can depend on core + infrastructure
└──────┬──────┘
       │
┌──────▼───────┬──────────┬──────────┐
│    02-db     │  02-http │ 02-other │  ← Can depend only on core
└──────────────┴──────────┴──────────┘
       │
┌──────▼──────┐
│    01-core  │  ← NO infrastructure dependencies
└─────────────┘
```

### Package Organization:

```scala
// ✅ Public API (top-level package)
package com.mycompany.worker
  case class Worker(...)
  trait WorkerService
  sealed trait WorkerError

// ✅ Private implementation (internal package)
package com.mycompany.worker.internal
  private[worker] case class WorkerServiceLive(...) extends WorkerService
```

### Key Principles:

1. ✅ **Separate core from infrastructure** - keeps domain pure
2. ✅ **Use `impl` modules** - provides convenient pre-wired bundles
3. ✅ **Public API in top-level package** - clear boundaries
4. ✅ **Hide internals with `private[domain]`** - encapsulation
5. ✅ **One module per technology** - db, http-server, http-client, s3, etc.

📖 **[Full Monorepo Structure Guide →](./docs/MONOREPO_PROJECT_STRUCTURE.md)**

---

## 3. Service Pattern vs Use Case Pattern

### Recommendation: Service Pattern for Most Cases

**Use Service Pattern** as the default, with Use Case Pattern for complex domains.

### Service Pattern (Default):

```scala
// Service interface with all methods
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def unregister(id: Worker.Id): IO[WorkerError, Unit]
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

// Implementation
final case class WorkerServiceLive(
  workerStore: WorkerStore,
  heartbeatStore: HeartbeatStore
) extends WorkerService:
  // Implementation of all methods

// Layer
object WorkerService:
  def layer: ZLayer[WorkerStore & HeartbeatStore, Nothing, WorkerService] =
    ZLayer.fromFunction(WorkerServiceLive.apply)
```

**Benefits:**
- ✅ Simpler to understand
- ✅ All operations in one place
- ✅ Less boilerplate
- ✅ Easy to mock for testing
- ✅ Good for 80% of use cases

**Use when:**
- Related operations that share state
- CRUD-focused domains
- Simple to medium complexity
- Team prefers OOP-style organization

---

### Use Case Pattern (Advanced):

```scala
// Each use case is a standalone function
object WorkerUseCases:

  // Option 1: Direct parameters (recommended for simple use cases)
  def registerWorker(id: String, config: Map[String, String]): ZIO[WorkerDependencies, WorkerError, Worker] = ???

  // Option 2: Input/Output case classes (for complex/evolving use cases)
  case class RegisterWorkerInput(id: String, config: Map[String, String])
  case class RegisterWorkerOutput(worker: Worker, registeredAt: Instant)

  def registerWorker(input: RegisterWorkerInput): ZIO[WorkerDependencies, WorkerError, RegisterWorkerOutput] = ???
```

**Benefits:**
- ✅ Each use case is independent (Single Responsibility)
- ✅ Minimal dependencies per use case
- ✅ Easy to compose use cases
- ✅ Better for Domain-Driven Design
- ✅ Excellent for CQRS/Event Sourcing

**Use when:**
- Complex business logic per operation
- Following DDD principles
- CQRS/Event Sourcing architecture
- Operations are truly independent
- Large-scale systems

### Input/Output Case Classes: Optional!

**Use Input/Output case classes when:**
- ✅ Many parameters (3+)
- ✅ Exposed via REST API (though separate DTOs preferred!)
- ✅ Need evolution flexibility
- ✅ Following CQRS with explicit commands

**Use direct parameters when:**
- ✅ Simple use cases (1-2 parameters)
- ✅ Internal use only
- ✅ Less ceremony needed

### REST API Exposure:

**Best Practice**: Always use separate DTOs at API layer, regardless of pattern:

```scala
// API Layer - DTOs
case class RegisterWorkerRequest(id: String, config: Map[String, String]) derives JsonCodec
case class RegisterWorkerResponse(id: String, status: String) derives JsonCodec

// Domain Layer (Service or Use Case)
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]

// Controller maps DTOs ↔ Domain
object WorkerController:
  def routes: Routes[WorkerService, Nothing] = Routes(
    Method.POST / "workers" -> handler { (req: Request) =>
      for
        dto <- req.body.as[RegisterWorkerRequest]
        worker <- WorkerService.register(dto.id, dto.config)
        response = RegisterWorkerResponse(worker.id.value, worker.status.toString)
      yield Response.json(response.toJson)
    }
  )
```

### Key Principles:

1. ✅ **Start with Service Pattern** - simpler default
2. ✅ **Use Case Pattern for complex domains** - better organization
3. ✅ **Input/Output case classes are optional** - use direct parameters for simplicity
4. ✅ **Always separate DTOs from domain types** - for API boundaries
5. ✅ **Same error type for all operations** - better composability

📖 **[Full Service vs Use Case Comparison →](./docs/WORKER_SERVICE_PATTERNS_COMPARISON.md)**

---

## 4. Self-Contained vs Domain-Driven Services

### Recommendation: Choose Based on Service Type

**Self-Contained for utilities, Domain-Driven for business domains.**

### Self-Contained Service (Utilities):

Everything in one file/object:

```scala
object CacheService:
  // Models
  case class CacheKey(value: String) extends AnyVal
  case class CacheEntry[A](key: CacheKey, value: A, expiresAt: Option[Instant])

  // Errors
  sealed trait Error extends NoStackTrace:
    def message: String

  object Error:
    case class KeyNotFound(key: CacheKey) extends Error:
      val message = s"Cache key '${key.value}' not found"

  // Service Interface
  trait Service:
    def get[A](key: CacheKey): IO[Error, A]
    def put[A](key: CacheKey, value: A, ttl: Option[Duration]): IO[Error, Unit]

  // Private Storage
  private trait Store:
    def get[A](key: CacheKey): UIO[Option[CacheEntry[A]]]

  // Private Implementation
  private case class Live(store: Store) extends Service:
    // Implementation

  // Layer
  val inMemory: ZLayer[Any, Nothing, Service] = ???

  // Accessors
  def get[A](key: CacheKey): ZIO[Service, Error, A] = ???
```

**Use when:**
- ✅ Utility/helper services (validators, parsers, ID generators)
- ✅ Simple services (< 300-500 lines total)
- ✅ No infrastructure dependencies
- ✅ Single implementation only
- ✅ Copy-paste friendly (shared across projects)
- ✅ Prototyping/throwaway code

**Examples:**
- Cache services
- ID generators
- Validators
- Parsers
- Rate limiters
- Simple metrics collectors

---

### Domain-Driven Service (Business Domains):

Structured package organization:

```
worker/
├── Worker.scala                    # Domain model
├── WorkerError.scala              # Domain errors
├── WorkerService.scala            # Service interface
├── WorkerRepository.scala         # Repository interface
└── internal/
    ├── WorkerServiceLive.scala    # Service implementation
    └── InMemoryWorkerRepository.scala
```

**Use when:**
- ✅ Business domains (Worker, Order, Payment, User)
- ✅ Multiple implementations needed (DB, HTTP, Kafka)
- ✅ Infrastructure dependencies (database, external APIs)
- ✅ Team collaboration (multiple developers)
- ✅ Long-term projects (will evolve over time)
- ✅ Complex business logic (> 300 lines)

**Examples:**
- User management
- Order processing
- Payment handling
- Worker orchestration
- File management
- Notification systems

### Decision Tree:

```
Is it a utility/helper? ───────────────────────────────► Self-Contained
  (e.g., ID generator, validator)

< 300 lines total? ────────────────────────────────────► Self-Contained

Single implementation only? ───────────────────────────► Self-Contained

Multiple implementations needed? ──────────────────────► Domain-Driven
  (e.g., DB, HTTP, Kafka)

Infrastructure dependencies? ──────────────────────────► Domain-Driven
  (e.g., database, external APIs)

Core business domain? ─────────────────────────────────► Domain-Driven
  (e.g., User, Order, Payment)

Multiple developers? ──────────────────────────────────► Domain-Driven

When in doubt: ────────────────────────────────────────► Domain-Driven
  (easier to maintain long-term)
```

### Migration Path:

**Self-Contained → Domain-Driven** (when service grows):

1. Extract models to separate file
2. Extract errors to separate file
3. Extract repository interface
4. Move implementation to `internal/`
5. Add infrastructure modules as needed

### Key Principles:

1. ✅ **Self-Contained for utilities** - simple, copy-paste friendly
2. ✅ **Domain-Driven for business logic** - scalable, maintainable
3. ✅ **Keep self-contained < 500 lines** - migrate if larger
4. ✅ **Start simple, refactor when needed** - don't over-engineer
5. ❌ **Don't add infrastructure to self-contained** - use domain-driven instead

📖 **[Full Self-Contained vs Domain-Driven Guide →](./docs/SELF_CONTAINED_VS_DOMAIN_SERVICES.md)**

---

## 5. Quick Reference

### Project Structure Template

```
project-root/
├── build.sbt
├── modules/
│   ├── <domain>/
│   │   ├── 01-core/           # Domain logic (Worker, WorkerService, WorkerError)
│   │   ├── 02-db/             # Database implementation (PostgresWorkerRepository)
│   │   ├── 02-http-server/    # REST API (WorkerRoutes, DTOs)
│   │   ├── 03-impl/           # Pre-wired bundles (WorkerLayers)
│   │   └── 03-it/             # Integration tests
│   ├── shared/
│   │   └── 01-core/           # Shared utilities (self-contained)
│   └── utils/                 # Self-contained utility services
│       ├── CacheService.scala
│       ├── IdGenerator.scala
│       └── Validator.scala
└── apps/
    └── 04-server/             # Main application (optional prefix)
        └── src/main/scala/
            └── Main.scala
```

**Numbering Convention:**
- `01-*` = Domain definition & pure implementation
- `02-*` = Infrastructure (db, http, s3, kafka, etc.)
- `03-*` = Wiring & testing (impl, it)
- `04-*` = Applications (optional)

### Error Template

```scala
package com.mycompany.<domain>

import scala.util.control.NoStackTrace

sealed trait <Domain>Error extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object <Domain>Error:
  case class NotFound(id: <Domain>.Id) extends <Domain>Error:
    val message = s"<Domain> with ID '${id.value}' not found"

  case class AlreadyExists(id: <Domain>.Id) extends <Domain>Error:
    val message = s"<Domain> '${id.value}' already exists"

  case class RepositoryError(operation: String, cause: Throwable) extends <Domain>Error:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause
```

### Service Template (Domain-Driven)

```scala
package com.mycompany.<domain>

import zio.*

// Service Interface
trait <Domain>Service:
  def create(...): IO[<Domain>Error, <Domain>]
  def get(id: <Domain>.Id): IO[<Domain>Error, <Domain>]
  def delete(id: <Domain>.Id): IO[<Domain>Error, Unit]

object <Domain>Service:
  def layer: ZLayer[<Domain>Repository, Nothing, <Domain>Service] =
    ZLayer.fromFunction(internal.<Domain>ServiceLive.apply)

  // Accessor methods
  def get(id: <Domain>.Id): ZIO[<Domain>Service, <Domain>Error, <Domain>] =
    ZIO.serviceWithZIO[<Domain>Service](_.get(id))

// Repository Interface
trait <Domain>Repository:
  def save(entity: <Domain>): IO[<Domain>Error, Unit]
  def findById(id: <Domain>.Id): IO[<Domain>Error, Option[<Domain>]]
  def delete(id: <Domain>.Id): IO[<Domain>Error, Unit]

// Internal Implementation
package com.mycompany.<domain>.internal

private[<domain>] final case class <Domain>ServiceLive(
  repository: <Domain>Repository
) extends <Domain>Service:
  // Implementation
```

### Self-Contained Template (Utilities)

```scala
package com.mycompany.util

import zio.*
import scala.util.control.NoStackTrace

object <Service>Service:

  // Models
  case class <Model>(...)

  // Errors
  sealed trait Error extends NoStackTrace:
    def message: String
    override def getMessage: String = message

  object Error:
    case class <ErrorCase>(...) extends Error:
      val message = "..."

  // Service Interface
  trait Service:
    def operation(...): IO[Error, Result]

  // Private Implementation
  private case class Live(...) extends Service:
    // Implementation

  // Layer
  val live: ZLayer[Any, Nothing, Service] = ???

  // Accessors
  def operation(...): ZIO[Service, Error, Result] =
    ZIO.serviceWithZIO[Service](_.operation(...))
```

---

## 6. Getting Started Checklist

### For New Projects:

- [ ] **Choose architecture**: Simple project? Self-contained. Complex? Domain-driven.
- [ ] **Set up monorepo structure**: Create `modules/`, `apps/` directories
- [ ] **Define domains**: Identify business domains (User, Order, Payment, etc.)
- [ ] **Create domain modules**: `<domain>-core`, `<domain>-db`, `<domain>-impl`
- [ ] **Define errors**: One error type per domain with descriptive messages
- [ ] **Choose pattern**: Service Pattern (default) or Use Case Pattern (complex domains)
- [ ] **Set up build.sbt**: Configure module dependencies
- [ ] **Create shared utilities**: Self-contained services in `shared/core`

### For Existing Projects:

- [ ] **Audit current structure**: Identify utilities vs business domains
- [ ] **Extract self-contained utilities**: Move simple helpers to single-file objects
- [ ] **Organize domains**: Group related models, services, repositories
- [ ] **Unify error handling**: Consolidate to domain-wide errors
- [ ] **Separate infrastructure**: Move DB, HTTP code to separate modules
- [ ] **Create `impl` bundles**: Pre-wire common configurations
- [ ] **Update documentation**: Reflect new structure in README

### Code Review Checklist:

- [ ] **Errors extend NoStackTrace** with descriptive messages
- [ ] **One error type per domain** (not per service)
- [ ] **Core modules have no infrastructure dependencies**
- [ ] **DTOs separate from domain models** (for API boundaries)
- [ ] **Internal implementations use `private[domain]`**
- [ ] **Utilities are self-contained** (< 500 lines)
- [ ] **Business domains use package structure**
- [ ] **Layers properly wired** in `impl` modules

---

## Summary: The Golden Rules

1. **Error Modeling**
   - ✅ One error type per domain
   - ✅ Include descriptive messages
   - ✅ Extend NoStackTrace

2. **Monorepo Structure**
   - ✅ Separate core from infrastructure
   - ✅ Use `impl` bundles for convenience
   - ✅ Public API in top-level, internals hidden

3. **Service vs Use Case**
   - ✅ Service Pattern as default
   - ✅ Use Case Pattern for complex domains
   - ✅ Separate DTOs from domain types

4. **Self-Contained vs Domain-Driven**
   - ✅ Self-Contained for utilities
   - ✅ Domain-Driven for business logic
   - ✅ Migrate when complexity grows

**When in doubt**: Follow these defaults for 80% of cases:
- Domain-wide errors extending NoStackTrace
- Domain-driven structure with core/impl separation
- Service Pattern with separate DTOs
- Self-contained only for simple utilities

---

## Additional Resources

- 📖 [Error Modeling Guide](./docs/ERROR_MODELING_GUIDE.md) - Comprehensive error design patterns
- 📖 [Monorepo Structure Guide](./docs/MONOREPO_PROJECT_STRUCTURE.md) - Module organization and dependencies
- 📖 [Service vs Use Case Comparison](./docs/WORKER_SERVICE_PATTERNS_COMPARISON.md) - Detailed pattern comparison
- 📖 [Self-Contained vs Domain-Driven Guide](./docs/SELF_CONTAINED_VS_DOMAIN_SERVICES.md) - When to use each approach

---

**Version**: 0.1
**Last Updated**: 2025-01-20
**Maintainer**: Development Team

This style guide is a living document. Feedback and improvements are welcome!