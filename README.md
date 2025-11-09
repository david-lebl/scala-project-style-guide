# Scala Project Style Guide

> **⚠️ Work in Progress**
> This style guide is actively being developed and refined. Examples and code snippets were generated with AI/LLM assistance and have been reviewed and modified by a human developer.

A comprehensive style guide for building scalable, maintainable Scala applications with ZIO.

---

## Table of Contents

1. [Error Modeling](#1-error-modeling)
2. [Monorepo Structure](#2-monorepo-structure)
3. [Service Pattern vs Use Case Pattern](#3-service-pattern-vs-use-case-pattern)
4. [Command/Event Handler Pattern](#4-commandevent-handler-pattern)
5. [Self-Contained vs Domain-Driven Services](#5-self-contained-vs-domain-driven-services)
6. [Architecture & Design Patterns](#6-architecture--design-patterns)
7. [Quick Reference](#7-quick-reference)
8. [Getting Started Checklist](#8-getting-started-checklist)

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

**▶️ Try it out:**
```bash
scala-cli run . --main-class examples.errormodeling.ErrorModelingExamples
```

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

📖 **[Full Service vs Use Case Comparison →](docs/SERVICE_PATTERNS_COMPARISON.md)**

**▶️ Try it out:**
```bash
scala-cli run . --main-class examples.servicepatterns.ServicePatternsComparison
```

---

## 4. Command/Event Handler Pattern

### Recommendation: For CQRS and Event Sourcing Architectures

**Use Command/Event Handler Pattern** when you need explicit command handling, event sourcing, or audit trails.

### Core Concepts:

The Handler Pattern adds an abstraction layer using:
- **Commands** (Input): Represent operations to perform (persistable, serializable)
- **Events** (Output): Represent what happened (immutable facts, past tense)
- **Handler**: Processes commands and produces events

### Handler Pattern with Service:

```scala
object WorkerService:
  // Events (Output)
  enum Event:
    case WorkerRegistered(id: WorkerId, registeredAt: Instant)
    case WorkerUnregistered(id: WorkerId, unregisteredAt: Instant)
    case HeartbeatRecorded(id: WorkerId, recordedAt: Instant)

  // Commands (Input) - GADT for type safety
  enum Command[+E]:
    case RegisterWorker(id: WorkerId, config: Map[String, String])
      extends Command[Event.WorkerRegistered]
    case UnregisterWorker(id: WorkerId)
      extends Command[Event.WorkerUnregistered]
    case RecordHeartbeat(id: WorkerId)
      extends Command[Event.HeartbeatRecorded]

  // Handler Trait
  trait Handler:
    def handle[E](command: Command[E]): IO[Error, E]

  // Implementation combines Service + Handler
  final class Live(deps: Dependencies) extends Service with Handler:
    def handle[E](command: Command[E]): IO[Error, E] =
      command match
        case Command.RegisterWorker(id, config) => registerWorker(id, config)
        case Command.UnregisterWorker(id) => unregisterWorker(id)
        case Command.RecordHeartbeat(id) => recordHeartbeat(id)

    def registerWorker(id: WorkerId, config: Map[String, String]): IO[Error, Event.WorkerRegistered] =
      for
        _ <- validateWorkerNotExists(id)
        now <- Clock.instant
        worker = Worker(id, config, Worker.Status.Pending, now)
        _ <- deps.workerStore.save(worker)
        event = Event.WorkerRegistered(id, now)
        _ <- deps.eventPublisher.publish(event)  // Publish event
      yield event
```

### Benefits:

**Commands:**
- ✅ Persistable and replayable
- ✅ Serializable (can cross service boundaries)
- ✅ Type-safe via GADTs
- ✅ Enables command sourcing

**Events:**
- ✅ Immutable audit trail
- ✅ Event sourcing support
- ✅ Downstream reactions (sagas, notifications)
- ✅ Complete history of state changes

**Handler:**
- ✅ Uniform command processing
- ✅ Middleware support (logging, metrics)
- ✅ Easy testing with command fixtures
- ✅ Decouples execution from definition

### Usage Example:

```scala
// Direct service method
val result = WorkerService.registerWorker("worker-1", Map("region" -> "us-east"))

// Via handler (for command sourcing, replay)
val command = WorkerService.Command.RegisterWorker("worker-1", Map("region" -> "us-east"))
val event = WorkerService.handle(command)

// Command replay for event sourcing
for
  commands <- commandLog.read
  events <- ZIO.foreach(commands)(WorkerService.handle)
yield events
```

### Three Pattern Variations:

**1. Service Pattern with Handler** (Hybrid):
- Traditional service methods + handler interface
- Good for gradual adoption
- Can use both styles

**2. Use Case Pattern with Handler** (Standalone):
- Each use case is a function
- Separate handler for routing
- Maximum flexibility

**3. Pure Handler Pattern** (Handler-first):
- Handler is primary interface
- Private business logic methods
- Fully event-driven

### When to Use:

**Use Command/Event Handler Pattern when:**
- ✅ Event sourcing or CQRS architecture
- ✅ Need complete audit trail
- ✅ Command replay for debugging/testing
- ✅ Saga orchestration with compensating transactions
- ✅ Commands/events cross service boundaries
- ✅ Regulatory compliance requirements
- ✅ Temporal decoupling (command now, effect later)

**Don't use when:**
- ❌ Simple CRUD operations
- ❌ Low complexity domains
- ❌ Performance-critical (extra abstraction overhead)
- ❌ Team unfamiliar with CQRS/Event Sourcing
- ❌ Rapid prototyping

### Best Practices:

1. **Use GADTs for type-safe commands**
   ```scala
   // ✅ Good: Type safety via GADT
   enum Command[+E]:
     case RegisterWorker(id: WorkerId) extends Command[Event.WorkerRegistered]
   ```

2. **Events should be immutable facts** (past tense)
   ```scala
   // ✅ Good: Past tense
   case class OrderCreated(orderId: OrderId, createdAt: Instant)

   // ❌ Bad: Present tense
   case class CreateOrder(orderId: OrderId)
   ```

3. **Commands should be serializable**
   ```scala
   // ✅ Good: Simple, serializable data
   case class RegisterWorker(id: String, config: Map[String, String])

   // ❌ Bad: Non-serializable callback
   case class RegisterWorker(id: String, callback: () => Unit)
   ```

4. **Keep handler logic thin** - delegate to service methods

5. **Publish events after state changes** - avoid inconsistency

### Comparison:

| Aspect | Service | Use Case | Handler |
|--------|---------|----------|---------|
| **Abstraction** | Method calls | Function calls | Command execution |
| **Input** | Parameters | Parameters/case classes | Command objects (GADT) |
| **Output** | Domain types | Domain types/DTOs | Event objects |
| **Persistability** | No | No | Yes (commands & events) |
| **Replay Support** | No | No | Yes |
| **Audit Trail** | Manual | Manual | Automatic (events) |
| **CQRS Fit** | Moderate | Good | Excellent |
| **Event Sourcing** | Poor | Moderate | Excellent |
| **Complexity** | Low | Low-Medium | Medium-High |

### Key Principles:

1. ✅ **Commands represent intent** - what the user wants to do
2. ✅ **Events represent facts** - what actually happened
3. ✅ **Use GADTs for type safety** - compile-time guarantees
4. ✅ **Handlers delegate to business logic** - keep them thin
5. ✅ **Events enable downstream reactions** - sagas, notifications
6. ✅ **Combine with Service or Use Case patterns** - not mutually exclusive

📖 **[Full Command/Event Handler Guide →](./docs/SERVICE_PATTERN_WITH_HANDLER.md)**

---

## 5. Self-Contained vs Domain-Driven Services

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

## 6. Architecture & Design Patterns

### Recommendation: Clean Architecture with Layer Separation

This guide provides a **concrete, detailed implementation** of Clean Architecture patterns for a single-module analysis feature, demonstrating how all the patterns from sections 1-5 come together in practice.

### What You'll Learn:

- **Layer 1: Core Domain Types** - Entity design with type safety, sealed trait error hierarchies, service interfaces, and orchestration use cases
- **Layer 2: Validated Domain Models** - Smart constructors with validation for impossible states
- **Layer 3: Domain-Specific Services** - Service wrappers that encapsulate dependencies and configuration
- **Layer 4: Infrastructure Implementations** - Adapters to external domains and database implementations

### Architecture Flow Example:

```
Application Layer
      ↓
Domain-Specific Service (basic/MdfAnalysisManager)
      ↓
Orchestration Use Case (AutomatedTraceReporting)
      ↓
Service Interfaces (TraceReporting, AnalysisStore, TraceStore)
      ↓
Infrastructure Implementations (Adapters, Repositories)
      ↓
External Dependencies (Database, Workflow Engine, File System)
```

### Key Patterns Covered:

- **Value Classes** (AnyVal) - Zero-overhead type-safe identifiers
- **Smart Constructors** - Validation at construction time with `Either`
- **Service Wrapper Pattern** - Clean API without ZIO environment leakage
- **Adapter Pattern** - Translation between domains and external systems
- **ZIO Layer Integration** - Dependency injection with `ZLayer.derive`

### When to Use:

✅ Complex business domains with multiple sub-features
✅ Need clear separation between core logic and infrastructure
✅ Multiple infrastructure implementations (DB, HTTP, Kafka)
✅ Team collaboration on feature modules
✅ Long-term maintainability requirements

📖 **[Full Architecture & Design Patterns Guide →](./docs/ARCHITECTURE.md)**

---

## 7. Quick Reference

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

## 8. Getting Started Checklist

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
   - ✅ Handler Pattern for CQRS/Event Sourcing
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

### Documentation

- 📖 [Error Modeling Guide](./docs/ERROR_MODELING_GUIDE.md) - Comprehensive error design patterns
- 📖 [Monorepo Structure Guide](./docs/MONOREPO_PROJECT_STRUCTURE.md) - Module organization and dependencies
- 📖 [Service vs Use Case Comparison](docs/SERVICE_PATTERNS_COMPARISON.md) - Detailed pattern comparison
- 📖 [Command/Event Handler Pattern](./docs/SERVICE_PATTERN_WITH_HANDLER.md) - CQRS and Event Sourcing guide
- 📖 [Self-Contained vs Domain-Driven Guide](./docs/SELF_CONTAINED_VS_DOMAIN_SERVICES.md) - When to use each approach
- 📖 [Architecture & Design Patterns Guide](./docs/ARCHITECTURE.md) - Clean Architecture implementation for single modules

### Multi-Module Examples (Mill)

- 🏗️ **[Worker Module](./examples-mill/worker/)** - Complete Use Case Pattern example with:
  - Worker lifecycle management (register, unregister, heartbeat)
  - Scheduled background jobs for state transitions
  - Package-private repositories with typed errors
  - REST API with ZIO HTTP
  - PostgreSQL + In-Memory implementations
  - **[Quick Start Guide](./examples-mill/QUICKSTART.md)** - Get running in 5 minutes!

---

**Version**: 0.2
**Last Updated**: 2025-01-25
**Maintainer**: Development Team

This style guide is a living document. Feedback and improvements are welcome!