package com.mycompany.worker

import java.time.Instant

/**
 * Worker domain model.
 *
 * Represents a worker in the system with lifecycle management,
 * heartbeat tracking, and configuration metadata.
 */
case class Worker(
    id: Worker.Id,
    config: Worker.Config,
    status: Worker.Status,
    registeredAt: Instant,
    lastHeartbeatAt: Option[Instant] = None
)

object Worker:

  /** Worker identifier (opaque type for type safety). */
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  /**
   * Worker lifecycle status.
   *
   * State transitions:
   * - Pending -> Active (first heartbeat received)
   * - Active -> Offline (heartbeat timeout)
   * - Active -> Failed (explicit failure)
   * - Offline -> Active (heartbeat received after timeout)
   * - Failed -> (terminal state, requires unregister)
   */
  enum Status:
    case Pending // Registered but not yet heartbeat
    case Active // Actively sending heartbeats
    case Offline // Heartbeat timeout, not responding
    case Failed // Explicit failure state

  /**
   * Worker configuration metadata.
   *
   * Contains all worker-specific configuration like region,
   * capacity, capabilities, and custom metadata.
   */
  case class Config(
      region: String,
      capacity: Int,
      capabilities: Set[String] = Set.empty,
      metadata: Map[String, String] = Map.empty
  )

  object Config:
    /**
     * Parse configuration from raw map.
     * Returns None if required fields are missing or invalid.
     */
    def fromMap(map: Map[String, String]): Option[Config] =
      for
        region <- map.get("region")
        capacityStr <- map.get("capacity")
        capacity <- capacityStr.toIntOption if capacity > 0
      yield
        val capabilities = map
          .get("capabilities")
          .map(_.split(",").map(_.trim).toSet)
          .getOrElse(Set.empty)
        val metadata = map.filterKeys(
          !Set("region", "capacity", "capabilities").contains(_)
        )
        Config(region, capacity, capabilities, metadata)

    /** Convert configuration to map for serialization. */
    def toMap(config: Config): Map[String, String] =
      Map(
        "region" -> config.region,
        "capacity" -> config.capacity.toString,
        "capabilities" -> config.capabilities.mkString(",")
      ) ++ config.metadata

  /** Create a new worker in Pending status. */
  def create(id: String, config: Config): Worker =
    Worker(Id(id), config, Status.Pending, Instant.now())