package examples.errormodeling.repository

import examples.errormodeling.domain.{Worker, WorkerError}
import zio.*

/** Repository interface for Worker persistence.
  *
  * All operations return ZIO effects with WorkerError as the error type.
  */
trait WorkerRepository:
  /** Check if a worker exists by ID. */
  def exists(id: Worker.Id): IO[WorkerError, Boolean]

  /** Find a worker by ID. */
  def findById(id: Worker.Id): IO[WorkerError, Option[Worker]]

  /** Save a worker (create or update). */
  def save(worker: Worker): IO[WorkerError, Unit]

  /** Delete a worker by ID. */
  def delete(id: Worker.Id): IO[WorkerError, Unit]

  /** Get all workers. */
  def getAll: IO[WorkerError, List[Worker]]

  /** Get workers by status. */
  def getByStatus(status: Worker.Status): IO[WorkerError, List[Worker]]
