package examples.errormodeling.service

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.repository.{WorkerRepository, HeartbeatRepository}
import zio.*
import java.time.Instant

/** Worker service interface.
  *
  * All operations use WorkerError as the error type, enabling easy composition.
  */
trait WorkerService:
  /** Register a new worker. */
  def register(id: String, config: Map[String, String]): IO[WorkerError, Worker]

  /** Unregister a worker. */
  def unregister(id: Worker.Id): IO[WorkerError, Unit]

  /** Record a heartbeat for a worker. Auto-activates pending workers. */
  def heartbeat(id: Worker.Id): IO[WorkerError, Unit]

  /** Get a worker by ID. */
  def getWorker(id: Worker.Id): IO[WorkerError, Worker]

  /** Update worker status with optional expected state check. */
  def updateStatus(
      id: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status] = None
  ): IO[WorkerError, Worker]

  /** Activate a worker (transition from Pending to Active). */
  def activate(id: Worker.Id): IO[WorkerError, Worker]

object WorkerService:
  /** ZLayer for WorkerService. */
  def layer
      : ZLayer[WorkerRepository & HeartbeatRepository, Nothing, WorkerService] =
    ZLayer.fromFunction(WorkerServiceLive.apply)

/** Live implementation of WorkerService.
  *
  * Demonstrates comprehensive error handling with domain errors.
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

  override def activate(id: Worker.Id): IO[WorkerError, Worker] =
    updateStatus(id, Worker.Status.Active, Some(Worker.Status.Pending))

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
