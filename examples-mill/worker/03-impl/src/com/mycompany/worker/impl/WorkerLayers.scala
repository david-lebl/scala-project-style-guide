package com.mycompany.worker.impl

import com.mycompany.worker.internal.{
  WorkerRepository,
  HeartbeatStore,
  InMemoryWorkerRepository,
  InMemoryHeartbeatStore
}
import com.mycompany.worker.db.{PostgresWorkerRepository, PostgresHeartbeatStore}
import com.mycompany.worker.http.WorkerRoutes
import zio.*
import zio.http.*
import javax.sql.DataSource

/**
 * Pre-wired layer bundles for worker module.
 *
 * Provides convenient layer composition for different environments.
 */
object WorkerLayers:

  /**
   * Production configuration with PostgreSQL.
   *
   * Requires: DataSource Provides: WorkerRepository & HeartbeatStore
   */
  val production: ZLayer[DataSource, Nothing, WorkerRepository & HeartbeatStore] =
    ZLayer.make[WorkerRepository & HeartbeatStore](
      PostgresWorkerRepository.layer,
      PostgresHeartbeatStore.layer
    )

  /**
   * In-memory configuration for testing and development.
   *
   * Requires: Nothing Provides: WorkerRepository & HeartbeatStore
   */
  val inMemory: ZLayer[Any, Nothing, WorkerRepository & HeartbeatStore] =
    ZLayer.make[WorkerRepository & HeartbeatStore](
      InMemoryWorkerRepository.layer,
      InMemoryHeartbeatStore.layer
    )

  /**
   * HTTP routes for worker management.
   *
   * Requires: WorkerRepository & HeartbeatStore & Clock
   */
  def httpRoutes: Routes[WorkerRepository & HeartbeatStore & Clock, Nothing] =
    WorkerRoutes.routes
