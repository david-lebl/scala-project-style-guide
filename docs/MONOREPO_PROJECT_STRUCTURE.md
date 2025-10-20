# Monorepo Project Structure Guide

A comprehensive guide for organizing multi-domain Scala projects with ZIO in a monorepo.

---

## Table of Contents

1. [Module Organization Principles](#module-organization-principles)
2. [Recommended Module Structure](#recommended-module-structure)
3. [Domain Module Breakdown](#domain-module-breakdown)
4. [Package Organization within Modules](#package-organization-within-modules)
5. [Example: Worker & File Management Domains](#example-worker--file-management-domains)
6. [Dependency Rules](#dependency-rules)
7. [Build Configuration](#build-configuration)
8. [Cross-Domain Communication](#cross-domain-communication)

---

## Module Organization Principles

### Key Principles:

1. **Domain-Driven Organization**: Each domain is a separate module/subproject
2. **Dependency Inversion**: Core domain doesn't depend on infrastructure
3. **Explicit Contracts**: Public APIs in top-level packages, internals hidden
4. **Technology Isolation**: Infrastructure concerns separated by technology
5. **Deployment Flexibility**: Apps can mix-and-match implementations

### Module Naming Convention:

```
<domain>-<layer>

Examples:
- worker-core       (domain logic, no dependencies)
- worker-db         (database implementation)
- worker-http       (HTTP client/server)
- worker-app        (wiring/main application)
- worker-test       (integration tests)
```

---

## Recommended Module Structure

### For Simple Projects (1-2 Domains):

```
project-root/
├── build.sbt
├── modules/
│   ├── worker/                    # Single module per domain
│   │   └── src/main/scala/
│   │       ├── domain/            # Public API (models, services, use cases)
│   │       ├── internal/          # Private implementation
│   │       └── infrastructure/    # Tech-specific (db, http, etc.)
│   └── files/
│       └── src/main/scala/
│           ├── domain/
│           ├── internal/
│           └── infrastructure/
└── apps/
    └── server/                    # Main application
        └── src/main/scala/
```

### For Complex Projects (3+ Domains, Multiple Implementations):

**Numbering Convention:**
- `01-*` = Domain definition & pure implementation (core, models, errors)
- `02-*` = Infrastructure implementations (db, http-server, http-client, s3, kafka)
- `03-*` = Wiring & testing (impl bundles, integration tests)
- `04-*` = Applications (optional)

**Note:** Prefixes are ONLY in folder paths for organization. Module names in `build.sbt` remain unchanged.

```
project-root/
├── build.sbt
├── modules/
│   ├── worker/
│   │   ├── 01-core/               # Domain logic only
│   │   │   └── src/main/scala/
│   │   │       └── com.mycompany.worker/
│   │   │           ├── model/     # Domain models (public)
│   │   │           ├── service/   # Service interfaces (public)
│   │   │           ├── usecase/   # Use case interfaces (public)
│   │   │           ├── repository/# Repository interfaces (public)
│   │   │           ├── error/     # Domain errors (public)
│   │   │           └── internal/  # Private domain logic
│   │   ├── 02-db/                 # Database implementation
│   │   │   └── src/main/scala/
│   │   │       └── com.mycompany.worker.db/
│   │   │           ├── PostgresWorkerRepository.scala
│   │   │           └── migrations/
│   │   ├── 02-http-server/        # HTTP REST API
│   │   │   └── src/main/scala/
│   │   │       └── com.mycompany.worker.http/
│   │   │           ├── routes/
│   │   │           ├── dto/       # DTOs for HTTP
│   │   │           └── controllers/
│   │   ├── 02-http-client/        # HTTP client (if calling external APIs)
│   │   │   └── src/main/scala/
│   │   │       └── com.mycompany.worker.httpclient/
│   │   ├── 03-impl/               # Common/default implementation bundle
│   │   │   └── src/main/scala/
│   │   │       └── com.mycompany.worker.impl/
│   │   │           └── WorkerLayers.scala  # Pre-wired layers
│   │   └── 03-it/                 # Integration tests
│   │       └── src/test/scala/
│   ├── files/
│   │   ├── 01-core/
│   │   ├── 02-db/
│   │   ├── 02-s3/                 # S3 storage implementation
│   │   ├── 02-local-fs/           # Local filesystem implementation
│   │   ├── 02-http-server/
│   │   ├── 03-impl/
│   │   └── 03-it/
│   └── shared/                    # Shared utilities
│       └── 01-core/
│           └── src/main/scala/
│               └── com.mycompany.shared/
│                   ├── types/     # Common types (Email, UserId, etc.)
│                   └── util/
└── apps/
    ├── 04-server/                 # Main REST API server (optional prefix)
    │   └── src/main/scala/
    │       └── com.mycompany.app/
    │           └── Main.scala
    ├── 04-worker-daemon/          # Background worker daemon (optional prefix)
    │   └── src/main/scala/
    ├── 04-cli/                    # CLI tool (optional prefix)
    │   └── src/main/scala/
    └── 04-examples/               # Example apps (optional prefix)
        └── src/main/scala/
```

---

## Domain Module Breakdown

### `<domain>-core` Module

**Purpose**: Pure domain logic, no infrastructure dependencies

**Contents**:
```scala
// Public API (top-level package)
package com.mycompany.worker

// Models
case class Worker(id: Worker.Id, status: Worker.Status, ...)
case class Heartbeat(...)

// Service/Use Case Interfaces
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]

object WorkerUseCases:
  def registerWorker(...): ZIO[WorkerRepository, WorkerError, Worker] = ???

// Repository Interfaces (Ports)
trait WorkerRepository:
  def save(worker: Worker): IO[WorkerError, Unit]
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]

// Errors
sealed trait WorkerError
case class WorkerNotFound(id: Worker.Id) extends WorkerError

// Internal implementation (hidden)
package com.mycompany.worker.internal

final case class WorkerServiceLive(repo: WorkerRepository) extends WorkerService:
  // Implementation details
```

**Dependencies**:
- ZIO core only
- Shared utilities (if any)
- NO infrastructure dependencies

---

### `<domain>-db` Module

**Purpose**: Database-specific implementation

**Contents**:
```scala
package com.mycompany.worker.db

import com.mycompany.worker.{Worker, WorkerRepository, WorkerError}

// Public implementation
final class PostgresWorkerRepository(
  dataSource: DataSource
) extends WorkerRepository:
  override def save(worker: Worker): IO[WorkerError, Unit] = ???
  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] = ???

// Layer factory
object PostgresWorkerRepository:
  def layer: ZLayer[DataSource, Nothing, WorkerRepository] =
    ZLayer.fromFunction(new PostgresWorkerRepository(_))
```

**Dependencies**:
- `worker-core` module
- Database libraries (Quill, Doobie, etc.)
- Database drivers

---

### `<domain>-http-server` Module

**Purpose**: REST API endpoints for the domain

**Contents**:
```scala
package com.mycompany.worker.http

import com.mycompany.worker.{WorkerService, Worker, WorkerError}

// DTOs (not domain models!)
case class RegisterWorkerRequest(id: String, config: Map[String, String]) derives JsonCodec
case class RegisterWorkerResponse(id: String, status: String) derives JsonCodec

// Routes
object WorkerRoutes:
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

**Dependencies**:
- `worker-core` module
- HTTP library (zio-http, http4s, etc.)
- JSON library (zio-json, circe, etc.)

---

### `<domain>-http-client` Module

**Purpose**: HTTP client for calling external APIs

**Contents**:
```scala
package com.mycompany.worker.httpclient

// Implementation of interfaces that call external services
final class ExternalWorkerApiClient(client: Client) extends ExternalWorkerApi:
  def fetchWorkerStatus(id: String): IO[ApiError, WorkerStatus] = ???

object ExternalWorkerApiClient:
  def layer: ZLayer[Client, Nothing, ExternalWorkerApi] = ???
```

**Dependencies**:
- `worker-core` module (for interfaces)
- HTTP client library

---

### `<domain>-impl` Module

**Purpose**: Pre-configured bundle of implementations for easy consumption

**Contents**:
```scala
package com.mycompany.worker.impl

import com.mycompany.worker._
import com.mycompany.worker.db._
import com.mycompany.worker.http._

// Pre-wired layers for common configurations
object WorkerLayers:

  // Production configuration (Postgres + HTTP)
  val production: ZLayer[DataSource & Client, Nothing, WorkerService] =
    PostgresWorkerRepository.layer >+>
    WorkerService.layer

  // In-memory configuration (for testing/examples)
  val inMemory: ZLayer[Any, Nothing, WorkerService] =
    InMemoryWorkerRepository.layer >+>
    WorkerService.layer

  // All HTTP routes
  val httpRoutes: Routes[WorkerService, Nothing] =
    WorkerRoutes.routes
```

**Dependencies**:
- `worker-core`
- `worker-db`
- `worker-http-server`
- Other implementation modules

**Benefits**:
- Easy to use in apps: just import `WorkerLayers.production`
- Reduces boilerplate in application code
- Provides sensible defaults

---

### `<domain>-test` Module

**Purpose**: Integration tests for the domain

**Contents**:
```scala
package com.mycompany.worker.test

class WorkerServiceIntegrationSpec extends ZIOSpecDefault:
  def spec = suite("WorkerService Integration")(
    test("register and retrieve worker") {
      for
        worker <- WorkerService.register("worker-1", Map.empty)
        retrieved <- WorkerService.getWorker(worker.id)
      yield assertTrue(retrieved == worker)
    }
  ).provide(
    WorkerLayers.inMemory  // Use pre-wired in-memory implementation
  )
```

**Dependencies**:
- `worker-core`
- `worker-impl` (for test fixtures)
- Test libraries (ZIO Test, ScalaTest, etc.)

---

## Package Organization within Modules

### Top-Level Package: Public API

```scala
package com.mycompany.worker

// ✅ Public - exposed to other modules/domains
case class Worker(...)
trait WorkerService
trait WorkerRepository
sealed trait WorkerError
```

### Internal Package: Private Implementation

```scala
package com.mycompany.worker.internal

// ❌ Private - NOT exposed to other modules
private[worker] final class WorkerServiceLive extends WorkerService
private[worker] object WorkerValidation
private[worker] case class WorkerState(...)
```

### Benefits:

- **Clear API boundaries**: Only top-level package is public
- **Refactoring freedom**: Internal code can change without breaking consumers
- **Prevents coupling**: Other modules can't depend on internal details

---

## Example: Worker & File Management Domains

### Directory Structure:

```
monorepo/
├── build.sbt
├── modules/
│   ├── worker/
│   │   ├── 01-core/src/main/scala/com/mycompany/worker/
│   │   │   ├── Worker.scala              # Domain model
│   │   │   ├── WorkerService.scala       # Service interface
│   │   │   ├── WorkerRepository.scala    # Repository interface
│   │   │   ├── WorkerError.scala         # Domain errors
│   │   │   └── internal/
│   │   │       └── WorkerServiceLive.scala
│   │   ├── 02-db/src/main/scala/com/mycompany/worker/db/
│   │   │   └── PostgresWorkerRepository.scala
│   │   ├── 02-http-server/src/main/scala/com/mycompany/worker/http/
│   │   │   ├── dto/
│   │   │   │   ├── WorkerRequest.scala
│   │   │   │   └── WorkerResponse.scala
│   │   │   └── WorkerRoutes.scala
│   │   ├── 03-impl/src/main/scala/com/mycompany/worker/impl/
│   │   │   └── WorkerLayers.scala
│   │   └── 03-it/src/test/scala/com/mycompany/worker/it/
│   │       └── WorkerIntegrationSpec.scala
│   │
│   └── files/
│       ├── 01-core/src/main/scala/com/mycompany/files/
│       │   ├── File.scala
│       │   ├── FileService.scala
│       │   ├── FileRepository.scala
│       │   ├── StorageProvider.scala     # Interface for different storage backends
│       │   └── internal/
│       ├── 02-db/                        # File metadata in DB
│       ├── 02-s3/                        # S3 storage implementation
│       ├── 02-local-fs/                  # Local filesystem implementation
│       ├── 02-http-server/
│       ├── 03-impl/
│       └── 03-it/
│
└── apps/
    ├── 04-server/src/main/scala/com/mycompany/app/
    │   └── Main.scala
    └── 04-worker-daemon/src/main/scala/com/mycompany/daemon/
        └── WorkerDaemon.scala
```

---

## Dependency Rules

### Allowed Dependencies:

```
┌─────────────┐
│    app      │  ← Can depend on everything
└──────┬──────┘
       │
┌──────▼──────┐
│    impl     │  ← Can depend on core + infrastructure modules
└──────┬──────┘
       │
┌──────▼───────┬──────────┬──────────┐
│     db       │   http   │  other   │  ← Can depend only on core
└──────────────┴──────────┴──────────┘
       │
┌──────▼──────┐
│    core     │  ← NO infrastructure dependencies
└─────────────┘
```

### Dependency Table:

| Module Type | Can Depend On | Cannot Depend On |
|-------------|---------------|------------------|
| **core** | Shared utils, ZIO | db, http, any infrastructure |
| **db** | core, DB libraries | http-server, http-client, other infrastructure |
| **http-server** | core, HTTP libraries | db, http-client |
| **http-client** | core, HTTP client libs | db, http-server |
| **impl** | core, all infrastructure modules | app |
| **app** | impl, core, any module | Nothing (top level) |
| **test** | All modules | Nothing |

### Cross-Domain Dependencies:

- ✅ Allowed: `files-core` → `worker-core` (domain depends on domain)
- ✅ Allowed: `app` → `worker-impl` + `files-impl`
- ❌ Forbidden: `worker-core` → `files-db` (domain → infrastructure)
- ❌ Forbidden: `worker-db` → `files-db` (infrastructure cross-talk)

---

## Build Configuration

### `build.sbt` Structure:

```scala
// Project settings
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.1"
ThisBuild / organization := "com.mycompany"

// Common settings
lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature"),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % "2.1.22"
  )
)

// Shared module
lazy val shared = project
  .in(file("modules/shared/01-core"))  // Folder path with prefix
  .settings(
    name := "shared-core",  // Module name WITHOUT prefix
    commonSettings
  )

// Worker domain modules
lazy val workerCore = project
  .in(file("modules/worker/01-core"))  // Folder path with prefix
  .settings(
    name := "worker-core",  // Module name WITHOUT prefix
    commonSettings
  )
  .dependsOn(shared)

lazy val workerDb = project
  .in(file("modules/worker/02-db"))  // Folder path with prefix
  .settings(
    name := "worker-db",  // Module name WITHOUT prefix
    commonSettings,
    libraryDependencies ++= Seq(
      "io.getquill" %% "quill-jdbc-zio" % "4.8.0",
      "org.postgresql" % "postgresql" % "42.7.0"
    )
  )
  .dependsOn(workerCore)

lazy val workerHttpServer = project
  .in(file("modules/worker/02-http-server"))  // Folder path with prefix
  .settings(
    name := "worker-http-server",  // Module name WITHOUT prefix
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % "3.0.1",
      "dev.zio" %% "zio-json" % "0.7.3"
    )
  )
  .dependsOn(workerCore)

lazy val workerImpl = project
  .in(file("modules/worker/03-impl"))  // Folder path with prefix
  .settings(
    name := "worker-impl",  // Module name WITHOUT prefix
    commonSettings
  )
  .dependsOn(workerCore, workerDb, workerHttpServer)

lazy val workerIt = project
  .in(file("modules/worker/03-it"))  // Folder path with prefix
  .settings(
    name := "worker-it",  // Module name WITHOUT prefix
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % "2.1.22" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.22" % Test
    )
  )
  .dependsOn(workerCore, workerImpl % Test)

// Files domain modules (similar structure)
lazy val filesCore = project.in(file("modules/files/01-core")).settings(name := "files-core", commonSettings).dependsOn(shared)
lazy val filesDb = project.in(file("modules/files/02-db")).settings(name := "files-db", commonSettings).dependsOn(filesCore)
lazy val filesS3 = project.in(file("modules/files/02-s3")).settings(name := "files-s3", commonSettings).dependsOn(filesCore)
lazy val filesHttpServer = project.in(file("modules/files/02-http-server")).settings(name := "files-http-server", commonSettings).dependsOn(filesCore)
lazy val filesImpl = project.in(file("modules/files/03-impl")).settings(name := "files-impl", commonSettings).dependsOn(filesCore, filesDb, filesS3, filesHttpServer)

// Application
lazy val server = project
  .in(file("apps/04-server"))  // Folder path with optional prefix
  .settings(
    name := "server",  // Module name WITHOUT prefix
    commonSettings,
    mainClass := Some("com.mycompany.app.Main")
  )
  .dependsOn(workerImpl, filesImpl)
  .enablePlugins(JavaAppPackaging)  // For deployment

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "mycompany-monorepo",
    publish / skip := true
  )
  .aggregate(
    shared,
    workerCore, workerDb, workerHttpServer, workerImpl, workerIt,
    filesCore, filesDb, filesS3, filesHttpServer, filesImpl,
    server
  )
```

---

## Cross-Domain Communication

### When Worker Domain Needs File Domain:

#### Option 1: Domain Dependency (Recommended)

```scala
// files-core module
package com.mycompany.files

trait FileService:
  def uploadFile(name: String, content: Array[Byte]): IO[FileError, File]

// worker-core module (depends on files-core)
package com.mycompany.worker

case class WorkerServiceLive(
  workerRepo: WorkerRepository,
  fileService: FileService  // Depend on files domain
) extends WorkerService:

  def registerWithDocument(id: String, doc: Array[Byte]): IO[WorkerError, Worker] =
    for
      file <- fileService.uploadFile(s"worker-$id-doc", doc)
        .mapError(e => WorkerError.FileUploadFailed(e))
      worker <- register(id, Map("documentId" -> file.id.value))
    yield worker
```

#### Option 2: Event-Based (Decoupled)

```scala
// shared module
package com.mycompany.shared.events

sealed trait DomainEvent
case class WorkerRegistered(workerId: String, timestamp: Instant) extends DomainEvent

// worker-core publishes events
case class WorkerServiceLive(
  workerRepo: WorkerRepository,
  eventBus: EventBus[DomainEvent]
) extends WorkerService:

  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker] =
    for
      worker <- workerRepo.save(Worker.create(id, config))
      _ <- eventBus.publish(WorkerRegistered(id, Instant.now()))
    yield worker

// files-core subscribes to events
case class FileCleanupService(
  fileService: FileService,
  eventBus: EventBus[DomainEvent]
):
  def start: ZIO[Scope, Nothing, Unit] =
    eventBus.subscribe {
      case WorkerRegistered(workerId, _) =>
        // React to worker registration
        fileService.createWorkerDirectory(workerId).ignore
      case _ => ZIO.unit
    }
```

---

## Summary: Best Practices

1. ✅ **Separate `core` from infrastructure** - keeps domain pure
2. ✅ **Use `impl` modules** - provides convenient pre-wired bundles
3. ✅ **Public API in top-level package** - clear boundaries
4. ✅ **Hide internals in `internal` package** - encapsulation
5. ✅ **One module per technology** - db, http-server, http-client, s3, etc.
6. ✅ **Cross-domain via core dependencies** - not infrastructure
7. ✅ **Test modules use `impl`** - easy setup for integration tests
8. ✅ **Apps depend on `impl` modules** - simplified wiring

This structure scales from small projects to large multi-domain systems!