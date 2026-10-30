import com.mycompany.worker.{Heartbeat, HeartbeatError, Worker}
import zio.*
import zio.stm.*

import java.time.{Duration, Instant}

/**
 * In-memory heartbeat repository using STM TMap.
 *
 * Provides thread-safe, transactional storage of heartbeat entities. Uses two
 * TMaps for efficient access:
 *   - heartbeatsById: Primary index by Heartbeat.Id
 *   - heartbeatsByWorker: Secondary index by Worker.Id for worker-specific
 *     queries
 */
object InMemoryHeartbeatRepository:

  def layer: ZLayer[Clock, Nothing, HeartbeatRepository] =
    ZLayer.scoped(
      for
        heartbeatsById <- TMap.empty[Heartbeat.Id, Heartbeat].commit
        heartbeatsByWorker <- TMap
          .empty[Worker.Id, List[Heartbeat]]
          .commit
        clock <- ZIO.clock
      yield new InMemoryHeartbeatRepository(
        heartbeatsById,
        heartbeatsByWorker,
        clock
      )
    )

private[worker] final class InMemoryHeartbeatRepository(
    heartbeatsById: TMap[Heartbeat.Id, Heartbeat],
    heartbeatsByWorker: TMap[Worker.Id, List[Heartbeat]],
    clock: Clock
) extends HeartbeatRepository:

  override def save(heartbeat: Heartbeat): IO[HeartbeatError, Unit] =
    (for
      // Save in primary index
      _ <- heartbeatsById.put(heartbeat.id, heartbeat)

      // Update secondary index (keep sorted by receivedAt, most recent first)
      existingHeartbeats <- heartbeatsByWorker
        .get(heartbeat.workerId)
        .map(_.getOrElse(List.empty))

      // Add new heartbeat and keep only last 1000 (memory management)
      updatedHeartbeats = (heartbeat :: existingHeartbeats)
        .sortBy(_.receivedAt.toEpochMilli)(Ordering[Long].reverse)
        .take(1000)

      _ <- heartbeatsByWorker.put(heartbeat.workerId, updatedHeartbeats)
    yield ()).commit

  override def findById(
      id: Heartbeat.Id
  ): IO[HeartbeatError, Option[Heartbeat]] =
    heartbeatsById.get(id).commit

  override def findLatestByWorkerId(
      workerId: Worker.Id
  ): IO[HeartbeatError, Option[Heartbeat]] =
    heartbeatsByWorker
      .get(workerId)
      .map(_.flatMap(_.headOption))
      .commit

  override def findByWorkerId(
      workerId: Worker.Id,
      limit: Int = 10
  ): IO[HeartbeatError, List[Heartbeat]] =
    if limit <= 0 then
      ZIO.fail(HeartbeatError.InvalidLimit(limit))
    else
      heartbeatsByWorker
        .get(workerId)
        .map(_.map(_.take(limit)).getOrElse(List.empty))
        .commit

  override def findWorkersWithStaleHeartbeats(
      timeout: Duration
  ): IO[HeartbeatError, List[Worker.Id]] =
    for
      now <- clock.instant

      // Get all worker IDs and their latest heartbeats
      allWorkerHeartbeats <- heartbeatsByWorker.toList.commit

      // Filter workers with stale heartbeats
      staleWorkers = allWorkerHeartbeats.collect {
        case (workerId, heartbeats) if heartbeats.nonEmpty =>
          val latestHeartbeat = heartbeats.head
          val timeSince = Duration.between(latestHeartbeat.receivedAt, now)
          if timeSince.compareTo(timeout) > 0 then Some(workerId) else None
      }.flatten

    yield staleWorkers

  override def findWorkersWithRecentHeartbeats(
      timeout: Duration
  ): IO[HeartbeatError, List[Worker.Id]] =
    for
      now <- clock.instant

      // Get all worker IDs and their latest heartbeats
      allWorkerHeartbeats <- heartbeatsByWorker.toList.commit

      // Filter workers with recent heartbeats
      recentWorkers = allWorkerHeartbeats.collect {
        case (workerId, heartbeats) if heartbeats.nonEmpty =>
          val latestHeartbeat = heartbeats.head
          val timeSince = Duration.between(latestHeartbeat.receivedAt, now)
          if timeSince.compareTo(timeout) < 0 then Some(workerId) else None
      }.flatten

    yield recentWorkers

  override def findByTimeRange(
      from: Instant,
      to: Instant
  ): IO[HeartbeatError, List[Heartbeat]] =
    if from.isAfter(to) then
      ZIO.fail(HeartbeatError.InvalidTimeRange(from, to))
    else
      heartbeatsById.values.commit.map { allHeartbeats =>
        allHeartbeats
          .filter { hb =>
            val receivedAt = hb.receivedAt
            !receivedAt.isBefore(from) && !receivedAt.isAfter(to)
          }
          .toList
          .sortBy(_.receivedAt.toEpochMilli)(Ordering[Long].reverse)
      }

  override def deleteByWorkerId(
      workerId: Worker.Id
  ): IO[HeartbeatError, Unit] =
    (for
      // Get all heartbeats for this worker
      maybeHeartbeats <- heartbeatsByWorker.get(workerId)

      // Delete from primary index
      _ <- maybeHeartbeats match
        case Some(heartbeats) =>
          ZSTM.foreach(heartbeats)(hb => heartbeatsById.delete(hb.id))
        case None =>
          ZSTM.unit

      // Delete from secondary index
      _ <- heartbeatsByWorker.delete(workerId)
    yield ()).commit

  override def countByWorkerId(
      workerId: Worker.Id
  ): IO[HeartbeatError, Long] =
    heartbeatsByWorker
      .get(workerId)
      .map(_.map(_.size.toLong).getOrElse(0L))
      .commit
