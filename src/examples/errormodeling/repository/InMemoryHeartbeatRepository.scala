package examples.errormodeling.repository

import examples.errormodeling.domain.{Worker, WorkerError}
import zio.*
import java.time.{Instant, Duration}

/** In-memory implementation of HeartbeatRepository.
  *
  * Uses a Ref to maintain heartbeat timestamps for testing and examples.
  */
final class InMemoryHeartbeatRepository(
    heartbeatsRef: Ref[Map[Worker.Id, Instant]]
) extends HeartbeatRepository:

  override def record(
      workerId: Worker.Id,
      timestamp: Instant
  ): IO[WorkerError, Unit] =
    heartbeatsRef.update(_ + (workerId -> timestamp))

  override def getLastHeartbeat(
      workerId: Worker.Id
  ): IO[WorkerError, Option[Instant]] =
    heartbeatsRef.get.map(_.get(workerId))

  override def findStaleWorkers(
      timeout: Duration
  ): IO[WorkerError, List[Worker.Id]] =
    for
      now <- Clock.instant
      heartbeats <- heartbeatsRef.get
      cutoff = now.minus(timeout)
      staleIds = heartbeats.collect {
        case (id, lastSeen) if lastSeen.isBefore(cutoff) => id
      }.toList
    yield staleIds

  override def remove(workerId: Worker.Id): IO[WorkerError, Unit] =
    heartbeatsRef.update(_ - workerId)

object InMemoryHeartbeatRepository:
  /** Create a layer for the in-memory heartbeat repository. */
  def layer: ZLayer[Any, Nothing, HeartbeatRepository] =
    ZLayer.fromZIO(
      Ref
        .make(Map.empty[Worker.Id, Instant])
        .map(new InMemoryHeartbeatRepository(_))
    )
