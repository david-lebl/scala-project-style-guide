package examples.errormodeling.repository

import examples.errormodeling.domain.{Worker, WorkerError}
import zio.*

/** In-memory implementation of WorkerRepository.
  *
  * Uses a Ref to maintain worker state for testing and examples.
  */
final class InMemoryWorkerRepository(
    workersRef: Ref[Map[Worker.Id, Worker]]
) extends WorkerRepository:

  override def exists(id: Worker.Id): IO[WorkerError, Boolean] =
    workersRef.get.map(_.contains(id))

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    workersRef.get.map(_.get(id))

  override def save(worker: Worker): IO[WorkerError, Unit] =
    workersRef.update(_ + (worker.id -> worker))

  override def delete(id: Worker.Id): IO[WorkerError, Unit] =
    workersRef.update(_ - id)

  override def getAll: IO[WorkerError, List[Worker]] =
    workersRef.get.map(_.values.toList)

  override def getByStatus(
      status: Worker.Status
  ): IO[WorkerError, List[Worker]] =
    workersRef.get.map(_.values.filter(_.status == status).toList)

object InMemoryWorkerRepository:
  /** Create a layer for the in-memory worker repository. */
  def layer: ZLayer[Any, Nothing, WorkerRepository] =
    ZLayer.fromZIO(
      Ref
        .make(Map.empty[Worker.Id, Worker])
        .map(new InMemoryWorkerRepository(_))
    )
