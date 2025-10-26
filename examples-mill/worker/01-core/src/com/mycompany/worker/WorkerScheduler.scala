package com.mycompany.worker

import com.mycompany.worker.internal.{WorkerRepository, HeartbeatStore}
import zio.*
import java.time.Duration

/**
 * Worker scheduler for periodic background jobs.
 *
 * Handles all time-based state transitions:
 *   - Activating pending workers with heartbeats
 *   - Marking stale workers as offline
 *   - Reactivating offline workers with recent heartbeats
 */
object WorkerScheduler:

  /**
   * Configuration for scheduler.
   */
  case class Config(
      activationCheckInterval: Duration = Duration.ofSeconds(10),
      staleCheckInterval: Duration = Duration.ofSeconds(30),
      reactivationCheckInterval: Duration = Duration.ofSeconds(30),
      heartbeatTimeout: Duration = Duration.ofMinutes(2)
  )

  /**
   * Start all scheduled worker management jobs.
   *
   * Returns a fiber that runs indefinitely until interrupted. Should be
   * started at application startup.
   */
  def startScheduledJobs(
      config: Config
  ): ZIO[
    WorkerRepository & HeartbeatStore & Clock,
    Nothing,
    Fiber.Runtime[Nothing, Nothing]
  ] =
    val jobs = ZIO.collectAllParDiscard(
      List(
        activationJob(config).forkDaemon,
        staleWorkerDetectionJob(config).forkDaemon,
        reactivationJob(config).forkDaemon
      )
    )

    jobs.as(Fiber.unit) // Return a dummy fiber representing all jobs

  /**
   * Activation job - Periodically activates pending workers with heartbeats.
   *
   * Schedule: Every 10 seconds (configurable) Action: Pending -> Active (if
   * heartbeat exists)
   */
  def activationJob(
      config: Config
  ): ZIO[WorkerRepository & HeartbeatStore, Nothing, Nothing] =
    (for
      activated <- WorkerUseCases.activatePendingWorkers()
      _ <- ZIO
        .logInfo(
          s"Activated ${activated.size} pending workers: ${activated.mkString(", ")}"
        )
        .when(activated.nonEmpty)
    yield ())
      .catchAll(error => ZIO.logError(s"Activation job failed: ${error.getMessage}"))
      .schedule(Schedule.fixed(config.activationCheckInterval))
      .unit

  /**
   * Stale worker detection job - Marks workers without recent heartbeats as
   * offline.
   *
   * Schedule: Every 30 seconds (configurable) Action: Active -> Offline (if no
   * heartbeat within timeout)
   */
  def staleWorkerDetectionJob(
      config: Config
  ): ZIO[WorkerRepository & HeartbeatStore, Nothing, Nothing] =
    (for
      markedOffline <- WorkerUseCases.markStaleWorkersOffline(
        config.heartbeatTimeout
      )
      _ <- ZIO
        .logWarning(
          s"Marked ${markedOffline.size} workers as offline: ${markedOffline.mkString(", ")}"
        )
        .when(markedOffline.nonEmpty)
    yield ())
      .catchAll(error =>
        ZIO.logError(s"Stale worker detection job failed: ${error.getMessage}")
      )
      .schedule(Schedule.fixed(config.staleCheckInterval))
      .unit

  /**
   * Reactivation job - Reactivates offline workers that have resumed
   * heartbeats.
   *
   * Schedule: Every 30 seconds (configurable) Action: Offline -> Active (if
   * recent heartbeat exists)
   */
  def reactivationJob(
      config: Config
  ): ZIO[WorkerRepository & HeartbeatStore & Clock, Nothing, Nothing] =
    (for
      reactivated <- WorkerUseCases.reactivateOfflineWorkers(
        config.heartbeatTimeout
      )
      _ <- ZIO
        .logInfo(
          s"Reactivated ${reactivated.size} offline workers: ${reactivated.mkString(", ")}"
        )
        .when(reactivated.nonEmpty)
    yield ())
      .catchAll(error =>
        ZIO.logError(s"Reactivation job failed: ${error.getMessage}")
      )
      .schedule(Schedule.fixed(config.reactivationCheckInterval))
      .unit

  /**
   * One-time check for all scheduled tasks (useful for testing or manual
   * trigger).
   */
  def runAllChecks(
      config: Config
  ): ZIO[WorkerRepository & HeartbeatStore & Clock, WorkerError, SchedulerResult] =
    for
      activated <- WorkerUseCases.activatePendingWorkers()
      markedOffline <- WorkerUseCases.markStaleWorkersOffline(
        config.heartbeatTimeout
      )
      reactivated <- WorkerUseCases.reactivateOfflineWorkers(
        config.heartbeatTimeout
      )
    yield SchedulerResult(activated, markedOffline, reactivated)

  case class SchedulerResult(
      activated: List[Worker.Id],
      markedOffline: List[Worker.Id],
      reactivated: List[Worker.Id]
  )
