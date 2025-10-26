package com.mycompany.worker

import com.mycompany.worker.internal.{WorkerRepository, HeartbeatStore}
import zio.*
import java.time.Instant

/**
 * Worker queries (read operations).
 *
 * Follows CQRS pattern - queries that read state without modification.
 */
object WorkerQueries:

  /**
   * Get a worker by ID.
   */
  def getWorker(id: Worker.Id): ZIO[WorkerRepository, WorkerError, Worker] =
    WorkerRepository.findById(id).someOrFail(WorkerError.WorkerNotFound(id))

  /**
   * Get all active workers.
   */
  def getActiveWorkers: ZIO[WorkerRepository, WorkerError, List[Worker]] =
    WorkerRepository.getByStatus(Worker.Status.Active)

  /**
   * Get all workers with a specific status.
   */
  def getWorkersByStatus(
      status: Worker.Status
  ): ZIO[WorkerRepository, WorkerError, List[Worker]] =
    WorkerRepository.getByStatus(status)

  /**
   * Get all workers.
   */
  def getAllWorkers: ZIO[WorkerRepository, WorkerError, List[Worker]] =
    WorkerRepository.getAll

  /**
   * Get last heartbeat timestamp for a worker.
   */
  def getLastHeartbeat(
      id: Worker.Id
  ): ZIO[WorkerRepository & HeartbeatStore, WorkerError, Instant] =
    for
      // Verify worker exists
      _ <- WorkerRepository
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      // Get last heartbeat
      lastHeartbeat <- HeartbeatStore
        .getLastHeartbeat(id)
        .someOrFail(WorkerError.NoHeartbeatRecorded(id))
    yield lastHeartbeat

  /**
   * Get heartbeat history for a worker.
   */
  def getHeartbeatHistory(
      id: Worker.Id,
      limit: Int = 10
  ): ZIO[WorkerRepository & HeartbeatStore, WorkerError, List[Instant]] =
    for
      // Verify worker exists
      _ <- WorkerRepository
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      // Get heartbeat history
      history <- HeartbeatStore.getHistory(id, limit)
    yield history

  /**
   * Worker with enriched metadata.
   */
  case class WorkerWithMetadata(
      worker: Worker,
      lastHeartbeat: Option[Instant]
  )

  /**
   * Get workers with their configuration metadata.
   *
   * Returns workers filtered by optional criteria.
   */
  def getWorkersWithMetadata(
      status: Option[Worker.Status] = None
  ): ZIO[WorkerRepository & HeartbeatStore, WorkerError, List[
    WorkerWithMetadata
  ]] =
    for
      // Get workers (all or by status)
      workers <- status match
        case Some(s) => WorkerRepository.getByStatus(s)
        case None    => WorkerRepository.getAll

      // Enrich with heartbeat data
      enriched <- ZIO.foreach(workers) { worker =>
        HeartbeatStore
          .getLastHeartbeat(worker.id)
          .map(hb => WorkerWithMetadata(worker, hb))
          .catchAll(_ => ZIO.succeed(WorkerWithMetadata(worker, None)))
      }
    yield enriched
