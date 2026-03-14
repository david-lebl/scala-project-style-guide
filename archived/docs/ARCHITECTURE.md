# Architecture & Design Patterns Guide - Single Module

## Overview

This document describes the architecture, design patterns, and structural conventions used for a single Analysis feature module example. The module demonstrates Clean Architecture principles with clear separation between core domain logic and infrastructure implementations.

## Module Structure

```
modules/analysis/
├── 01-c-core/          # Core domain layer (interfaces, entities, use cases)
│   └── src/main/scala/com/carpdap/mdm/usecase/analysis/
│       ├── *.scala                    # Root: Core entities, errors, traits
│       ├── basic/                     # Sub-package: Validated domain models & services
│       ├── fullsignal/                # Sub-package: Full signal analysis domain
│       ├── carreport/                 # Sub-package: Car report domain
│       └── ...
│
└── 02-c-impl/          # Infrastructure layer (adapters, implementations)
    └── src/main/scala/com/carpdap/mdm/usecase/analysis/
        ├── adapter/                   # Adapters to external domains
        ├── db/                        # Database implementations
        └── ...
```

**Note:** In larger projects, sub-packages like `basic/` should be separated into their own modules (e.g., `03-c-basic`). For small cases, keeping them in core is acceptable.

---

## Design Patterns & Layers

### Layer 1: Core Domain Types (Root Package)

#### 1.1 Entity Design with Type Safety

**Pattern:** Value classes for type-safe identifiers using `AnyVal`

```scala
// Entity with nested companion object containing types
case class Analysis(
  id: Analysis.Id,
  manager: Analysis.ManagerCode,
  config: Analysis.Config
)

object Analysis {
  // Zero-overhead type wrapper (AnyVal)
  case class Id(value: String) extends AnyVal
  case class ManagerCode(value: String) extends AnyVal

  // Nested types for namespacing
  case class Config(id: Config.Id)
  object Config {
    case class Id(value: String) extends AnyVal
  }
}
```

**Benefits:**
- Type safety: Can't accidentally pass `Analysis.Id` where `Trace.Id` is expected
- Zero runtime overhead (AnyVal)
- Clear namespace organization
- IDE auto-completion support

**Examples:** `Analysis.scala`, `File.scala`, `Trace.scala`, `Car.scala`

#### 1.2 Error Design - Sealed Trait Hierarchy

**Pattern:** ADT (Algebraic Data Types) for domain errors without stack traces

```scala
// Base error trait
sealed trait Error extends NoStackTrace {
  def message: String
  override def getMessage: String = message
}

// Category-specific error types
sealed trait AnalysisError extends Error
object AnalysisError {
  case class NotApplicable(entity: String) extends AnalysisError {
    override def message: String = s"No applicable analysis found for: $entity."
  }
}

sealed trait TraceMetaError extends Error
object TraceMetaError {
  case class TraceNotFount(id: Trace.Id) extends TraceMetaError {
    override def message: String = s"Trace $id not found!"
  }

  case class MissingCarDetail(traceId: Trace.Id, detailName: String) extends TraceMetaError {
    override def message: String = s"Trace $traceId is missing $detailName."
  }
}

sealed trait WorkflowError extends Error
sealed trait FileError extends Error
```

**Benefits:**
- Exhaustive pattern matching (compiler checks)
- No stack trace overhead (NoStackTrace)
- Type-safe error channels in ZIO: `IO[AnalysisError, Result]`
- With build in messages, keeping the main logic clean
- Easy to use with another domain, automatic error widening to `Throwable`
- But do not force user to handle the error using `.catchAll` or `.mapError`, resulting in unintentional automated error widening to `Throwable` which will be handled somewhere up

**Location:** `Error.scala`

#### 1.3 Services (Stores)

**Pattern:** Port interfaces using `@accessible` trait with ZIO

```scala
@accessible
private[analysis] trait AnalysisStore {
  def findAllAnalysisBy(
    vehicle: Vehicle.Id,
    model: Model.Id,
    architecture: Architecture.Id
  ): IO[AnalysisError, List[Analysis]]
}

@accessible
private[analysis] trait TraceStore {
  def findById(traceId: Trace.Id): IO[TraceMetaError, Trace]
}
```

**Benefits:**
- `@accessible` generates ZIO accessor methods automatically
- Clear abstraction boundary
- `private[analysis]` - encapsulation within module
- Dependency inversion (depends on abstraction, not implementation)
- Easy to mock for testing

**Examples:** `AnalysisStore.scala`, `TraceStore.scala`, `FileStore.scala`

#### 1.4 Services (operations)

**Pattern:** Service trait defining business operations, executed by infrastructure

