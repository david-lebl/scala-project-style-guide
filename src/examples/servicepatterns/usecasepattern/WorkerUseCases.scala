package examples.servicepatterns.usecasepattern

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.repository.{WorkerRepository, HeartbeatRepository}
import zio.*
import java.time.{Instant, Duration}

/** Use Case Pattern Example.
  *
  * Characteristics:
  * - Each use case is a standalone function
  * - Can provide only needed dependencies per use case
  * - Easy to compose use cases together
  * - Input/Output case classes are OPTIONAL (not used here for simplicity)
  */
object WorkerUseCases:

  /** Dependencies required across use cases. */
  type WorkerDependencies = WorkerRepository & HeartbeatRepository

  /** Register a new worker.
    *
    * Dependencies: WorkerRepository
    */
  def registerWorker(
      id: String,
      config: Map[String, String]
  ): ZIO[WorkerRepository, WorkerError, Worker] =
    val workerId = Worker.Id(id)
    for
      repo <- ZIO.service[WorkerRepository]
      exists <- repo.exists(workerId)
      _ <- ZIO.when(exists)(
        ZIO.fail(WorkerError.WorkerAlreadyExists(workerId))
      )
      validConfig <- validateConfiguration(config)
      worker = Worker.create(id, validConfig)
      _ <- repo
        .save(worker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield worker

  /** Unregister a worker.
    *
    * Dependencies: WorkerRepository, HeartbeatRepository
    */
  def unregisterWorker(
      id: Worker.Id
  ): ZIO[WorkerRepository & HeartbeatRepository, WorkerError, Unit] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]
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

  /** Record a heartbeat for a worker.
    *
    * Dependencies: WorkerRepository, HeartbeatRepository
    */
  def recordHeartbeat(
      id: Worker.Id
  ): ZIO[WorkerRepository & HeartbeatRepository, WorkerError, Unit] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]

      worker <- workerRepo
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      _ <- heartbeatRepo
        .record(id, Instant.now())
        .mapError(e => WorkerError.HeartbeatRecordingFailed(id, e.getMessage))

      // Auto-activate pending workers on first heartbeat
      _ <-
        if worker.status == Worker.Status.Pending then
          updateWorkerStatus(id, Worker.Status.Active, Some(Worker.Status.Pending))
        else ZIO.unit
    yield ()

  /** Get a worker by ID.
    *
    * Dependencies: WorkerRepository
    */
  def getWorker(id: Worker.Id): ZIO[WorkerRepository, WorkerError, Worker] =
    ZIO.serviceWithZIO[WorkerRepository](
      _.findById(id).someOrFail(WorkerError.WorkerNotFound(id))
    )

  /** Get all active workers.
    *
    * Dependencies: WorkerRepository
    */
  def getActiveWorkers: ZIO[WorkerRepository, WorkerError, List[Worker]] =
    ZIO.serviceWithZIO[WorkerRepository](
      _.getByStatus(Worker.Status.Active)
    )

  /** Get workers by status.
    *
    * Dependencies: WorkerRepository
    */
  def getWorkersByStatus(
      status: Worker.Status
  ): ZIO[WorkerRepository, WorkerError, List[Worker]] =
    ZIO.serviceWithZIO[WorkerRepository](
      _.getByStatus(status)
    )

  /** Update worker status.
    *
    * Dependencies: WorkerRepository
    */
  def updateWorkerStatus(
      id: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status] = None
  ): ZIO[WorkerRepository, WorkerError, Worker] =
    for
      repo <- ZIO.service[WorkerRepository]
      worker <- repo.findById(id).someOrFail(WorkerError.WorkerNotFound(id))

      _ <- expectedStatus match
        case Some(expected) if worker.status != expected =>
          ZIO.fail(WorkerError.InvalidWorkerState(id, worker.status, expected))
        case _ =>
          ZIO.unit

      updatedWorker = worker.copy(status = newStatus)
      _ <- repo
        .save(updatedWorker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield updatedWorker

  /** Detect and cleanup stale workers.
    *
    * Dependencies: WorkerRepository, HeartbeatRepository
    */
  def cleanupStaleWorkers(
      timeout: Duration
  ): ZIO[WorkerRepository & HeartbeatRepository, WorkerError, List[Worker.Id]] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]

      staleWorkerIds <- heartbeatRepo.findStaleWorkers(timeout)

      _ <- ZIO.foreach(staleWorkerIds) { workerId =>
        workerRepo.findById(workerId).flatMap {
          case Some(worker)
              if worker.status == Worker.Status.Active || worker.status == Worker.Status.Pending =>
            updateWorkerStatus(workerId, Worker.Status.Offline, None).unit
          case _ =>
            ZIO.unit
        }.catchAll(_ => ZIO.unit)
      }
    yield staleWorkerIds

  // Private helper functions

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