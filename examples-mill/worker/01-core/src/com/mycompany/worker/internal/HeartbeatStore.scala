package com.mycompany.worker.internal

import com.mycompany.worker.{Worker, WorkerError}
import zio.*
import java.time.{Instant, Duration}

/**
 * Heartbeat storage interface (package-private).
 *
 * Returns typed WorkerError for all operations.
 */
private[worker] trait HeartbeatStore:

  /** Record a heartbeat for a worker. */
  def record(id: Worker.Id, timestamp: Instant): IO[WorkerError, Unit]

  /** Get the last heartbeat timestamp for a worker. */
  def getLastHeartbeat(id: Worker.Id): IO[WorkerError, Option[Instant]]

  /** Remove all heartbeat records for a worker. */
  def remove(id: Worker.Id): IO[WorkerError, Unit]

  /** Find workers with stale heartbeats (older than timeout). */
  def findStaleWorkers(timeout: Duration): IO[WorkerError, List[Worker.Id]]

  /** Get heartbeat history for a worker. */
  def getHistory(id: Worker.Id, limit: Int = 10): IO[WorkerError, List[Instant]]

private[worker] object HeartbeatStore:
  def record(
      id: Worker.Id,
      timestamp: Instant
  ): ZIO[HeartbeatStore, WorkerError, Unit] =
    ZIO.serviceWithZIO[HeartbeatStore](_.record(id, timestamp))

  def getLastHeartbeat(
      id: Worker.Id
  ): ZIO[HeartbeatStore, WorkerError, Option[Instant]] =
    ZIO.serviceWithZIO[HeartbeatStore](_.getLastHeartbeat(id))

  def remove(id: Worker.Id): ZIO[HeartbeatStore, WorkerError, Unit] =
    ZIO.serviceWithZIO[HeartbeatStore](_.remove(id))

  def findStaleWorkers(
      timeout: Duration
  ): ZIO[HeartbeatStore, WorkerError, List[Worker.Id]] =
    ZIO.serviceWithZIO[HeartbeatStore](_.findStaleWorkers(timeout))

  def getHistory(
      id: Worker.Id,
      limit: Int
  ): ZIO[HeartbeatStore, WorkerError, List[Instant]] =
    ZIO.serviceWithZIO[HeartbeatStore](_.getHistory(id, limit))
