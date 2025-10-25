package examples.errormodeling.domain

import java.time.Instant

/** Worker domain model.
  *
  * Represents a worker in the system with lifecycle states.
  */
case class Worker(
    id: Worker.Id,
    config: Map[String, String],
    status: Worker.Status,
    registeredAt: Instant
)

object Worker:
  /** Worker identifier (opaque type for type safety). */
  opaque type Id = String
  object Id:
    def apply(value: String): Id = value
    extension (id: Id) def value: String = id

  /** Worker lifecycle status. */
  enum Status:
    case Pending, Active, Offline, Failed

  /** Create a new worker in Pending status. */
  def create(id: String, config: Map[String, String]): Worker =
    Worker(
      Id(id),
      config,
      Status.Pending,
      Instant.now()
    )
