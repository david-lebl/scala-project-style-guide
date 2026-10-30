import com.mycompany.worker.{Heartbeat, HeartbeatError, Worker}
import zio.*

import java.time.{Duration, Instant}

/**
 * Heartbeat repository interface (package-private).
 *
 * Stores and retrieves Heartbeat entities. Returns full Heartbeat objects, not
 * just timestamps.
 */
private[worker] trait HeartbeatRepository:

  /** Save a heartbeat. */
  def save(heartbeat: Heartbeat): IO[HeartbeatError, Unit]

  /** Find a heartbeat by ID. */
  def findById(id: Heartbeat.Id): IO[HeartbeatError, Option[Heartbeat]]

  /**
   * Get the most recent heartbeat for a worker. Returns the full Heartbeat
   * entity with all metadata.
   */
  def findLatestByWorkerId(workerId: Worker.Id): IO[HeartbeatError, Option[Heartbeat]]

  /**
   * Get heartbeat history for a worker. Returns list of Heartbeat entities,
   * most recent first.
   */
  def findByWorkerId(
      workerId: Worker.Id,
      limit: Int = 10
  ): IO[HeartbeatError, List[Heartbeat]]

  /**
   * Find all workers that have stale heartbeats.
   *
   * Returns worker IDs where the most recent heartbeat is older than timeout.
   * This is an efficient query for the periodic status computation.
   */
  def findWorkersWithStaleHeartbeats(
      timeout: Duration
  ): IO[HeartbeatError, List[Worker.Id]]

  /**
   * Find all workers that have recent heartbeats.
   *
   * Returns worker IDs where the most recent heartbeat is within timeout.
   */
  def findWorkersWithRecentHeartbeats(
      timeout: Duration
  ): IO[HeartbeatError, List[Worker.Id]]

  /**
   * Find all heartbeats received within a time range. Useful for analytics and
   * monitoring.
   */
  def findByTimeRange(
      from: Instant,
      to: Instant
  ): IO[HeartbeatError, List[Heartbeat]]

  /**
   * Delete all heartbeats for a worker. Called when worker is unregistered.
   */
  def deleteByWorkerId(workerId: Worker.Id): IO[HeartbeatError, Unit]

  /** Count heartbeats for a worker. */
  def countByWorkerId(workerId: Worker.Id): IO[HeartbeatError, Long]

private[worker] object HeartbeatRepository:

  // ZIO accessor methods

  def save(heartbeat: Heartbeat): ZIO[HeartbeatRepository, HeartbeatError, Unit] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.save(heartbeat))

  def findById(
      id: Heartbeat.Id
  ): ZIO[HeartbeatRepository, HeartbeatError, Option[Heartbeat]] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.findById(id))

  def findLatestByWorkerId(
      workerId: Worker.Id
  ): ZIO[HeartbeatRepository, HeartbeatError, Option[Heartbeat]] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.findLatestByWorkerId(workerId))

  def findByWorkerId(
      workerId: Worker.Id,
      limit: Int = 10
  ): ZIO[HeartbeatRepository, HeartbeatError, List[Heartbeat]] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.findByWorkerId(workerId, limit))

  def findWorkersWithStaleHeartbeats(
      timeout: Duration
  ): ZIO[HeartbeatRepository, HeartbeatError, List[Worker.Id]] =
    ZIO.serviceWithZIO[HeartbeatRepository](
      _.findWorkersWithStaleHeartbeats(timeout)
    )

  def findWorkersWithRecentHeartbeats(
      timeout: Duration
  ): ZIO[HeartbeatRepository, HeartbeatError, List[Worker.Id]] =
    ZIO.serviceWithZIO[HeartbeatRepository](
      _.findWorkersWithRecentHeartbeats(timeout)
    )

  def findByTimeRange(
      from: Instant,
      to: Instant
  ): ZIO[HeartbeatRepository, HeartbeatError, List[Heartbeat]] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.findByTimeRange(from, to))

  def deleteByWorkerId(
      workerId: Worker.Id
  ): ZIO[HeartbeatRepository, HeartbeatError, Unit] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.deleteByWorkerId(workerId))

  def countByWorkerId(
      workerId: Worker.Id
  ): ZIO[HeartbeatRepository, HeartbeatError, Long] =
    ZIO.serviceWithZIO[HeartbeatRepository](_.countByWorkerId(workerId))
