package com.mycompany.worker.app

import com.mycompany.worker.*
import com.mycompany.worker.impl.WorkerLayers
import zio.*
import zio.http.*

/**
 * Worker management application.
 *
 * Features:
 *   - REST API for worker management
 *   - Background scheduler for state transitions
 *   - In-memory storage for demo (can swap with PostgreSQL)
 */
object WorkerApp extends ZIOAppDefault:

  /**
   * Main application logic.
   */
  val app: ZIO[Any, Throwable, Unit] =
    for
      // Start the scheduler in the background
      _ <- WorkerScheduler.startScheduledJobs(WorkerScheduler.Config()).fork

      _ <- ZIO.logInfo("Worker scheduler started")
      _ <- ZIO.logInfo("Starting HTTP server on port 8080...")

      // Start HTTP server
      _ <- Server.serve(WorkerLayers.httpRoutes)
    yield ()

  /**
   * Run the application with all dependencies.
   */
  override def run: ZIO[Any, Any, Any] =
    app.provide(
      // Use in-memory storage
      WorkerLayers.inMemory,
      // HTTP server configuration
      Server.defaultWithPort(8080),
      // Clock for heartbeat timestamps
      ZLayer.succeed(Clock.ClockLive)
    )
