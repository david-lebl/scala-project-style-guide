package com.mycompany.worker.internal

import com.mycompany.worker.{Worker, WorkerError}
import zio.*
import java.time.{Instant, Duration}

/**
 * In-memory implementation of HeartbeatStore.
 *
 * Returns typed WorkerError for all operations. Keeps last 100 heartbeats per
 * worker.
 */
private[worker] final case class InMemoryHeartbeatStore(
    storeRef: Ref[Map[Worker.Id, List[Instant]]]
) extends HeartbeatStore:

  override def record(id: Worker.Id, timestamp: Instant): IO[WorkerError, Unit] =
    storeRef.update { store =>
      val history = store.getOrElse(id, List.empty)
      store + (id -> (timestamp :: history).take(100)) // Keep last 100
    }

  override def getLastHeartbeat(
      id: Worker.Id
  ): IO[WorkerError, Option[Instant]] =
    storeRef.get.map(_.get(id).flatMap(_.headOption))

  override def remove(id: Worker.Id): IO[WorkerError, Unit] =
    storeRef.update(_ - id)

  override def findStaleWorkers(
      timeout: Duration
  ): IO[WorkerError, List[Worker.Id]] =
    for
      now <- Clock.instant
      store <- storeRef.get
      stale = store.collect {
        case (id, timestamps) if timestamps.headOption.exists { lastSeen =>
          Duration.between(lastSeen, now).compareTo(timeout) >= 0
        } => id
      }.toList
    yield stale

  override def getHistory(
      id: Worker.Id,
      limit: Int
  ): IO[WorkerError, List[Instant]] =
    storeRef.get.map(_.getOrElse(id, List.empty).take(limit))

private[worker] object InMemoryHeartbeatStore:
  def layer: ZLayer[Any, Nothing, HeartbeatStore] =
    ZLayer.fromZIO(
      Ref
        .make(Map.empty[Worker.Id, List[Instant]])
        .map(InMemoryHeartbeatStore(_))
    )
