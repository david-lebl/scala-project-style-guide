import java.time.Instant

/**
 * Heartbeat domain entity.
 *
 * Represents a single heartbeat event from a worker. Immutable aggregate root
 * with full metadata about the heartbeat, including timing information and
 * worker metrics at the time of the heartbeat.
 */
case class Heartbeat(
    id: Heartbeat.Id,
    workerId: Worker.Id,
    createdAt: Instant, // When worker sent the heartbeat
    receivedAt: Instant, // When system received it
    metadata: Heartbeat.Metadata
)

@accessible
private[worker] trait AllocationStore {
  // Per-task allocation management
  def allocate(allocation: Allocation): IO[AllocationError, Unit]
  def deallocate(workerId: Worker.Id, taskId: TaskEntry.Id): IO[AllocationError, Unit]
  def updateAllocation(allocation: Allocation): IO[AllocationError, Unit]

  // Query individual task allocations
  def getTaskAllocations(workerId: Worker.Id): IO[Nothing, List[Allocation]]

  // Query aggregated resource usage
  def getTotalAllocated(workerId: Worker.Id): IO[Nothing, AllocatedResources]
  def getAllWorkerAllocations(): IO[Nothing, List[AllocatedResources]]
}

object AllocationStore {
  val inMemoryLayer = internal.InMemoryAllocationStore.layer
}

object Heartbeat:

  /** Heartbeat identifier (opaque type for type safety). */
  opaque type Id = String

  object Id:
    def apply(value: String): Id = value
    def generate(): Id = java.util.UUID.randomUUID().toString
    extension (id: Id) def value: String = id

  /**
   * Heartbeat metadata.
   *
   * Contains additional context about the heartbeat: - Source IP/hostname -
   * Worker metrics at heartbeat time - Custom tags
   */
  case class Metadata(
      sourceIp: Option[String] = None,
      hostname: Option[String] = None,
      workerLoad: Option[Double] = None, // 0.0 to 1.0
      activeTaskCount: Option[Int] = None,
      memoryUsageBytes: Option[Long] = None,
      tags: Map[String, String] = Map.empty
  )

  object Metadata:
    val empty: Metadata = Metadata()

  /**
   * Create a new heartbeat.
   *
   * Factory method that sets both createdAt and receivedAt to now. Use
   * createWithTimes() if you need different timestamps.
   */
  def create(
      workerId: Worker.Id,
      metadata: Metadata = Metadata.empty
  )(using clock: java.time.Clock): Heartbeat =
    val now = Instant.now(clock)
    Heartbeat(
      id = Id.generate(),
      workerId = workerId,
      createdAt = now,
      receivedAt = now,
      metadata = metadata
    )

  /**
   * Create heartbeat with specific timestamps.
   *
   * Useful when worker sends its local timestamp and we want to track clock
   * skew or network latency.
   */
  def createWithTimes(
      workerId: Worker.Id,
      createdAt: Instant,
      receivedAt: Instant,
      metadata: Metadata = Metadata.empty
  ): Heartbeat =
    Heartbeat(
      id = Id.generate(),
      workerId = workerId,
      createdAt = createdAt,
      receivedAt = receivedAt,
      metadata = metadata
    )

  /**
   * Calculate network latency (time between creation and receipt).
   */
  extension (heartbeat: Heartbeat)
    def latency: java.time.Duration =
      java.time.Duration.between(heartbeat.createdAt, heartbeat.receivedAt)

    def isStale(
        timeout: java.time.Duration
    )(using clock: java.time.Clock): Boolean =
      val now = Instant.now(clock)
      val age = java.time.Duration.between(heartbeat.receivedAt, now)
      age.compareTo(timeout) > 0