```scala
trait TraceReporting {
  def create(
    analysis: Analysis,
    trace: Trace,
    inputData: InputData,
    attachedParams: AttachedParams
  ): IO[Error, TraceReporting.CreatedAnalysis]
}

object TraceReporting {
  // ZIO accessor for environment-based access
  def create(
    analysis: Analysis,
    trace: Trace,
    inputData: InputData,
    attachedParams: AttachedParams
  ): ZIO[TraceReporting, Error, CreatedAnalysis] =
    ZIO.serviceWithZIO[TraceReporting](_.create(analysis, trace, inputData, attachedParams))

  // Return types in companion object
  case class CreatedAnalysis(
    executionId: Long,
    estimatedFinishAt: Option[OffsetDateTime] = None,
    terminalTaskIds: List[Long] = Nil
  )
}
```

**Benefits:**
- Single Responsibility Principle
- Service interface separate from implementation
- Associated types in companion object

#### 1.5 Orchestration Use Cases (Object or Classes)

**Pattern:** Configurable class for complex orchestration logic

```scala
class AutomatedTraceReporting(val managerCode: String) {
  import AutomatedTraceReporting._

  def createAll(
    traceId: PackageId,
    inputData: InputData = InputData.NoData
  ): ZIO[TraceReporting with AnalysisStore with TraceStore, Error, BatchAnalysisResult] = {
    // Complex orchestration logic
    // - Query multiple stores
    // - Filter and transform
    // - Execute batch operations
    // - Aggregate results
  }
}

object AutomatedTraceReporting {
  // Factory method
  def apply(managerCode: String): AutomatedTraceReporting =
    new AutomatedTraceReporting(managerCode)

  // Shared types
  case class SubmittedAnalysis(...)
  case class BatchAnalysisResult(...)
}
```

**When to use:**
- Complex multi-step workflows
- Configurable behavior (via constructor parameters)
- Reusable orchestration logic
- Requires ZIO environment dependencies

---

### Layer 2: Validated Domain Models (Sub-package `basic/`)

**Pattern:** Smart constructors with validation

```scala
// Validated model with private constructor
case class MdfFile private (file: File)

object MdfFile {
  private val regexMDF = ".*\\.(MDF|MF4)$".r

  // Smart constructor returns Either for validation
  def apply(file: File): Either[FileError.InvalidMdfFile, MdfFile] =
    regexMDF
      .findFirstIn(file.metadata.fullName.value)
      .map(_ => new MdfFile(file))
      .toRight(FileError.InvalidMdfFile(file.id))
}

// Domain-specific composite model
case class MdfAnalysis(
  analysis: Analysis,
  traceMeta: Trace,
  mdfFile: MdfFile
)
```

**Benefits:**
- Validation at construction time
- Invalid states unrepresentable
- Type safety beyond primitives
- Domain-specific constraints encoded in types
- 
---

### Layer 3: Domain-Specific Services (Sub-package `basic/`)

**Pattern:** Service wrapper that encapsulates dependencies and configuration

```scala
final class MdfAnalysisManagerV2(
  traceReporting: TraceReporting,
  analysisStore: AnalysisStore,
  traceStore: TraceStore,
  fileStore: FileStore
) {

  val managerCode: String = MdfAnalysisManagerV2.managerCode

  // Instantiate orchestrator with configuration
  private val manager = AutomatedTraceReporting(managerCode)

  // Public API with clean signature (no ZIO environment leak)
  def createAutomatedAnalysis(
    traceId: PackageId,
    inputFile: FileId
  ): IO[Error, BatchAnalysisResult] = {
    (for {
      // 1. Validate input (MDF file)
      mdfFile <- FileStore
                   .findLocationById(File.Id(inputFile))
                   .map(MdfFile.apply)
                   .flatMap(ZIO.fromEither(_))

      // 2. Delegate to orchestrator
      result  <- manager.createAll(traceId, InputData.FileMeta(mdfFile.file))
    } yield result)
      // 3. Provide dependencies internally
      .provideEnvironment(
        ZEnvironment(traceReporting).add(fileStore).add(traceStore).add(analysisStore)
      )
  }
}

object MdfAnalysisManagerV2 {
  // ZLayer for dependency injection
  val layer = ZLayer.derive[MdfAnalysisManagerV2]

  // Domain configuration
  val managerCode: String = "AUTOMATED_MDF_TRACE_REPORTING"
}
```

**Architecture Benefits:**

1. **Dependency Encapsulation**
   - Consumers depend on `MdfAnalysisManagerV2`, not 4 individual services
   - Clean API: `IO[Error, Result]` instead of `ZIO[Env1 with Env2 with Env3, Error, Result]`

2. **Single Responsibility**
   - MDF-specific validation (`MdfFile`)
   - MDF-specific configuration (`managerCode`)
   - Reuses generic orchestration (`AutomatedTraceReporting`)

