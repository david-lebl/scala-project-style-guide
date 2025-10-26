package com.mycompany.worker.http

import com.mycompany.worker.*
import com.mycompany.worker.internal.{WorkerRepository, HeartbeatStore}
import com.mycompany.worker.http.dto.WorkerDTOs.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * REST API routes for worker management.
 *
 * Endpoints:
 *   - POST /workers - Register a new worker
 *   - DELETE /workers/:id - Unregister a worker
 *   - POST /workers/:id/heartbeat - Record heartbeat
 *   - GET /workers/:id - Get worker by ID
 *   - GET /workers - Get all workers (with optional status filter)
 *   - GET /workers/active - Get active workers
 *   - PUT /workers/:id/status - Update worker status
 *   - POST /scheduler/run - Manually trigger scheduler checks
 */
object WorkerRoutes:

  val routes: Routes[WorkerRepository & HeartbeatStore & Clock, Nothing] =
    Routes(
      // POST /workers - Register worker
      Method.POST / "workers" -> handler { (req: Request) =>
        (for
          dto <- req.body.to[RegisterWorkerRequest]
          worker <- WorkerUseCases.registerWorker(dto.id, dto.toConfig)
          response = WorkerResponse.fromWorker(worker)
        yield Response.json(response.toJson))
          .catchAll(handleError)
      },
      // DELETE /workers/:id - Unregister worker
      Method.DELETE / "workers" / string("id") -> handler {
        (id: String, _: Request) =>
          (for
            _ <- WorkerUseCases.unregisterWorker(Worker.Id(id))
          yield Response.ok)
            .catchAll(handleError)
      },
      // POST /workers/:id/heartbeat - Record heartbeat
      Method.POST / "workers" / string("id") / "heartbeat" -> handler {
        (id: String, _: Request) =>
          (for
            _ <- WorkerUseCases.recordHeartbeat(Worker.Id(id))
            now <- Clock.instant
            response = HeartbeatResponse(id, true, now.toString)
          yield Response.json(response.toJson))
            .catchAll(handleError)
      },
      // GET /workers/:id - Get worker by ID
      Method.GET / "workers" / string("id") -> handler {
        (id: String, _: Request) =>
          (for
            worker <- WorkerQueries.getWorker(Worker.Id(id))
            response = WorkerResponse.fromWorker(worker)
          yield Response.json(response.toJson))
            .catchAll(handleError)
      },
      // GET /workers/active - Get active workers
      Method.GET / "workers" / "active" -> handler { (_: Request) =>
        (for
          workers <- WorkerQueries.getActiveWorkers
          response = workers.map(WorkerResponse.fromWorker)
        yield Response.json(response.toJson))
          .catchAll(handleError)
      },
      // GET /workers - Get all workers (with optional status filter)
      Method.GET / "workers" -> handler { (req: Request) =>
        (for
          statusParam <- ZIO.succeed(req.url.queryParams.get("status").flatMap(_.headOption))
          workers <- statusParam match
            case Some(statusStr) =>
              Worker.Status.valueOf(statusStr) match
                case status: Worker.Status =>
                  WorkerQueries.getWorkersByStatus(status)
                case _ =>
                  ZIO.fail(
                    WorkerError.InvalidConfiguration(
                      "status",
                      s"Invalid status: $statusStr"
                    )
                  )
            case None =>
              WorkerQueries.getAllWorkers
          response = workers.map(WorkerResponse.fromWorker)
        yield Response.json(response.toJson))
          .catchAll(handleError)
      },
      // GET /workers/:id/metadata - Get worker with metadata
      Method.GET / "workers" / string("id") / "metadata" -> handler {
        (id: String, _: Request) =>
          (for
            worker <- WorkerQueries.getWorker(Worker.Id(id))
            lastHeartbeat <- WorkerQueries
              .getLastHeartbeat(Worker.Id(id))
              .option
            response = WorkerWithMetadataResponse(
              id = worker.id.value,
              region = worker.config.region,
              capacity = worker.config.capacity,
              capabilities = worker.config.capabilities,
              metadata = worker.config.metadata,
              status = worker.status.toString,
              registeredAt = worker.registeredAt.toString,
              lastHeartbeatAt = lastHeartbeat.map(_.toString)
            )
          yield Response.json(response.toJson))
            .catchAll(handleError)
      },
      // PUT /workers/:id/status - Update worker status
      Method.PUT / "workers" / string("id") / "status" -> handler {
        (id: String, req: Request) =>
          (for
            dto <- req.body.to[UpdateStatusRequest]
            oldWorker <- WorkerQueries.getWorker(Worker.Id(id))
            newStatus = Worker.Status.valueOf(dto.status)
            expectedStatus = dto.expectedStatus.map(Worker.Status.valueOf)
            updatedWorker <- WorkerUseCases.updateWorkerStatus(
              Worker.Id(id),
              newStatus,
              expectedStatus
            )
            response = StatusUpdateResponse(
              id,
              oldWorker.status.toString,
              updatedWorker.status.toString
            )
          yield Response.json(response.toJson))
            .catchAll(handleError)
      },
      // POST /scheduler/run - Manually trigger scheduler
      Method.POST / "scheduler" / "run" -> handler { (_: Request) =>
        (for
          result <- WorkerScheduler.runAllChecks(WorkerScheduler.Config())
          response = SchedulerStatusResponse(
            activated = result.activated.map(_.value),
            markedOffline = result.markedOffline.map(_.value),
            reactivated = result.reactivated.map(_.value)
          )
        yield Response.json(response.toJson))
          .catchAll(handleError)
      }
    )

  private def handleError(error: WorkerError): UIO[Response] =
    val errorResponse = ErrorResponse(
      error = error.getClass.getSimpleName,
      message = error.message
    )
    val status = error match
      case _: WorkerError.WorkerNotFound       => Status.NotFound
      case _: WorkerError.WorkerAlreadyExists  => Status.Conflict
      case _: WorkerError.InvalidConfiguration => Status.BadRequest
      case _: WorkerError.InvalidWorkerState   => Status.Conflict
      case _: WorkerError.InvalidStateTransition => Status.BadRequest
      case _                                     => Status.InternalServerError

    ZIO.succeed(Response(status = status, body = Body.fromString(errorResponse.toJson)))
