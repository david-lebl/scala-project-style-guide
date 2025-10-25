package examples.errormodeling

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.service.WorkerService
import examples.errormodeling.repository.{
  InMemoryWorkerRepository,
  InMemoryHeartbeatRepository
}
import zio.*

/** Error Modeling Examples.
  *
  * Demonstrates the patterns from ERROR_MODELING_GUIDE.md:
  *   1. Domain-wide errors (all services use WorkerError)
  *   2. Error messages in each error case
  *   3. NoStackTrace extension for Throwable conversion
  *   4. Error composition with flatMap
  *   5. Error handling in applications
  */
object ErrorModelingExamples extends ZIOAppDefault:

  /** Example 1: Basic registration and error handling. */
  def example1: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console.printLine("=== Example 1: Basic Registration ===").orDie

      // Successful registration
      worker <- ZIO.serviceWithZIO[WorkerService](
        _.register("worker-1", Map("region" -> "us-east", "capacity" -> "100"))
      )
      _ <- Console
        .printLine(
          s"✓ Registered: ${worker.id.value}, status: ${worker.status}"
        )
        .orDie

      // Attempt duplicate registration (will fail)
      result <- ZIO
        .serviceWithZIO[WorkerService](
          _.register("worker-1", Map("region" -> "us-west", "capacity" -> "50"))
        )
        .either

      _ <- result match
        case Left(error) =>
          Console.printLine(s"✗ Expected error: ${error.message}").orDie
        case Right(_) => Console.printLine("✗ Should have failed!").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 2: Error composition - all operations use same error type. */
  def example2: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console.printLine("=== Example 2: Error Composition ===").orDie

      // Register and activate in one flow (possible because same error type)
      result <- registerAndActivate(
        "worker-2",
        Map("region" -> "eu-west", "capacity" -> "200")
      )
      _ <- Console
        .printLine(
          s"✓ Registered and activated: ${result.id.value}, status: ${result.status}"
        )
        .orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 3: Configuration validation errors. */
  def example3: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 3: Configuration Validation ===")
        .orDie

      // Missing required field
      result <- ZIO
        .serviceWithZIO[WorkerService](
          _.register(
            "worker-3",
            Map("region" -> "us-east")
          ) // missing 'capacity'
        )
        .either

      _ <- result match
        case Left(error: WorkerError.InvalidConfiguration) =>
          Console
            .printLine(s"✓ Validation error caught: ${error.message}")
            .orDie
        case Left(error) =>
          Console.printLine(s"✗ Wrong error type: ${error.message}").orDie
        case Right(_) =>
          Console.printLine("✗ Should have failed validation!").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 4: State management and transitions. */
  def example4: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console.printLine("=== Example 4: State Management ===").orDie

      // Register worker in Pending state
      worker <- ZIO.serviceWithZIO[WorkerService](
        _.register("worker-4", Map("region" -> "ap-south", "capacity" -> "150"))
      )
      _ <- Console
        .printLine(s"✓ Worker registered, status: ${worker.status}")
        .orDie

      // Send heartbeat (auto-activates pending workers)
      _ <- ZIO.serviceWithZIO[WorkerService](_.heartbeat(worker.id))
      activated <- ZIO.serviceWithZIO[WorkerService](_.getWorker(worker.id))
      _ <- Console
        .printLine(s"✓ After heartbeat, status: ${activated.status}")
        .orDie

      // Try to activate again (should fail - already active)
      result <- ZIO
        .serviceWithZIO[WorkerService](
          _.activate(worker.id)
        )
        .either

      _ <- result match
        case Left(error: WorkerError.InvalidWorkerState) =>
          Console.printLine(s"✓ State error caught: ${error.message}").orDie
        case _ =>
          Console.printLine("✗ Should have failed state check!").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 5: Error-as-Throwable (for logging). */
  def example5: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console.printLine("=== Example 5: Error as Throwable ===").orDie

      // Demonstrate that WorkerError is a Throwable
      result <- ZIO
        .serviceWithZIO[WorkerService](
          _.getWorker(Worker.Id("nonexistent"))
        )
        .either

      _ <- result match
        case Left(error) =>
          // Error extends NoStackTrace, so it IS a Throwable
          for
            _ <- Console
              .printLine(
                s"✓ Error is Throwable: ${error.isInstanceOf[Throwable]}"
              )
              .orDie
            _ <- Console
              .printLine(s"✓ Error message: ${error.getMessage}")
              .orDie
            _ <- ZIO.logError(s"Logged error: ${error.message}")
          yield ()
        case Right(_) =>
          Console.printLine("✗ Should have failed!").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 6: Comprehensive error handling pattern. */
  def example6: ZIO[WorkerService, Nothing, Unit] =
    val program: ZIO[WorkerService, WorkerError, Unit] =
      for
        _ <- Console
          .printLine("=== Example 6: Comprehensive Error Handling ===")
          .orDie
        worker <- ZIO.serviceWithZIO[WorkerService](
          _.register(
            "worker-6",
            Map("region" -> "us-east", "capacity" -> "100")
          )
        )
        _ <- ZIO.serviceWithZIO[WorkerService](_.heartbeat(worker.id))
        _ <- Console
          .printLine(s"✓ Worker ${worker.id.value} registered and active")
          .orDie
      yield ()

    // Handle all errors with pattern matching
    program.catchAll { error =>
      error match
        case WorkerError.WorkerAlreadyExists(id) =>
          Console
            .printLine(s"⚠ Conflict: Worker ${id.value} already exists")
            .orDie

        case WorkerError.WorkerNotFound(id) =>
          Console
            .printLine(s"⚠ Not Found: Worker ${id.value} does not exist")
            .orDie

        case WorkerError.InvalidConfiguration(field, reason) =>
          Console.printLine(s"⚠ Bad Request: Invalid $field - $reason").orDie

        case WorkerError.InvalidWorkerState(id, current, expected) =>
          Console
            .printLine(
              s"⚠ Conflict: Worker ${id.value} is $current, expected $expected"
            )
            .orDie

        case _: WorkerError.RepositoryError | _: WorkerError.DatabaseError =>
          Console.printLine(s"⚠ Internal Error: ${error.message}").orDie *>
            ZIO.logErrorCause("Repository error", Cause.fail(error))

        case _ =>
          Console.printLine(s"⚠ Error: ${error.message}").orDie *>
            ZIO.logErrorCause("Unexpected error", Cause.fail(error))
    } *> Console.printLine("").orDie

  /** Helper function demonstrating error composition.
    *
    * All operations return IO[WorkerError, *], so they compose naturally.
    */
  def registerAndActivate(
      id: String,
      config: Map[String, String]
  ): ZIO[WorkerService, WorkerError, Worker] =
    for
      worker <- ZIO.serviceWithZIO[WorkerService](_.register(id, config))
      _ <- ZIO.serviceWithZIO[WorkerService](_.heartbeat(worker.id))
      activated <- ZIO.serviceWithZIO[WorkerService](_.getWorker(worker.id))
    yield activated

  /** Main program - runs all examples. */
  override def run: ZIO[Any, Any, Unit] =
    val program = for
      _ <- Console
        .printLine("╔════════════════════════════════════════════╗")
        .orDie
      _ <- Console
        .printLine("║  Error Modeling Examples                  ║")
        .orDie
      _ <- Console
        .printLine("║  From: ERROR_MODELING_GUIDE.md             ║")
        .orDie
      _ <- Console
        .printLine("╚════════════════════════════════════════════╝\n")
        .orDie

      _ <- example1
      _ <- example2
      _ <- example3
      _ <- example4
      _ <- example5
      _ <- example6

      _ <- Console.printLine("✓ All examples completed successfully!").orDie
    yield ()

    program.provide(
      InMemoryWorkerRepository.layer,
      InMemoryHeartbeatRepository.layer,
      WorkerService.layer
    )
