package com.mycompany.worker.internal

import com.mycompany.worker.{Worker, WorkerError}
import zio.*

/**
 * In-memory implementation of WorkerRepository.
 *
 * Returns typed WorkerError for all operations. Used for testing and
 * development.
 */
private[worker] final case class InMemoryWorkerRepository(
    storeRef: Ref[Map[Worker.Id, Worker]]
) extends WorkerRepository:

  override def save(worker: Worker): IO[WorkerError, Unit] =
    storeRef.update(_ + (worker.id -> worker))

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    storeRef.get.map(_.get(id))

  override def exists(id: Worker.Id): IO[WorkerError, Boolean] =
    storeRef.get.map(_.contains(id))

  override def delete(id: Worker.Id): IO[WorkerError, Unit] =
    storeRef.get.flatMap { store =>
      if store.contains(id) then storeRef.update(_ - id)
      else ZIO.fail(WorkerError.WorkerNotFound(id))
    }

  override def getByStatus(
      status: Worker.Status
  ): IO[WorkerError, List[Worker]] =
    storeRef.get.map(_.values.filter(_.status == status).toList)

  override def getAll: IO[WorkerError, List[Worker]] =
    storeRef.get.map(_.values.toList)

private[worker] object InMemoryWorkerRepository:
  def layer: ZLayer[Any, Nothing, WorkerRepository] =
    ZLayer.fromZIO(
      Ref
        .make(Map.empty[Worker.Id, Worker])
        .map(InMemoryWorkerRepository(_))
    )
