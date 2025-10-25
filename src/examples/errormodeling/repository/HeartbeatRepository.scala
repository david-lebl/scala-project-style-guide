package examples.errormodeling.repository

import examples.errormodeling.domain.{Worker, WorkerError}
import zio.*
import java.time.{Instant, Duration}

/** Repository interface for Heartbeat tracking.
  *
  * Tracks worker heartbeats and enables detection of stale workers.
  */
trait HeartbeatRepository:
  /** Record a heartbeat for a worker. */
  def record(workerId: Worker.Id, timestamp: Instant): IO[WorkerError, Unit]

  /** Get the last heartbeat timestamp for a worker. */
  def getLastHeartbeat(workerId: Worker.Id): IO[WorkerError, Option[Instant]]

  /** Find workers with stale heartbeats (not seen within the timeout period).
    */
  def findStaleWorkers(timeout: Duration): IO[WorkerError, List[Worker.Id]]

  /** Remove heartbeat records for a worker. */
  def remove(workerId: Worker.Id): IO[WorkerError, Unit]
