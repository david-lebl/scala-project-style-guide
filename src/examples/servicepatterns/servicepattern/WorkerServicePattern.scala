package examples.servicepatterns.servicepattern

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.repository.{WorkerRepository, HeartbeatRepository}
import zio.*
import java.time.{Instant, Duration}

/** Service Pattern Example.
  *
  * Characteristics:
  * - Methods grouped together in a cohesive service
  * - Single point of dependency injection
  * - All dependencies required even if only using one method
  * - Good for related operations that share state
  */
trait WorkerService:
  /** Register a new worker. */
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]

  /** Unregister a worker. */
  def unregister(id: Worker.Id): IO[WorkerError, Unit]

  /** Record a heartbeat for a worker. */
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]

  /** Get a worker by ID. */
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

  /** Get all active workers. */
  def getActiveWorkers: IO[WorkerError, List[Worker]]

  /** Get all workers with a specific status. */
  def getWorkersByStatus(status: Worker.Status): IO[WorkerError, List[Worker]]

  /** Update worker status. */
  def updateStatus(
      id: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status] = None
  ): IO[WorkerError, Worker]

  /** Cleanup stale workers (mark as Offline). */
  def cleanupStaleWorkers(timeout: Duration): IO[WorkerError, List[Worker.Id]]

object WorkerService:
  /** ZLayer for WorkerService. */
  def layer: ZLayer[WorkerRepository & HeartbeatRepository, Nothing, WorkerService] =
    ZLayer.fromFunction(WorkerServiceLive.apply)

/** Live implementation of WorkerService.
  *
  * All dependencies are injected at the service level.
  */
final case class WorkerServiceLive(
    workerRepo: WorkerRepository,
    heartbeatRepo: HeartbeatRepository
) extends WorkerService:

  override def register(
      id: String,
      config: Map[String, String]
  ): IO[WorkerError, Worker] =
    val workerId = Worker.Id(id)
    workerRepo.exists(workerId).flatMap {
      case true =>
        ZIO.fail(WorkerError.WorkerAlreadyExists(workerId))
      case false =>
        validateConfiguration(config).flatMap { validConfig =>
          val worker = Worker.create(id, validConfig)
          workerRepo
            .save(worker)
            .mapError(e => WorkerError.RepositoryError("save", e))
            .as(worker)
        }
    }

  override def unregister(id: Worker.Id): IO[WorkerError, Unit] =
    for
      _ <- workerRepo
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))
      _ <- workerRepo
        .delete(id)
        .mapError(e => WorkerError.RepositoryError("delete", e))
      _ <- heartbeatRepo
        .remove(id)
        .mapError(e => WorkerError.RepositoryError("remove_heartbeat", e))
    yield ()

  override def heartbeat(id: Worker.Id): IO[WorkerError, Unit] =
    for
      worker <- workerRepo
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      _ <- heartbeatRepo
        .record(id, Instant.now())
        .mapError(e => WorkerError.HeartbeatRecordingFailed(id, e.getMessage))

      // Auto-activate pending workers on first heartbeat
      _ <-
        if worker.status == Worker.Status.Pending then
          updateStatus(id, Worker.Status.Active, Some(Worker.Status.Pending))
        else ZIO.unit
    yield ()

  override def getWorker(id: Worker.Id): IO[WorkerError, Worker] =
    workerRepo
      .findById(id)
      .someOrFail(WorkerError.WorkerNotFound(id))

  override def getActiveWorkers: IO[WorkerError, List[Worker]] =
    workerRepo.getByStatus(Worker.Status.Active)

  override def getWorkersByStatus(
      status: Worker.Status
  ): IO[WorkerError, List[Worker]] =
    workerRepo.getByStatus(status)

  override def updateStatus(
      id: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status]
  ): IO[WorkerError, Worker] =
    for
      worker <- workerRepo
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      _ <- expectedStatus match
        case Some(expected) if worker.status != expected =>
          ZIO.fail(WorkerError.InvalidWorkerState(id, worker.status, expected))
        case _ =>
          ZIO.unit

      updatedWorker = worker.copy(status = newStatus)
      _ <- workerRepo
        .save(updatedWorker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield updatedWorker

  override def cleanupStaleWorkers(
      timeout: Duration
  ): IO[WorkerError, List[Worker.Id]] =
    for
      staleWorkerIds <- heartbeatRepo.findStaleWorkers(timeout)
      _ <- ZIO.foreach(staleWorkerIds) { workerId =>
        workerRepo.findById(workerId).flatMap {
          case Some(worker)
              if worker.status == Worker.Status.Active || worker.status == Worker.Status.Pending =>
            updateStatus(workerId, Worker.Status.Offline, None).unit
          case _ =>
            ZIO.unit
        }.catchAll(_ => ZIO.unit)
      }
    yield staleWorkerIds

  // Private helper methods

  private def validateConfiguration(
      config: Map[String, String]
  ): IO[WorkerError, Map[String, String]] =
    ZIO
      .foreach(List("region", "capacity")) { field =>
        ZIO
          .fromOption(config.get(field))
          .orElseFail(
            WorkerError.InvalidConfiguration(field, "required field missing")
          )
      }
      .as(config)