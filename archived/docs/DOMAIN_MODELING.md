# Domain Modeling with Strong Types

## Overview

This guide demonstrates how to use **opaque types** and **smart constructors** to make illegal states unrepresentable in your domain models. By encoding business rules and validation at the type level, you catch errors at compile-time or construction-time rather than at runtime.

---

## Table of Contents

1. [Type-Safe Identifiers (Opaque Types)](#type-safe-identifiers-opaque-types)
2. [Smart Constructors with Validation](#smart-constructors-with-validation)
3. [Validated Domain Types](#validated-domain-types)
4. [Composite Domain Models](#composite-domain-models)
5. [Phantom Types for State Machines](#phantom-types-for-state-machines)
6. [Integration with ZIO](#integration-with-zio)
7. [Anti-Patterns to Avoid](#anti-patterns-to-avoid)

---

## Type-Safe Identifiers (Opaque Types)

### Problem: Primitive Obsession

Using raw primitives for domain concepts leads to bugs:

```scala
// ❌ Bad: Can easily mix up IDs
def getWorker(id: String): IO[WorkerError, Worker] = ???
def getFile(id: String): IO[FileError, File] = ???

val workerId = "worker-123"
val fileId = "file-456"

getWorker(fileId)  // ✅ Compiles but wrong! Runtime error
```

### Solution: Opaque Types (Scala 3)

**Use opaque types for zero-overhead type safety:**

```scala
case class Worker(
  id: Worker.Id,
  manager: Worker.ManagerCode,
  config: Worker.Config
)

object Worker:
  // Zero-overhead type wrapper (opaque type)
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  opaque type ManagerCode = String
  object ManagerCode:
    def apply(value: String): ManagerCode = value
    extension (code: ManagerCode) def value: String = code

  // Nested types for namespacing
  case class Config(id: Config.Id, settings: Map[String, String])
  object Config:
    opaque type Id = String
    object Id:
      def apply(value: String): Id = value
      extension (id: Id) def value: String = id
```

### Benefits

- ✅ **Type safety**: Can't pass `Worker.Id` where `File.Id` is expected
- ✅ **Zero runtime overhead**: Completely erased at runtime
- ✅ **No boxing/unboxing**: Unlike AnyVal in some cases
- ✅ **Clear namespace organization**: Nested in companion objects
- ✅ **IDE auto-completion support**: Types are discoverable
- ✅ **Self-documenting code**: Intent is clear
- ✅ **Better than AnyVal**: Preferred for Scala 3 projects

### Usage

```scala
// ✅ Compile-time type safety
val workerId = Worker.Id("worker-123")
val fileId = File.Id("file-456")

def getWorker(id: Worker.Id): IO[WorkerError, Worker] = ???

getWorker(workerId)  // ✅ Compiles
getWorker(fileId)    // ❌ Compile error - type mismatch!
getWorker("worker-123")  // ❌ Compile error - needs Worker.Id

// Access underlying value with extension method
println(workerId.value)  // "worker-123"
```

### Scala 2 Alternative: AnyVal

For Scala 2 or cross-compilation:

```scala
object Worker:
  case class Id(value: String) extends AnyVal
  case class ManagerCode(value: String) extends AnyVal
```

**Note**: Prefer opaque types in Scala 3 for better ergonomics and guaranteed zero overhead.

---

## Smart Constructors with Validation

### Problem: Invalid Data at Runtime

Accepting any input leads to validation scattered throughout code:

```scala
// ❌ Bad: No validation at construction
case class MdfFile(file: File)

// Must validate everywhere
def processMdf(mdf: MdfFile): IO[FileError, Unit] =
  if !mdf.file.metadata.fullName.value.matches(".*\\.(MDF|MF4)$") then
    ZIO.fail(FileError.InvalidMdfFile(mdf.file.id))
  else
    // ... business logic
```

### Solution: Private Constructor + Smart Constructor

**Validate once at construction time:**

```scala
// Validated model with private constructor
case class MdfFile private (file: File)

object MdfFile:
  private val regexMDF = ".*\\.(MDF|MF4)$".r

  // Smart constructor returns Either for validation
  def apply(file: File): Either[FileError.InvalidMdfFile, MdfFile] =
    regexMDF
      .findFirstIn(file.metadata.fullName.value)
      .map(_ => new MdfFile(file))
      .toRight(FileError.InvalidMdfFile(file.id))
```

### Benefits

- ✅ **Invalid states become unrepresentable**: Can't construct invalid `MdfFile`
- ✅ **Validation happens once**: At construction time
- ✅ **No need to re-validate**: Once constructed, always valid
- ✅ **Clear error types**: Via `Either`
- ✅ **Immutable and safe**: Can't be modified after construction

### Usage

```scala
val file: File = ???

MdfFile(file) match
  case Right(mdfFile) =>
    // Guaranteed to be valid MDF file
    processMdf(mdfFile)  // No validation needed!
  case Left(error) =>
    // Handle validation error
    ZIO.fail(error)

// Clean with ZIO
def loadMdfFile(file: File): IO[FileError, MdfFile] =
  ZIO.fromEither(MdfFile(file))
```

---

## Validated Domain Types

### Common Validated Types

**Encode business rules as opaque types:**

```scala
// Email with validation
object Email:
  opaque type Email = String

  private val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".r

  def apply(value: String): Either[ValidationError, Email] =
    if emailRegex.matches(value) then
      Right(value)
    else
      Left(ValidationError.InvalidEmail(value))

  extension (email: Email) def value: String = email

// Positive integer
object PositiveInt:
  opaque type PositiveInt = Int

  def apply(value: Int): Either[ValidationError, PositiveInt] =
    if value > 0 then
      Right(value)
    else
      Left(ValidationError.MustBePositive(value))

  extension (n: PositiveInt) def value: Int = n

// Non-empty string
object NonEmptyString:
  opaque type NonEmptyString = String

  def apply(value: String): Either[ValidationError, NonEmptyString] =
    if value.trim.nonEmpty then
      Right(value.trim)
    else
      Left(ValidationError.CannotBeEmpty)

  extension (s: NonEmptyString) def value: String = s

// Port number (1-65535)
object Port:
  opaque type Port = Int

  def apply(value: Int): Either[ValidationError, Port] =
    if value >= 1 && value <= 65535 then
      Right(value)
    else
      Left(ValidationError.InvalidPort(value))

  extension (port: Port) def value: Int = port
```

### Usage

```scala
import Email.Email
import PositiveInt.PositiveInt
import NonEmptyString.NonEmptyString

val email: Either[ValidationError, Email] = Email("user@example.com")
val count: Either[ValidationError, PositiveInt] = PositiveInt(42)
val name: Either[ValidationError, NonEmptyString] = NonEmptyString("Alice")

// Pattern matching
email match
  case Right(validEmail) => sendEmail(validEmail)
  case Left(error) => handleError(error)

// With ZIO
def sendNotification(emailStr: String): IO[ValidationError, Unit] =
  for
    email <- ZIO.fromEither(Email(emailStr))
    _     <- emailService.send(email, "Hello!")
  yield ()
```

---

## Composite Domain Models

### Building Complex Types from Validated Primitives

**Compose validated types together:**

```scala
import Email.Email
import PositiveInt.PositiveInt
import NonEmptyString.NonEmptyString

// Worker configuration with validated fields
case class WorkerConfig private (
  name: NonEmptyString,
  email: Email,
  maxRetries: PositiveInt,
  region: Region
)

object WorkerConfig:
  def apply(
    name: String,
    email: String,
    maxRetries: Int,
    region: String
  ): Either[ValidationError, WorkerConfig] =
    for
      validName       <- NonEmptyString(name)
      validEmail      <- Email(email)
      validMaxRetries <- PositiveInt(maxRetries)
      validRegion     <- Region.parse(region)
    yield new WorkerConfig(validName, validEmail, validMaxRetries, validRegion)
```

### Usage in Services

```scala
def createWorker(
  name: String,
  email: String,
  maxRetries: Int,
  region: String
): IO[WorkerError, Worker] =
  for
    // Validate all fields at once
    config <- ZIO.fromEither(WorkerConfig(name, email, maxRetries, region))
                .mapError(WorkerError.InvalidConfiguration(_))

    // config is guaranteed to be valid here
    worker <- repository.save(Worker(config))
  yield worker
```

### Domain-Specific Composite Models

```scala
// Trace analysis domain
case class MdfAnalysis(
  analysis: Analysis,
  traceMeta: Trace,
  mdfFile: MdfFile  // Validated MDF file
)

object MdfAnalysis:
  def create(
    analysis: Analysis,
    traceMeta: Trace,
    file: File
  ): Either[FileError, MdfAnalysis] =
    MdfFile(file).map { validMdf =>
      MdfAnalysis(analysis, traceMeta, validMdf)
    }
```

---

## Phantom Types for State Machines

### Problem: Invalid State Transitions

Without type-level state, invalid transitions happen at runtime:

```scala
// ❌ Bad: Can call any method in any state
case class Worker(id: Worker.Id, status: WorkerStatus)

enum WorkerStatus:
  case Pending, Active, Stopped

def activate(worker: Worker): IO[WorkerError, Worker] = ???
def stop(worker: Worker): IO[WorkerError, Worker] = ???

val worker = Worker(id, WorkerStatus.Pending)
val stopped = stop(worker)  // ✅ Compiles but should fail! Can't stop pending worker
```

### Solution: Phantom Types

**Use phantom type parameters to encode state:**

```scala
// States as types
sealed trait WorkerState
object WorkerState:
  sealed trait Pending extends WorkerState
  sealed trait Active extends WorkerState
  sealed trait Stopped extends WorkerState

// Worker with phantom type parameter
case class Worker[S <: WorkerState](
  id: Worker.Id,
  config: Worker.Config
)

object Worker:
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  case class Config(settings: Map[String, String])

  // Factory creates Pending worker
  def create(id: Id, config: Config): Worker[WorkerState.Pending] =
    Worker[WorkerState.Pending](id, config)

// State transitions return new type
def activate(worker: Worker[WorkerState.Pending]): IO[WorkerError, Worker[WorkerState.Active]] =
  // Activation logic
  ZIO.succeed(Worker[WorkerState.Active](worker.id, worker.config))

def stop(worker: Worker[WorkerState.Active]): IO[WorkerError, Worker[WorkerState.Stopped]] =
  // Stop logic
  ZIO.succeed(Worker[WorkerState.Stopped](worker.id, worker.config))
```

### Benefits

- ✅ **Compile-time state validation**: Invalid transitions won't compile
- ✅ **Self-documenting API**: Types show valid transitions
- ✅ **No runtime checks**: All validation at compile-time
- ✅ **Refactoring safety**: Compiler catches invalid code

### Usage

```scala
val program = for
  pending <- ZIO.succeed(Worker.create(Worker.Id("w1"), config))
  active  <- activate(pending)  // ✅ Can activate pending worker
  stopped <- stop(active)       // ✅ Can stop active worker
  // active2 <- activate(stopped)  // ❌ Compile error - can't activate stopped worker!
yield stopped
```

### Advanced: Type-Level State Transitions

```scala
// More complex state machine
sealed trait ConnectionState
object ConnectionState:
  sealed trait Disconnected extends ConnectionState
  sealed trait Connecting extends ConnectionState
  sealed trait Connected extends ConnectionState
  sealed trait Authenticated extends ConnectionState

case class Connection[S <: ConnectionState](
  host: String,
  port: Port.Port
)

object Connection:
  def create(host: String, port: Port.Port): Connection[ConnectionState.Disconnected] =
    Connection[ConnectionState.Disconnected](host, port)

def connect[S <: ConnectionState.Disconnected](
  conn: Connection[S]
): IO[ConnectionError, Connection[ConnectionState.Connecting]] = ???

def authenticate(
  conn: Connection[ConnectionState.Connected]
): IO[ConnectionError, Connection[ConnectionState.Authenticated]] = ???

// Only authenticated connections can send commands
def sendCommand(
  conn: Connection[ConnectionState.Authenticated],
  cmd: Command
): IO[ConnectionError, Response] = ???
```

---

## Integration with ZIO

### Converting Either to ZIO

```scala
def createWorker(
  name: String,
  email: String
): IO[WorkerError, Worker] =
  for
    config <- ZIO.fromEither(WorkerConfig(name, email))
                .mapError(WorkerError.ValidationFailed(_))
    worker <- repository.save(Worker(config))
  yield worker
```

### Parallel Validation

```scala
def createBatch(
  workers: List[(String, String)]
): IO[WorkerError, List[Worker]] =
  ZIO.validatePar(workers) { case (name, email) =>
    createWorker(name, email)
  }
```

### Collecting All Validations

```scala
def validateEmails(
  inputs: List[String]
): IO[WorkerError, List[Email.Email]] =
  ZIO.collectAll(
    inputs.map(s =>
      ZIO.fromEither(Email(s))
        .mapError(WorkerError.InvalidEmail(_))
    )
  )
```

### Accumulating Errors

```scala
import zio.prelude.Validation

def validateWorkerData(
  name: String,
  email: String,
  maxRetries: Int
): Validation[ValidationError, (NonEmptyString.NonEmptyString, Email.Email, PositiveInt.PositiveInt)] =
  Validation.validateWith(
    Validation.fromEither(NonEmptyString(name)),
    Validation.fromEither(Email(email)),
    Validation.fromEither(PositiveInt(maxRetries))
  )((_, _, _))

// Returns ALL validation errors, not just the first
validateWorkerData("", "invalid", -5) match
  case Validation.Success(data) => // All valid
  case Validation.Failure(errors) =>
    // errors: NonEmptyChunk[ValidationError]
    // Contains: CannotBeEmpty, InvalidEmail, MustBePositive
```

---

## Anti-Patterns to Avoid

### ❌ Primitive Obsession

```scala
// Bad: Using raw primitives everywhere
case class Worker(
  id: String,          // Any string can be passed
  email: String,       // No validation
  maxRetries: Int      // Could be negative
)

def processWorker(id: String, email: String, retries: Int): IO[WorkerError, Unit] =
  // Validation scattered everywhere
  if !isValidEmail(email) then ZIO.fail(WorkerError.InvalidEmail)
  else if retries <= 0 then ZIO.fail(WorkerError.InvalidRetries)
  else ???
```

### ✅ Use Strong Types

```scala
// Good: Type-safe with validation
case class Worker(
  id: Worker.Id,
  email: Email.Email,
  maxRetries: PositiveInt.PositiveInt
)

def processWorker(worker: Worker): IO[WorkerError, Unit] =
  // No validation needed - types guarantee validity
  ???
```

### ❌ Stringly-Typed Code

```scala
// Bad: String-based enums
def setState(state: String): IO[WorkerError, Unit] =
  state match
    case "pending" | "active" | "stopped" => ???
    case _ => ZIO.fail(WorkerError.InvalidState(state))
```

### ✅ Use Enums or ADTs

```scala
// Good: Type-safe enums
enum WorkerStatus:
  case Pending, Active, Stopped

def setState(state: WorkerStatus): IO[WorkerError, Unit] = ???
```

### ❌ Runtime Validation Everywhere

```scala
// Bad: Validating the same thing over and over
def processEmail(email: String): IO[Error, Unit] =
  if !isValidEmail(email) then ZIO.fail(Error.InvalidEmail)
  else sendEmail(email)

def logEmail(email: String): IO[Error, Unit] =
  if !isValidEmail(email) then ZIO.fail(Error.InvalidEmail)
  else logger.log(email)
```

### ✅ Validate Once at Boundaries

```scala
// Good: Validate at the edge, use validated type internally
def handleRequest(emailStr: String): IO[Error, Unit] =
  for
    email <- ZIO.fromEither(Email(emailStr))  // Validate once
    _     <- processEmail(email)              // No validation
    _     <- logEmail(email)                  // No validation
  yield ()

def processEmail(email: Email.Email): IO[Error, Unit] = ???
def logEmail(email: Email.Email): IO[Error, Unit] = ???
```

---

## Key Principles

1. ✅ **Make illegal states unrepresentable** - use types to prevent invalid data
2. ✅ **Validate at boundaries** - parse at edges, use validated types internally
3. ✅ **Fail fast** - validate at construction time, not later
4. ✅ **Use opaque types for IDs** - zero-overhead type safety (Scala 3)
5. ✅ **Return `Either` for validation** - explicit error handling
6. ✅ **Private constructors** - force use of smart constructors
7. ✅ **Nested opaque types in companion objects** - clear namespacing
8. ✅ **Immutable validated types** - once valid, always valid
9. ✅ **Extension methods for value access** - clean API with opaque types
10. ✅ **Phantom types for state machines** - compile-time state validation

---

## When to Use Each Pattern

### Opaque Types
- ✅ Type-safe IDs and simple wrappers (Scala 3)
- ✅ Zero runtime overhead (no boxing)
- ✅ Better ergonomics than AnyVal
- ✅ Clear domain vocabulary
- ✅ Prefer over AnyVal in Scala 3 projects

### AnyVal (Scala 2)
- ✅ When you need Scala 2/3 cross-compilation
- ✅ Legacy codebases on Scala 2
- ⚠️ Has some boxing overhead in certain cases
- ⚠️ Opaque types are preferred in pure Scala 3

### Smart Constructors
- ✅ Domain objects with validation rules
- ✅ Business constraints (email format, file extensions)
- ✅ Need to fail fast at construction
- ✅ Validation is expensive (do it once)
- ✅ Works with both opaque types and case classes

### Composite Domain Models
- ✅ Aggregating validated primitives
- ✅ Complex business objects
- ✅ Multiple validation rules
- ✅ Builder pattern alternative

### Phantom Types
- ✅ State machines with valid transitions
- ✅ Compile-time enforcement of business rules
- ✅ Protocol implementations
- ✅ Type-level guarantees

---

## Summary

**Domain modeling with strong types enables:**

- **Compile-time safety** - Catch type mismatches before runtime
- **Construction-time validation** - Invalid data can't be created
- **Self-documenting code** - Types express business rules
- **Reduced testing** - Types eliminate entire classes of bugs
- **Fearless refactoring** - Compiler guides changes

**Follow these practices:**

1. Use opaque types for type-safe IDs (Scala 3)
2. Use smart constructors to validate at construction
3. Return `Either` for validation results
4. Make constructors private to force validation
5. Use phantom types for state machines
6. Validate at boundaries, use validated types internally
7. Compose validated types into domain models

---

**Related Documentation:**
- 📖 [Architecture Guide](./ARCHITECTURE.md) - Layer 2: Validated Domain Models
- 📖 [Error Modeling Guide](./ERROR_MODELING_GUIDE.md) - Error design patterns
- 📖 [Service Patterns](../README.md#service-patterns) - Service design with validated types

**Version**: 1.0
**Last Updated**: 2025-01-09