3. **ZIO Layer Integration**
   - `ZLayer.derive` for automatic DI wiring
   - Easy composition in application setup

**Examples:** `MdfAnalysisManagerV2.scala`, `RawTraceReportingManager.scala`, `CarReportManagerV2.scala`

---

### Layer 4: Infrastructure Implementations (Module `02-c-impl`)


#### 4.1 Adapters to External Domains

**Pattern:** Adapter that translates between domains

```scala
// Adapter implementing core interface
final class AnalysisStoreAdapter(
  processRepo: ProcessRepo[CrudIO],  // External domain dependency
  ds: DataSource
) extends AnalysisStore {

  override def findAllAnalysisBy(
    vehicle: Vehicle.Id,
    model: Model.Id,
    architecture: Architecture.Id
  ): IO[AnalysisError, List[Analysis]] = {
    processRepo
      // Call external domain with translated types
      .findAllAutomatedProcessesBy(
        architecture = ArchitectureCode(architecture.value),
        project = ProjectCode(model.value),
        vin = vehicle.value
      )
      .orDie
      // Map external domain types to analysis domain types
      .map(rows =>
        rows.map(item =>
          Analysis(
            id = Analysis.Id(item.code),
            manager = Analysis.ManagerCode(item.instanceManager.getOrElse("")),
            config = Analysis.Config(Analysis.Config.Id("INIT"))
          )
        )
      )
      .provideEnvironment(ZEnvironment(ds) ++ UserService.systemEnv)
  }
}

object AnalysisStoreAdapter {
  val layer = ZLayer.derive[AnalysisStoreAdapter]
}
```

**Benefits:**
- Isolates external domain dependencies
- Clean translation layer
- Core domain remains pure
- Easy to swap implementations

#### 4.2 Adapters to Framework/Library - Database Implementations

**Pattern:** Repository implementation using Quill/SQL

```scala
final class QuillTraceStore(ds: DataSource) extends TraceStore {
  import com.carpdap.mdm.infra.rdb.QuillContext._

  override def findById(traceId: Trace.Id): IO[TraceMetaError, Trace] = {
    queryTraceWithCarDetail(PackageId(traceId.value))
      .orDie
      .map(_.map(_.toDomain).headOption)
      .someOrFail(TraceMetaError.TraceNotFount(traceId))
      .flatMap(ZIO.fromEither(_))
      .provideEnvironment(ZEnvironment(ds))
  }

  private[db] def queryTraceWithCarDetail(traceId: PackageId) = run {
    for {
      trace <- Tables.dataPackage_active
                 .filter(_.packageType == "TRACE")
                 .filter(_.packageId == lift(traceId.value))
      model <- Tables.queryModel.leftJoin(...)
      arch  <- Tables.queryArchitecture.leftJoin(...)
    } yield TraceCarDetailDao(trace, model, arch)
  }
}

object QuillTraceStore {
  val layer = ZLayer.derive[QuillTraceStore]
}
```

---

## Complete Architecture Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ Application Layer (e.g., ServerCommandProcessor)               │
│ - Depends on: RawTraceReportingManager                         │
│ - Single clean dependency                                      │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│ Layer 3: Domain-Specific Service (basic/RawTraceReporting...)  │
│ - Validates domain-specific inputs (MdfFile)                   │
│ - Encapsulates configuration (managerCode)                     │
│ - Provides dependencies to orchestrator                        │
│ - Clean API: IO[Error, Result]                                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│ Layer 1: Orchestration Use Case (AutomatedTraceReporting)      │
│ - Configurable class (managerCode)                             │
│ - Complex multi-step workflow                                  │
│ - Depends on: TraceReporting, AnalysisStore, TraceStore        │
│ - Returns: ZIO[Env, Error, Result]                             │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┬──────────────┐
              ▼                             ▼              ▼
┌─────────────────────┐  ┌──────────────────────┐  ┌─────────────┐
│ TraceReporting      │  │ AnalysisStore        │  │ TraceStore  │
│ (trait)             │  │ (trait)              │  │ (trait)     │
└─────────┬───────────┘  └──────────┬───────────┘  └──────┬──────┘
          │                         │                     │
          ▼                         ▼                     ▼
┌─────────────────────┐  ┌──────────────────────┐  ┌─────────────┐
│ Layer 4:            │  │ Layer 4:             │  │ Layer 4:    │
│ TraceReporting      │  │ AnalysisStore        │  │ QuillTrace  │
│ WorkflowAdapter     │  │ Adapter              │  │ Store       │
│ (impl)              │  │ (impl)               │  │ (impl)      │
└─────────────────────┘  └──────────────────────┘  └─────────────┘
          │                         │                     │
          ▼                         ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ External Dependencies                                           │
