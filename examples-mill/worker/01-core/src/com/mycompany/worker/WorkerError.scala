package com.mycompany.worker

import scala.util.control.NoStackTrace
import java.time.{Instant, Duration}

/**
 * Worker domain errors.
 *
 * Single error type for the entire worker domain, enabling easy composition
 * with flatMap, zipPar, and other ZIO operations.
 *
 * Error Categories:
 *   - Registration: WorkerAlreadyExists, InvalidConfiguration
 *   - Retrieval: WorkerNotFound
 *   - State Management: InvalidWorkerState, InvalidStateTransition
 *   - Heartbeat: HeartbeatTimeout, HeartbeatFailed, NoHeartbeatRecorded
 *   - Unregistration: UnregisterFailed
 *   - Infrastructure: RepositoryError, StorageError
 */
sealed trait WorkerError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object WorkerError:

  // ========== Registration Errors ==========

  case class WorkerAlreadyExists(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' already exists"

  case class InvalidConfiguration(field: String, reason: String)
      extends WorkerError:
    val message = s"Invalid configuration: field '$field' - $reason"

  case class RegistrationFailed(id: Worker.Id, reason: String)
      extends WorkerError:
    val message = s"Failed to register worker '${id.value}': $reason"

  // ========== Retrieval Errors ==========

  case class WorkerNotFound(id: Worker.Id) extends WorkerError:
    val message = s"Worker with ID '${id.value}' not found"

  // ========== State Management Errors ==========

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

  // ========== Heartbeat Errors ==========

  case class HeartbeatTimeout(
      id: Worker.Id,
      lastSeen: Instant,
      timeout: Duration
  ) extends WorkerError:
    val message =
      s"Worker '${id.value}' heartbeat timeout (last seen: $lastSeen, timeout: ${timeout.toSeconds}s)"

  case class HeartbeatFailed(id: Worker.Id, reason: String)
      extends WorkerError:
    val message = s"Failed to record heartbeat for worker '${id.value}': $reason"

  case class NoHeartbeatRecorded(id: Worker.Id) extends WorkerError:
    val message = s"No heartbeat recorded for worker '${id.value}'"

  // ========== Unregistration Errors ==========

  case class UnregisterFailed(id: Worker.Id, reason: String)
      extends WorkerError:
    val message = s"Failed to unregister worker '${id.value}': $reason"

  // ========== Infrastructure Errors ==========

  case class RepositoryError(operation: String, detail: String)
      extends WorkerError:
    val message = s"Repository operation '$operation' failed: $detail"

  case class StorageError(operation: String, detail: String)
      extends WorkerError:
    val message = s"Storage operation '$operation' failed: $detail"
