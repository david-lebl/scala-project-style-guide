package examples.servicepatterns.usecasepattern

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.repository.{WorkerRepository, HeartbeatRepository}
import zio.*
import java.time.{Instant, Duration}

/** Use Case Pattern with Input/Output DTOs.
  *
  * This variation uses explicit case classes for inputs and outputs.
  * Good for:
  * - Complex use cases with many parameters
  * - API boundaries
  * - Self-documenting code
  * - CQRS patterns
  */
object WorkerUseCasesWithDTO:

  type WorkerDependencies = WorkerRepository & HeartbeatRepository

  // Input/Output case classes for RegisterWorker

  case class RegisterWorkerInput(
      id: String,
      config: Map[String, String]
  )

  case class RegisterWorkerOutput(
      worker: Worker,
      registeredAt: Instant
  )

  /** Register a new worker with explicit Input/Output. */
  def registerWorker(
      input: RegisterWorkerInput
  ): ZIO[WorkerRepository, WorkerError, RegisterWorkerOutput] =
    val workerId = Worker.Id(input.id)
    for
      repo <- ZIO.service[WorkerRepository]
      exists <- repo.exists(workerId)
      _ <- ZIO.when(exists)(
        ZIO.fail(WorkerError.WorkerAlreadyExists(workerId))
      )
      validConfig <- validateConfiguration(input.config)
      worker = Worker.create(input.id, validConfig)
      _ <- repo
        .save(worker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield RegisterWorkerOutput(worker, worker.registeredAt)

  // Input/Output case classes for RecordHeartbeat

  case class RecordHeartbeatInput(
      workerId: Worker.Id
  )

  case class RecordHeartbeatOutput(
      workerId: Worker.Id,
      recordedAt: Instant,
      workerStatus: Worker.Status
  )

  /** Record heartbeat with explicit Input/Output. */
  def recordHeartbeat(
      input: RecordHeartbeatInput
  ): ZIO[
    WorkerRepository & HeartbeatRepository,
    WorkerError,
    RecordHeartbeatOutput
  ] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]

      worker <- workerRepo
        .findById(input.workerId)
        .someOrFail(WorkerError.WorkerNotFound(input.workerId))

      now <- Clock.instant
      _ <- heartbeatRepo
        .record(input.workerId, now)
        .mapError(e =>
          WorkerError.HeartbeatRecordingFailed(input.workerId, e.getMessage)
        )

      // Auto-activate pending workers
      finalWorker <-
        if worker.status == Worker.Status.Pending then
          updateWorkerStatus(
            UpdateWorkerStatusInput(
              input.workerId,
              Worker.Status.Active,
              Some(Worker.Status.Pending)
            )
          ).map(_.worker)
        else ZIO.succeed(worker)
    yield RecordHeartbeatOutput(input.workerId, now, finalWorker.status)

  // Input/Output case classes for GetWorker

  case class GetWorkerInput(
      workerId: Worker.Id
  )

  case class GetWorkerOutput(
      worker: Worker,
      lastHeartbeat: Option[Instant]
  )

  /** Get worker with additional heartbeat info. */
  def getWorker(
      input: GetWorkerInput
  ): ZIO[
    WorkerRepository & HeartbeatRepository,
    WorkerError,
    GetWorkerOutput
  ] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]

      worker <- workerRepo
        .findById(input.workerId)
        .someOrFail(WorkerError.WorkerNotFound(input.workerId))

      lastHeartbeat <- heartbeatRepo.getLastHeartbeat(input.workerId)
    yield GetWorkerOutput(worker, lastHeartbeat)

  // Input/Output case classes for ListWorkersByStatus

  case class ListWorkersByStatusInput(
      status: Worker.Status
  )

  case class ListWorkersByStatusOutput(
      workers: List[Worker],
      count: Int
  )

  /** List workers by status with count. */
  def listWorkersByStatus(
      input: ListWorkersByStatusInput
  ): ZIO[WorkerRepository, WorkerError, ListWorkersByStatusOutput] =
    for
      repo <- ZIO.service[WorkerRepository]
      workers <- repo.getByStatus(input.status)
    yield ListWorkersByStatusOutput(workers, workers.length)

  // Input/Output case classes for DetectStaleWorkers

  case class DetectStaleWorkersInput(
      timeoutDuration: Duration
  )

  case class DetectStaleWorkersOutput(
      staleWorkerIds: List[Worker.Id],
      markedOffline: Int
  )

  /** Detect and mark stale workers offline. */
  def detectStaleWorkers(
      input: DetectStaleWorkersInput
  ): ZIO[
    WorkerRepository & HeartbeatRepository,
    WorkerError,
    DetectStaleWorkersOutput
  ] =
    for
      workerRepo <- ZIO.service[WorkerRepository]
      heartbeatRepo <- ZIO.service[HeartbeatRepository]

      staleWorkerIds <- heartbeatRepo.findStaleWorkers(input.timeoutDuration)

      markedCount <- ZIO.foldLeft(staleWorkerIds)(0) { (count, workerId) =>
        workerRepo.findById(workerId).flatMap {
          case Some(worker)
              if worker.status == Worker.Status.Active || worker.status == Worker.Status.Pending =>
            updateWorkerStatus(
              UpdateWorkerStatusInput(workerId, Worker.Status.Offline, None)
            ).as(count + 1)
          case _ =>
            ZIO.succeed(count)
        }.catchAll(_ => ZIO.succeed(count))
      }
    yield DetectStaleWorkersOutput(staleWorkerIds, markedCount)

  // Input/Output case classes for UpdateWorkerStatus

  case class UpdateWorkerStatusInput(
      workerId: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status]
  )

  case class UpdateWorkerStatusOutput(
      worker: Worker,
      previousStatus: Worker.Status
  )

  /** Update worker status with previous status tracking. */
  def updateWorkerStatus(
      input: UpdateWorkerStatusInput
  ): ZIO[WorkerRepository, WorkerError, UpdateWorkerStatusOutput] =
    for
      repo <- ZIO.service[WorkerRepository]
      worker <- repo
        .findById(input.workerId)
        .someOrFail(WorkerError.WorkerNotFound(input.workerId))

      _ <- input.expectedStatus match
        case Some(expected) if worker.status != expected =>
          ZIO.fail(
            WorkerError
              .InvalidWorkerState(input.workerId, worker.status, expected)
          )
        case _ =>
          ZIO.unit

      previousStatus = worker.status
      updatedWorker = worker.copy(status = input.newStatus)
      _ <- repo
        .save(updatedWorker)
        .mapError(e => WorkerError.RepositoryError("save", e))
    yield UpdateWorkerStatusOutput(updatedWorker, previousStatus)

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