│ - Workflow Engine (JobManager)                                  │
│ - Database (ProcessRepo, Tables)                                │
│ - File System (WorkspaceLocator)                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Design Principles

### 1. **Dependency Inversion**
- Core depends on abstractions (traits)
- Infrastructure depends on core and implements traits
- No core dependency on infrastructure

### 2. **Type-Driven Design**
- Value classes (AnyVal) for type safety
- Sealed traits for exhaustive pattern matching
- Smart constructors for validation
- Impossible states made unrepresentable

### 3. **Error as Values**
- Sealed trait hierarchy for errors
- Type-safe error channels: `IO[DomainError, Result]`
- NoStackTrace for performance
- Clear error categories

### 4. **Service Wrapper Pattern**
```scala
// Instead of:
class Consumer(dep1: A, dep2: B, dep3: C, dep4: D) {
  def doWork() = orchestrator.run(...)
    .provideEnvironment(ZEnvironment(dep1, dep2, dep3, dep4))
}

// Use:
class DomainService(dep1: A, dep2: B, dep3: C, dep4: D) {
  def doWork(): IO[Error, Result] = {
    orchestrator.run(...)
      .provideEnvironment(ZEnvironment(dep1, dep2, dep3, dep4))
  }
}

class Consumer(domainService: DomainService) {
  def doWork() = domainService.doWork()
}
```

### 5. **ZIO Integration**
- `@accessible` for automatic accessor generation
- `ZLayer.derive` for DI composition
- Environment-based dependency management
- Effectful operations as first-class values

### 6. **Package Organization**
- Root package: Core types, errors, interfaces
- Sub-packages: Specific domains (basic, fullsignal, carreport)
- Implementation module: Infrastructure code
- adapter/: Cross-domain translations
- db/: Database implementations

---

## When to Extract Sub-Package to Module

**Keep in core when:**
- Small codebase (< 10 files)
- Tight coupling with core
- Shared by multiple features

**Extract to separate module when:**
- Large domain (> 15 files)
- Independent evolution
- Optional feature
- Different deployment cadence
- Separate team ownership

**Example structure for large projects:**
```
modules/
├── analysis-core/       # Core domain
├── analysis-basic/      # Basic analysis domain
├── analysis-fullsignal/ # Full signal analysis
├── analysis-impl/       # Infrastructure
```

---

## Common Patterns Summary

| Pattern | Location | Purpose |
|---------|----------|---------|
| **Value Classes** | Entities | Type-safe IDs with zero overhead |
| **Sealed Traits** | Error.scala | Exhaustive error handling |
| **Smart Constructors** | basic/model.scala | Validation at construction |
| **Repository Trait** | *Store.scala | Dependency inversion |
| **Service Trait** | TraceReporting.scala | Use case abstraction |
| **Orchestrator Class** | AutomatedTraceReporting.scala | Configurable workflows |
| **Service Wrapper** | basic/*Manager.scala | Dependency encapsulation |
| **Adapter** | adapter/*.scala | Cross-domain translation |
| **ZLayer** | All objects | Dependency injection |

---

## Best Practices

1. **Always use value classes for IDs** - prevents mixing up different ID types
2. **Seal error hierarchies** - enables exhaustive pattern matching
3. **Keep core pure** - no infrastructure dependencies in core
4. **Use smart constructors for validation** - invalid states become unrepresentable
5. **Wrap complex dependencies** - single service dependency > multiple low-level dependencies
6. **Leverage ZIO environment** - don't pass dependencies explicitly
7. **Document trade-offs** - use FIXME/TODO for known issues
8. **Prefer composition over inheritance** - use traits for interfaces only

---

## Testing Strategy

```scala
// Mock repository for testing
class MockAnalysisStore extends AnalysisStore {
  override def findAllAnalysisBy(...) =
    ZIO.succeed(List(/* test data */))
}

// Test with ZIO TestAspect
suite("MdfAnalysisManagerV2") {
  test("creates analysis for valid MDF file") {
    for {
      manager <- ZIO.service[MdfAnalysisManagerV2]
      result  <- manager.createAutomatedAnalysis(traceId, fileId)
    } yield assertTrue(result.successful.nonEmpty)
  }.provide(
    MdfAnalysisManagerV2.layer,
    MockAnalysisStore.layer,
    MockTraceStore.layer,
    MockFileStore.layer,
    MockTraceReporting.layer
  )
}
```

---

## Conclusion

This architecture demonstrates:
- **Clean separation** of concerns across layers
- **Type safety** through value classes and ADTs
- **Testability** through dependency inversion
- **Maintainability** through clear patterns
- **Scalability** through modular design

The patterns shown here scale from small features to large enterprise systems by extracting sub-packages into modules when appropriate.