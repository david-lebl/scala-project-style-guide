package com.mycompany.worker.http.dto

import com.mycompany.worker.Worker
import zio.json.*
import java.time.Instant

/**
 * DTOs for Worker REST API.
 *
 * Separate from domain models to decouple API from internal representation.
 */
object WorkerDTOs:

  // ========== Request DTOs ==========

  case class RegisterWorkerRequest(
      id: String,
      region: String,
      capacity: Int,
      capabilities: Option[Set[String]] = None,
      metadata: Option[Map[String, String]] = None
  ) derives JsonCodec:
    def toConfig: Worker.Config =
      Worker.Config(
        region = region,
        capacity = capacity,
        capabilities = capabilities.getOrElse(Set.empty),
        metadata = metadata.getOrElse(Map.empty)
      )

  case class HeartbeatRequest(
      workerId: String
  ) derives JsonCodec

  case class UpdateStatusRequest(
      status: String,
      expectedStatus: Option[String] = None
  ) derives JsonCodec

  // ========== Response DTOs ==========

  case class WorkerResponse(
      id: String,
      region: String,
      capacity: Int,
      capabilities: Set[String],
      metadata: Map[String, String],
      status: String,
      registeredAt: String,
      lastHeartbeatAt: Option[String]
  ) derives JsonCodec

  object WorkerResponse:
    def fromWorker(worker: Worker): WorkerResponse =
      WorkerResponse(
        id = worker.id.value,
        region = worker.config.region,
        capacity = worker.config.capacity,
        capabilities = worker.config.capabilities,
        metadata = worker.config.metadata,
        status = worker.status.toString,
        registeredAt = worker.registeredAt.toString,
        lastHeartbeatAt = worker.lastHeartbeatAt.map(_.toString)
      )

  case class WorkerWithMetadataResponse(
      id: String,
      region: String,
      capacity: Int,
      capabilities: Set[String],
      metadata: Map[String, String],
      status: String,
      registeredAt: String,
      lastHeartbeatAt: Option[String]
  ) derives JsonCodec

  case class ErrorResponse(
      error: String,
      message: String
  ) derives JsonCodec

  case class HeartbeatResponse(
      workerId: String,
      recorded: Boolean,
      timestamp: String
  ) derives JsonCodec

  case class StatusUpdateResponse(
      workerId: String,
      oldStatus: String,
      newStatus: String
  ) derives JsonCodec

  case class SchedulerStatusResponse(
      activated: List[String],
      markedOffline: List[String],
      reactivated: List[String]
  ) derives JsonCodec
