# Error Modeling in Scala with ZIO

A comprehensive guide for designing domain errors in Scala/ZIO applications.

---

## Table of Contents

1. [Domain-Wide vs Service-Specific Errors](#domain-wide-vs-service-specific-errors)
2. [Error Structure Best Practices](#error-structure-best-practices)
3. [Error Messages](#error-messages)
4. [Converting to Throwable](#converting-to-throwable)
5. [Error Hierarchies](#error-hierarchies)
6. [Practical Examples](#practical-examples)

---

## Domain-Wide vs Service-Specific Errors

### The Question:

Should you have **one error type for the entire domain** or **separate errors per service/use case**?

```scala
// Option 1: Domain-wide error
sealed trait WorkerError
case class WorkerNotFound(id: Worker.Id, message: String) extends WorkerError

// All services use the same error type
trait WorkerService:
  def register(...): IO[WorkerError, Worker]
  def heartbeat(...): IO[WorkerError, Unit]
  def getWorker(...): IO[WorkerError, Worker]

// vs

// Option 2: Service-specific errors
sealed trait RegistrationError
sealed trait HeartbeatError

trait WorkerService:
  def register(...): IO[RegistrationError, Worker]
  def heartbeat(...): IO[HeartbeatError, Unit]
```

---

### Recommendation: **Domain-Wide Error** (for most cases)

✅ **Use a single domain-wide error type** for the entire domain, with these exceptions:
- Services deployed independently
- External API boundaries with different error contracts
- Very different error handling requirements

---

### Why Domain-Wide Error?

**Composability**:
```scala
// ✅ Easy composition with same error type
def registerAndActivate(id: String): IO[WorkerError, Worker] =
  for
    worker <- WorkerService.register(id, Map.empty)      // IO[WorkerError, Worker]
    _ <- WorkerService.heartbeat(worker.id)              // IO[WorkerError, Unit]
    activated <- WorkerService.activate(worker.id)       // IO[WorkerError, Worker]
  yield activated

// ❌ Difficult with different error types
def registerAndActivate(id: String): IO[???, Worker] =  // What error type here?
  for
    worker <- WorkerService.register(id, Map.empty)      // IO[RegistrationError, Worker]
    _ <- WorkerService.heartbeat(worker.id)              // IO[HeartbeatError, Unit] - Can't compose!
  yield worker
```

**Simplicity**:
- One error type to document
- One error type to handle
- One error type to test

**Refactoring Freedom**:
- Can move operations between services
- Errors flow naturally through call chains
- API remains stable

---

### When to Use Service-Specific Errors?

Create **separate error types** when:

1. **Independent deployment** - Services deployed as separate microservices
2. **Different API boundaries** - REST API vs gRPC vs Message Queue
3. **Different error handling** - Retry logic differs significantly
4. **Very large domains** - 100+ error cases (consider splitting domain instead)

---

## Error Structure Best Practices

### Include Error Messages

Each error should contain a **human-readable message**:

```scala
sealed trait WorkerError:
  def message: String

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class InvalidWorkerState(
    id: Worker.Id,
    currentState: Worker.Status,
    expectedState: Worker.Status
  ) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $currentState, expected $expectedState"

  case class HeartbeatTimeout(id: Worker.Id, lastSeen: Instant) extends WorkerError:
    val message = s"Worker '${id.value}' heartbeat timeout, last seen at $lastSeen"

// Usage
def handleError(error: WorkerError): Unit =
  println(error.message)  // User-friendly message
```

**Benefits**:
- **User-friendly**: Clear error messages
- **Debugging**: Includes relevant context
- **Logging**: Can log directly
- **API responses**: Can return to clients

---

### Alternative: Message Method

If you prefer flexibility:

```scala
sealed trait WorkerError:
  def message: String

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    def message = s"Worker with ID '${id.value}' not found"

  case class InvalidConfiguration(field: String, reason: String) extends WorkerError:
    def message = s"Invalid configuration for field '$field': $reason"
```

**Same benefits**, but computed on-demand rather than stored.

---

## Converting to Throwable

ZIO errors are typed, but sometimes you need to convert to `Throwable`:
- Logging frameworks
- Legacy code integration
- Monitoring/observability tools
- Stack traces for debugging

### Approach 1: Extend NoStackTrace Exception (Recommended)

```scala
import scala.util.control.NoStackTrace

sealed trait WorkerError extends NoStackTrace:  // Extends Exception via NoStackTrace
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
    override def getCause: Throwable = cause  // Preserve original cause
```

**Benefits**:
- ✅ IS-A Throwable (can be used anywhere Throwable is needed)
- ✅ No stack trace overhead (`NoStackTrace`)
- ✅ Works with logging frameworks
- ✅ Can preserve underlying cause

**Usage**:
```scala
// Automatically works as Throwable
def logError(error: WorkerError): Unit =
  logger.error("Worker error occurred", error)  // error is Throwable

// Can catch in try/catch (though not recommended with ZIO)
try
  // some code
catch
  case e: WorkerError.WorkerNotFound => println(e.message)
```

---

### Approach 2: toThrowable Method

If you prefer not to extend Exception:

```scala
sealed trait WorkerError:
  def message: String
  def toThrowable: Throwable

object WorkerError:
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"
    def toThrowable = new Exception(message) with NoStackTrace

  case class InvalidWorkerState(
    id: Worker.Id,
    currentState: Worker.Status,
    expectedState: Worker.Status
  ) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $currentState, expected $expectedState"
    def toThrowable = new Exception(message) with NoStackTrace

  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    def toThrowable = new Exception(message, cause) with NoStackTrace
```

**Benefits**:
- ✅ Errors are plain case classes
- ✅ Clear conversion point
- ✅ Can customize throwable creation

**Usage**:
```scala
// Explicit conversion
def logError(error: WorkerError): Unit =
  logger.error("Worker error occurred", error.toThrowable)

// With ZIO
def handleError: IO[Throwable, Worker] =
  WorkerService.register("worker-1", Map.empty)
    .mapError(_.toThrowable)  // Convert to Throwable
```

---

### Approach 3: Both (Maximum Flexibility)

Combine both approaches:

```scala
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

  // Also provide explicit conversion
  def toThrowable: Throwable = this
```

**When to use each approach:**

| Approach | Use When |
|----------|----------|
| **Extend NoStackTrace** | Default choice - simple, works everywhere |
| **toThrowable method** | Want errors as pure case classes, explicit conversion |
| **Both** | Maximum flexibility, but slight redundancy |

---

## Error Hierarchies

### Flat Structure (Simple)

Good for **small-medium domains** (< 20 error cases):

```scala
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  // Registration errors
  case class WorkerAlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker '${id.value}' already exists"

  case class InvalidConfiguration(field: String, reason: String) extends WorkerError:
    val message = s"Invalid configuration for field '$field': $reason"

  // State errors
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class InvalidWorkerState(
    id: Worker.Id,
    currentState: Worker.Status,
    expectedState: Worker.Status
  ) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $currentState, expected $expectedState"

  // Heartbeat errors
  case class HeartbeatTimeout(id: Worker.Id, lastSeen: Instant) extends WorkerError:
    val message = s"Worker '${id.value}' heartbeat timeout, last seen at $lastSeen"

  // Infrastructure errors
  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause
```

---

### Hierarchical Structure (Advanced)

Good for **large domains** with logical groupings:

```scala
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

// Sub-categories
sealed trait RegistrationError extends WorkerError
sealed trait StateError extends WorkerError
sealed trait HeartbeatError extends WorkerError
sealed trait InfrastructureError extends WorkerError

object WorkerError:
  // Registration errors
  object Registration:
    case class WorkerAlreadyExists(id: Worker.Id) extends RegistrationError:
      val message = s"Worker '${id.value}' already exists"

    case class InvalidConfiguration(field: String, reason: String) extends RegistrationError:
      val message = s"Invalid configuration for field '$field': $reason"

  // State errors
  object State:
    case class WorkerNotFound(id: Worker.Id) extends StateError:
      val message = s"Worker with ID '${id.value}' not found"

    case class InvalidTransition(
      id: Worker.Id,
      from: Worker.Status,
      to: Worker.Status
    ) extends StateError:
      val message = s"Invalid state transition for worker '${id.value}': $from -> $to"

  // Heartbeat errors
  object Heartbeat:
    case class Timeout(id: Worker.Id, lastSeen: Instant) extends HeartbeatError:
      val message = s"Worker '${id.value}' heartbeat timeout, last seen at $lastSeen"

    case class RecordingFailed(id: Worker.Id, reason: String) extends HeartbeatError:
      val message = s"Failed to record heartbeat for worker '${id.value}': $reason"

  // Infrastructure errors
  object Infrastructure:
    case class RepositoryError(operation: String, cause: Throwable) extends InfrastructureError:
      val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
      override def getCause: Throwable = cause

    case class DatabaseError(query: String, cause: Throwable) extends InfrastructureError:
      val message = s"Database query failed: ${cause.getMessage}"
      override def getCause: Throwable = cause
```

**Usage**:
```scala
// Import specific categories
import WorkerError.Registration._
import WorkerError.State._

// Pattern match by category
def handleError(error: WorkerError): String = error match
  case e: RegistrationError => s"Registration failed: ${e.message}"
  case e: StateError => s"State error: ${e.message}"
  case e: HeartbeatError => s"Heartbeat error: ${e.message}"
  case e: InfrastructureError => s"System error: ${e.message}"

// Or match specific errors
def handleSpecific(error: WorkerError): Unit = error match
  case Registration.WorkerAlreadyExists(id) => println(s"Worker $id exists")
  case State.WorkerNotFound(id) => println(s"Worker $id not found")
  case _ => println(s"Other error: ${error.message}")
```

---

## Practical Examples

### Example 1: Complete Worker Domain Error

```scala
package com.mycompany.worker

import scala.util.control.NoStackTrace
import java.time.Instant

sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:
  // Registration
  case class WorkerAlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' already exists"

  case class InvalidConfiguration(field: String, reason: String) extends WorkerError:
    val message = s"Invalid configuration: field '$field' - $reason"

  case class RegistrationFailed(id: Worker.Id, reason: String) extends WorkerError:
    val message = s"Failed to register worker '${id.value}': $reason"

  // Retrieval
  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class MultipleWorkersFound(id: Worker.Id, count: Int) extends WorkerError:
    val message = s"Expected 1 worker with ID '${id.value}', found $count"

  // State Management
  case class InvalidWorkerState(
    id: Worker.Id,
    currentState: Worker.Status,
    expectedState: Worker.Status
  ) extends WorkerError:
    val message = s"Worker '${id.value}' is in state $currentState, expected $expectedState"

  case class InvalidStateTransition(
    id: Worker.Id,
    from: Worker.Status,
    to: Worker.Status
  ) extends WorkerError:
    val message = s"Invalid state transition for worker '${id.value}': $from -> $to"

  // Heartbeat
  case class HeartbeatTimeout(id: Worker.Id, lastSeen: Instant, timeout: Duration) extends WorkerError:
    val message = s"Worker '${id.value}' heartbeat timeout (last seen: $lastSeen, timeout: $timeout)"

  case class HeartbeatRecordingFailed(id: Worker.Id, reason: String) extends WorkerError:
    val message = s"Failed to record heartbeat for worker '${id.value}': $reason"

  // Business Rules
  case class CapacityExceeded(requested: Int, available: Int, max: Int) extends WorkerError:
    val message = s"Worker capacity exceeded: requested $requested, available $available, max $max"

  case class UnauthorizedOperation(userId: String, operation: String, workerId: Worker.Id) extends WorkerError:
    val message = s"User '$userId' not authorized to perform '$operation' on worker '${workerId.value}'"

  // Infrastructure (wrapping technical errors)
  case class RepositoryError(operation: String, cause: Throwable) extends WorkerError:
    val message = s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause

  case class DatabaseError(cause: Throwable) extends WorkerError:
    val message = s"Database error: ${cause.getMessage}"
    override def getCause: Throwable = cause

  case class ExternalServiceError(service: String, cause: Throwable) extends WorkerError:
    val message = s"External service '$service' error: ${cause.getMessage}"
    override def getCause: Throwable = cause
```

---

### Example 2: Using in Service

```scala
trait WorkerService:
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]
  def unregister(id: Worker.Id): IO[WorkerError, Unit]
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

final case class WorkerServiceLive(
  workerRepo: WorkerRepository,
  heartbeatRepo: HeartbeatRepository
) extends WorkerService:

  override def register(id: String, config: Map[String, String]): IO[WorkerError, Worker] =
    val workerId = Worker.Id(id)

    workerRepo.exists(workerId).flatMap {
      case true =>
        ZIO.fail(WorkerError.WorkerAlreadyExists(workerId))
      case false =>
        validateConfiguration(config).flatMap { validConfig =>
          val worker = Worker.create(id, validConfig)
          workerRepo.save(worker)
            .mapError(e => WorkerError.RepositoryError("save", e))
            .as(worker)
        }
    }

  override def heartbeat(id: Worker.Id): IO[WorkerError, Unit] =
    for
      worker <- workerRepo.findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))
        .mapError {
          case e: WorkerError => e
          case e: Throwable => WorkerError.RepositoryError("findById", e)
        }

      _ <- heartbeatRepo.record(id, Instant.now())
        .mapError(e => WorkerError.HeartbeatRecordingFailed(id, e.getMessage))

      // Auto-activate pending workers
      _ <- if worker.status == Worker.Status.Pending then
        updateStatus(id, Worker.Status.Active, Some(Worker.Status.Pending))
      else
        ZIO.unit
    yield ()

  private def validateConfiguration(config: Map[String, String]): IO[WorkerError, Map[String, String]] =
    ZIO.foreach(List("region", "capacity")) { field =>
      ZIO.fromOption(config.get(field))
        .orElseFail(WorkerError.InvalidConfiguration(field, "required field missing"))
    }.as(config)

  private def updateStatus(
    id: Worker.Id,
    newStatus: Worker.Status,
    expectedStatus: Option[Worker.Status]
  ): IO[WorkerError, Worker] =
    for
      worker <- workerRepo.findById(id).someOrFail(WorkerError.WorkerNotFound(id))

      _ <- expectedStatus match
        case Some(expected) if worker.status != expected =>
          ZIO.fail(WorkerError.InvalidWorkerState(id, worker.status, expected))
        case _ =>
          ZIO.unit

      updatedWorker = worker.copy(status = newStatus)
      _ <- workerRepo.save(updatedWorker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield updatedWorker
```

---

### Example 3: Error Handling in Application

```scala
object WorkerApp extends ZIOAppDefault:

  val program: ZIO[WorkerService, WorkerError, Unit] =
    for
      worker <- WorkerService.register("worker-1", Map(
        "region" -> "us-east",
        "capacity" -> "100"
      ))
      _ <- Console.printLine(s"Registered: ${worker.id.value}").orDie

      _ <- WorkerService.heartbeat(worker.id)
      _ <- Console.printLine("Heartbeat recorded").orDie
    yield ()

  override def run =
    program.provide(
      InMemoryWorkerRepository.layer,
      InMemoryHeartbeatRepository.layer,
      WorkerService.layer
    ).catchAll { error =>
      // Error is already Throwable, can log directly
      Console.printLineError(s"Error: ${error.message}") *>
        ZIO.logErrorCause("Worker error", Cause.fail(error))
    }
```

---

### Example 4: Converting to HTTP Responses

```scala
import zio.http._

object WorkerRoutes:
  def routes: Routes[WorkerService, Nothing] = Routes(
    Method.POST / "workers" -> handler { (req: Request) =>
      (for
        dto <- req.body.as[RegisterWorkerRequest]
        worker <- WorkerService.register(dto.id, dto.config)
        response = RegisterWorkerResponse(worker.id.value, worker.status.toString)
      yield Response.json(response.toJson))
        .catchAll(errorToHttpResponse)
    }
  )

  private def errorToHttpResponse(error: WorkerError): UIO[Response] =
    val (status, body) = error match
      case WorkerError.WorkerAlreadyExists(_) =>
        (Status.Conflict, ErrorResponse(409, error.message))

      case WorkerError.WorkerNotFound(_) =>
        (Status.NotFound, ErrorResponse(404, error.message))

      case WorkerError.InvalidConfiguration(_, _) =>
        (Status.BadRequest, ErrorResponse(400, error.message))

      case WorkerError.UnauthorizedOperation(_, _, _) =>
        (Status.Forbidden, ErrorResponse(403, error.message))

      case _: WorkerError.RepositoryError | _: WorkerError.DatabaseError =>
        (Status.InternalServerError, ErrorResponse(500, "Internal server error"))

      case _ =>
        (Status.InternalServerError, ErrorResponse(500, error.message))

    ZIO.succeed(Response(status = status, body = Body.fromString(body.toJson)))

  case class ErrorResponse(code: Int, message: String) derives JsonCodec
```

---

## Summary: Best Practices

1. ✅ **Use domain-wide error** for most cases (simplicity + composability)
2. ✅ **Include error messages** in each error case (user-friendly + debugging)
3. ✅ **Extend NoStackTrace** for automatic Throwable conversion (recommended)
4. ✅ **Preserve underlying causes** when wrapping exceptions (use `getCause`)
5. ✅ **Group errors logically** in companion object or use hierarchical structure
6. ✅ **Include relevant context** (IDs, states, timestamps) in errors
7. ✅ **Map infrastructure errors** to domain errors at the boundary
8. ⚠️ **Only split errors** when services are truly independent
9. ❌ **Don't use generic errors** like `Error(message: String)` - be specific
10. ❌ **Don't leak technical details** in error messages shown to end users

---

## Additional Considerations

### Error Logging

```scala
// Errors extending NoStackTrace work directly with logging
def logError(error: WorkerError): UIO[Unit] =
  ZIO.logErrorCause("Worker operation failed", Cause.fail(error))

// Or use slf4j/log4j
def logWithSlf4j(error: WorkerError): Unit =
  logger.error("Worker error: {}", error.message, error)  // error is Throwable
```

### Error Metrics

```scala
def recordErrorMetric(error: WorkerError): UIO[Unit] =
  val errorType = error.getClass.getSimpleName
  metrics.counter(s"worker.errors.$errorType").increment
```

### Error Documentation

```scala
/** Worker domain errors.
  *
  * All errors include a human-readable message and extend NoStackTrace
  * for efficient error handling without stack trace overhead.
  *
  * Error Categories:
  * - Registration: WorkerAlreadyExists, InvalidConfiguration
  * - State: WorkerNotFound, InvalidWorkerState
  * - Heartbeat: HeartbeatTimeout, HeartbeatRecordingFailed
  * - Infrastructure: RepositoryError, DatabaseError
  */
sealed trait WorkerError extends NoStackTrace
```

This approach provides excellent error handling while maintaining type safety and user-friendliness!