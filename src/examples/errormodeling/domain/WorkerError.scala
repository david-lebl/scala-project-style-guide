package examples.errormodeling.domain

import scala.util.control.NoStackTrace
import java.time.{Instant, Duration}

/** Worker domain errors.
  *
  * All errors include a human-readable message and extend NoStackTrace for
  * efficient error handling without stack trace overhead.
  *
  * Error Categories:
  *   - Registration: WorkerAlreadyExists, InvalidConfiguration,
  *     RegistrationFailed
  *   - Retrieval: WorkerNotFound, MultipleWorkersFound
  *   - State Management: InvalidWorkerState, InvalidStateTransition
  *   - Heartbeat: HeartbeatTimeout, HeartbeatRecordingFailed
  *   - Business Rules: CapacityExceeded, UnauthorizedOperation
  *   - Infrastructure: RepositoryError, DatabaseError, ExternalServiceError
  */
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:

  // Registration errors

  case class WorkerAlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' already exists"

  case class InvalidConfiguration(field: String, reason: String)
      extends WorkerError:
    val message = s"Invalid configuration: field '$field' - $reason"

  case class RegistrationFailed(id: Worker.Id, reason: String)
      extends WorkerError:
    val message = s"Failed to register worker '${id.value}': $reason"

  // Retrieval errors

  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  case class MultipleWorkersFound(id: Worker.Id, count: Int)
      extends WorkerError:
    val message = s"Expected 1 worker with ID '${id.value}', found $count"

  // State Management errors

  case class InvalidWorkerState(
      id: Worker.Id,
      currentState: Worker.Status,
      expectedState: Worker.Status
  ) extends WorkerError:
    val message =
      s"Worker '${id.value}' is in state $currentState, expected $expectedState"

  case class InvalidStateTransition(
      id: Worker.Id,
      from: Worker.Status,
      to: Worker.Status
  ) extends WorkerError:
    val message =
      s"Invalid state transition for worker '${id.value}': $from -> $to"

  // Heartbeat errors

  case class HeartbeatTimeout(
      id: Worker.Id,
      lastSeen: Instant,
      timeout: Duration
  ) extends WorkerError:
    val message =
      s"Worker '${id.value}' heartbeat timeout (last seen: $lastSeen, timeout: $timeout)"

  case class HeartbeatRecordingFailed(id: Worker.Id, reason: String)
      extends WorkerError:
    val message =
      s"Failed to record heartbeat for worker '${id.value}': $reason"

  // Business Rules errors

  case class CapacityExceeded(requested: Int, available: Int, max: Int)
      extends WorkerError:
    val message =
      s"Worker capacity exceeded: requested $requested, available $available, max $max"

  case class UnauthorizedOperation(
      userId: String,
      operation: String,
      workerId: Worker.Id
  ) extends WorkerError:
    val message =
      s"User '$userId' not authorized to perform '$operation' on worker '${workerId.value}'"

  // Infrastructure errors (wrapping technical errors)

  case class RepositoryError(operation: String, cause: Throwable)
      extends WorkerError:
    val message =
      s"Repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause

  case class DatabaseError(cause: Throwable) extends WorkerError:
    val message = s"Database error: ${cause.getMessage}"
    override def getCause: Throwable = cause

  case class ExternalServiceError(service: String, cause: Throwable)
      extends WorkerError:
    val message = s"External service '$service' error: ${cause.getMessage}"
    override def getCause: Throwable = cause
