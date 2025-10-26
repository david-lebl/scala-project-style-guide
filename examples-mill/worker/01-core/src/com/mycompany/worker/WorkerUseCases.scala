package com.mycompany.worker

import com.mycompany.worker.internal.{WorkerRepository, HeartbeatStore}
import zio.*
import java.time.{Instant, Duration}

/**
 * Worker use cases (command operations).
 *
 * Each use case is a standalone function with minimal dependencies. Follows
 * CQRS pattern - commands that modify state.
 */
object WorkerUseCases:

  // ========== Registration Use Cases ==========

  /**
   * Register a new worker.
   *
   * Use Case: RegisterWorker
   *   - Validates worker doesn't already exist
   *   - Validates configuration
   *   - Creates worker in Pending status
   *   - Saves to repository
   *
   * Auto-activation happens via scheduled job on first heartbeat.
   */
  def registerWorker(
      id: String,
      config: Worker.Config
  ): ZIO[WorkerRepository, WorkerError, Worker] =
    val workerId = Worker.Id(id)

    for
      // Check if worker already exists
      exists <- WorkerRepository.exists(workerId)
      _ <- ZIO.fail(WorkerError.WorkerAlreadyExists(workerId)).when(exists)

      // Validate configuration
      _ <- validateConfiguration(config)

      // Create and save worker
      worker = Worker.create(id, config)
      _ <- WorkerRepository.save(worker)
    yield worker

  /**
   * Unregister a worker.
   *
   * Use Case: UnregisterWorker
   *   - Verifies worker exists
   *   - Deletes worker from repository
   *   - Removes all heartbeat history
   */
  def unregisterWorker(
      id: Worker.Id
  ): ZIO[WorkerRepository & HeartbeatStore, WorkerError, Unit] =
    for
      // Verify worker exists
      _ <- WorkerRepository
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      // Delete worker
      _ <- WorkerRepository.delete(id)

      // Remove heartbeat history
      _ <- HeartbeatStore.remove(id)
    yield ()

  // ========== Heartbeat Use Cases ==========

  /**
   * Record a heartbeat for a worker.
   *
   * Use Case: RecordHeartbeat
   *   - Verifies worker exists
   *   - Records heartbeat with current timestamp
   *
   * Note: Does NOT auto-activate or reactivate workers. State transitions
   * happen via scheduled background jobs.
   */
  def recordHeartbeat(
      id: Worker.Id
  ): ZIO[WorkerRepository & HeartbeatStore & Clock, WorkerError, Unit] =
    for
      // Verify worker exists
      _ <- WorkerRepository
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      // Record heartbeat with current timestamp
      now <- Clock.instant
      _ <- HeartbeatStore.record(id, now)
    yield ()

  // ========== State Management Use Cases ==========

  /**
   * Activate pending workers that have received their first heartbeat.
   *
   * Use Case: ActivatePendingWorkers
   *   - Finds all workers in Pending status
   *   - Checks if they have a heartbeat recorded
   *   - Transitions to Active status if heartbeat exists
   *
   * Called periodically by scheduler.
   */
  def activatePendingWorkers()
      : ZIO[WorkerRepository & HeartbeatStore, WorkerError, List[Worker.Id]] =
    for
      // Get all pending workers
      pendingWorkers <- WorkerRepository.getByStatus(Worker.Status.Pending)

      // For each pending worker, check if heartbeat exists and activate
      activated <- ZIO.foreach(pendingWorkers) { worker =>
        HeartbeatStore.getLastHeartbeat(worker.id).flatMap {
          case Some(_) =>
            // Heartbeat exists, activate worker
            updateWorkerStatus(
              worker.id,
              Worker.Status.Active,
              Some(Worker.Status.Pending)
            ).as(Some(worker.id))
          case None =>
            // No heartbeat yet, keep in Pending
            ZIO.none
        }.catchAll(_ => ZIO.none)
      }
    yield activated.flatten

  /**
   * Detect and mark unresponsive workers as Offline.
   *
   * Use Case: MarkStaleWorkersOffline
   *   - Finds workers with stale heartbeats (beyond timeout)
   *   - Transitions Active workers to Offline status
   *
   * Called periodically by scheduler.
   */
  def markStaleWorkersOffline(
      timeout: Duration
  ): ZIO[WorkerRepository & HeartbeatStore, WorkerError, List[Worker.Id]] =
    for
      // Find workers with stale heartbeats
      staleWorkerIds <- HeartbeatStore.findStaleWorkers(timeout)

      // For each stale worker, mark as offline if currently active
      markedOffline <- ZIO.foreach(staleWorkerIds) { workerId =>
        WorkerRepository.findById(workerId).flatMap {
          case Some(worker) if worker.status == Worker.Status.Active =>
            updateWorkerStatus(
              workerId,
              Worker.Status.Offline,
              Some(Worker.Status.Active)
            ).as(Some(workerId))
          case _ =>
            ZIO.none
        }.catchAll(_ => ZIO.none)
      }
    yield markedOffline.flatten

  /**
   * Reactivate offline workers that have resumed heartbeats.
   *
   * Use Case: ReactivateOfflineWorkers
   *   - Finds all workers in Offline status
   *   - Checks if they have recent heartbeat (within timeout)
   *   - Transitions back to Active status if heartbeat is recent
   *
   * Called periodically by scheduler.
   */
  def reactivateOfflineWorkers(
      timeout: Duration
  ): ZIO[WorkerRepository & HeartbeatStore & Clock, WorkerError, List[
    Worker.Id
  ]] =
    for
      // Get all offline workers
      offlineWorkers <- WorkerRepository.getByStatus(Worker.Status.Offline)

      // Current time for comparison
      now <- Clock.instant

      // For each offline worker, check if heartbeat is recent
      reactivated <- ZIO.foreach(offlineWorkers) { worker =>
        HeartbeatStore.getLastHeartbeat(worker.id).flatMap {
          case Some(lastHeartbeat) =>
            val timeSinceHeartbeat = Duration.between(lastHeartbeat, now)
            if timeSinceHeartbeat.compareTo(timeout) < 0 then
              // Recent heartbeat, reactivate
              updateWorkerStatus(
                worker.id,
                Worker.Status.Active,
                Some(Worker.Status.Offline)
              ).as(Some(worker.id))
            else
              // Still stale, keep offline
              ZIO.none
          case None =>
            ZIO.none
        }.catchAll(_ => ZIO.none)
      }
    yield reactivated.flatten

  /**
   * Update worker status manually.
   *
   * Use Case: UpdateWorkerStatus
   *   - Finds worker by ID
   *   - Validates expected status (CAS check)
   *   - Validates state transition
   *   - Updates status
   */
  def updateWorkerStatus(
      id: Worker.Id,
      newStatus: Worker.Status,
      expectedStatus: Option[Worker.Status] = None
  ): ZIO[WorkerRepository, WorkerError, Worker] =
    for
      // Find worker
      worker <- WorkerRepository
        .findById(id)
        .someOrFail(WorkerError.WorkerNotFound(id))

      // Validate expected status (CAS check)
      _ <- expectedStatus match
        case Some(expected) if worker.status != expected =>
          ZIO.fail(WorkerError.InvalidWorkerState(id, worker.status, expected))
        case _ =>
          ZIO.unit

      // Validate state transition
      _ <- validateStateTransition(worker.status, newStatus, id)

      // Update worker status
      updatedWorker = worker.copy(status = newStatus)
      _ <- WorkerRepository.save(updatedWorker)
    yield updatedWorker

  // ========== Private Helper Functions ==========

  private def validateConfiguration(
      config: Worker.Config
  ): IO[WorkerError, Unit] =
    for
      // Validate region
      _ <- ZIO
        .fail(WorkerError.InvalidConfiguration("region", "cannot be empty"))
        .when(config.region.isEmpty)

      // Validate capacity
      _ <- ZIO
        .fail(WorkerError.InvalidConfiguration("capacity", "must be positive"))
        .when(config.capacity <= 0)
    yield ()

  private def validateStateTransition(
      currentStatus: Worker.Status,
      newStatus: Worker.Status,
      workerId: Worker.Id
  ): IO[WorkerError, Unit] =
    val validTransitions = Map(
      Worker.Status.Pending -> Set(Worker.Status.Active, Worker.Status.Failed),
      Worker.Status.Active -> Set(Worker.Status.Offline, Worker.Status.Failed),
      Worker.Status.Offline -> Set(
        Worker.Status.Active,
        Worker.Status.Failed
      ),
      Worker.Status.Failed -> Set.empty[Worker.Status]
    )

    val isValid = validTransitions
      .get(currentStatus)
      .exists(_.contains(newStatus))

    ZIO
      .fail(
        WorkerError.InvalidStateTransition(workerId, currentStatus, newStatus)
      )
      .unless(isValid || currentStatus == newStatus)
