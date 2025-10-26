package com.mycompany.worker.internal

import com.mycompany.worker.{Worker, WorkerError}
import zio.*

/**
 * Worker repository interface (package-private).
 *
 * Only accessible within worker package. Returns typed WorkerError for all
 * operations.
 */
private[worker] trait WorkerRepository:

  /** Save or update a worker. */
  def save(worker: Worker): IO[WorkerError, Unit]

  /** Find a worker by ID. */
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]

  /** Check if a worker exists. */
  def exists(id: Worker.Id): IO[WorkerError, Boolean]

  /** Delete a worker by ID. */
  def delete(id: Worker.Id): IO[WorkerError, Unit]

  /** Get all workers with a specific status. */
  def getByStatus(status: Worker.Status): IO[WorkerError, List[Worker]]

  /** Get all workers. */
  def getAll: IO[WorkerError, List[Worker]]

private[worker] object WorkerRepository:
  /** Accessor for use in ZIO environment. */
  def save(worker: Worker): ZIO[WorkerRepository, WorkerError, Unit] =
    ZIO.serviceWithZIO[WorkerRepository](_.save(worker))

  def findById(
      id: Worker.Id
  ): ZIO[WorkerRepository, WorkerError, Option[Worker]] =
    ZIO.serviceWithZIO[WorkerRepository](_.findById(id))

  def exists(id: Worker.Id): ZIO[WorkerRepository, WorkerError, Boolean] =
    ZIO.serviceWithZIO[WorkerRepository](_.exists(id))

  def delete(id: Worker.Id): ZIO[WorkerRepository, WorkerError, Unit] =
    ZIO.serviceWithZIO[WorkerRepository](_.delete(id))

  def getByStatus(
      status: Worker.Status
  ): ZIO[WorkerRepository, WorkerError, List[Worker]] =
    ZIO.serviceWithZIO[WorkerRepository](_.getByStatus(status))

  def getAll: ZIO[WorkerRepository, WorkerError, List[Worker]] =
    ZIO.serviceWithZIO[WorkerRepository](_.getAll)
