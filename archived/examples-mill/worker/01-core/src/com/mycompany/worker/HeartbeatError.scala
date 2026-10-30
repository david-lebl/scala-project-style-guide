import java.time.Instant
import scala.util.control.NoStackTrace

/**
 * Heartbeat domain errors.
 *
 * Sealed trait hierarchy for all heartbeat-related errors. Each error includes
 * a descriptive message for logging and user feedback.
 */
sealed trait HeartbeatError extends NoStackTrace:
  def message: String
  override def getMessage: String = message

object HeartbeatError:

  // ========== Not Found Errors ==========

  case class HeartbeatNotFound(id: Heartbeat.Id) extends HeartbeatError:
    val message = s"Heartbeat with ID '${id.value}' not found"

  case class NoHeartbeatsForWorker(workerId: Worker.Id) extends HeartbeatError:
    val message = s"No heartbeats found for worker '${workerId.value}'"

  // ========== Validation Errors ==========

  case class InvalidTimeRange(from: Instant, to: Instant) extends HeartbeatError:
    val message = s"Invalid time range: from=$from must be before to=$to"

  case class InvalidLimit(limit: Int) extends HeartbeatError:
    val message = s"Invalid limit: $limit (must be positive)"

  case class InvalidLatency(createdAt: Instant, receivedAt: Instant)
      extends HeartbeatError:
    val message =
      s"Invalid heartbeat timing: createdAt=$createdAt is after receivedAt=$receivedAt"

  // ========== Repository Errors ==========

  case class RepositoryError(operation: String, cause: Throwable)
      extends HeartbeatError:
    val message =
      s"Heartbeat repository operation '$operation' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause

  case class StorageError(operation: String, detail: String)
      extends HeartbeatError:
    val message = s"Heartbeat storage error during '$operation': $detail"
