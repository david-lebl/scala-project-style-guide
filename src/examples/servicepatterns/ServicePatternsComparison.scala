package examples.servicepatterns

import examples.errormodeling.domain.{Worker, WorkerError}
import examples.errormodeling.repository.{
  InMemoryWorkerRepository,
  InMemoryHeartbeatRepository
}
import examples.servicepatterns.servicepattern.WorkerService
import examples.servicepatterns.usecasepattern.{
  WorkerUseCases,
  WorkerUseCasesWithDTO
}
import zio.*
import java.time.Duration

/** Service Patterns Comparison Examples.
  *
  * Demonstrates the differences between:
  * 1. Service Pattern - methods grouped in a trait
  * 2. Use Case Pattern - standalone functions
  * 3. Use Case Pattern with DTOs - explicit Input/Output case classes
  */
object ServicePatternsComparison extends ZIOAppDefault:

  /** Example 1: Service Pattern Usage */
  def example1ServicePattern: ZIO[WorkerService, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 1: Service Pattern ===")
        .orDie

      // Register workers using the service
      worker1 <- ZIO.serviceWithZIO[WorkerService](
        _.register("worker-1", Map("region" -> "us-east", "capacity" -> "100"))
      )
      _ <- Console
        .printLine(
          s"✓ Service Pattern: Registered ${worker1.id.value}, status: ${worker1.status}"
        )
        .orDie

      // Send heartbeat
      _ <- ZIO.serviceWithZIO[WorkerService](_.heartbeat(worker1.id))
      _ <- Console.printLine("✓ Service Pattern: Heartbeat recorded").orDie

      // Get active workers
      activeWorkers <- ZIO.serviceWithZIO[WorkerService](_.getActiveWorkers)
      _ <- Console
        .printLine(s"✓ Service Pattern: Active workers: ${activeWorkers.length}")
        .orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 2: Use Case Pattern Usage (without DTOs) */
  def example2UseCasePattern
      : ZIO[WorkerUseCases.WorkerDependencies, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 2: Use Case Pattern (no DTOs) ===")
        .orDie

      // Register workers using use cases
      worker2 <- WorkerUseCases.registerWorker(
        "worker-2",
        Map("region" -> "eu-west", "capacity" -> "200")
      )
      _ <- Console
        .printLine(
          s"✓ Use Case Pattern: Registered ${worker2.id.value}, status: ${worker2.status}"
        )
        .orDie

      // Send heartbeat
      _ <- WorkerUseCases.recordHeartbeat(worker2.id)
      _ <- Console.printLine("✓ Use Case Pattern: Heartbeat recorded").orDie

      // Get active workers
      activeWorkers <- WorkerUseCases.getActiveWorkers
      _ <- Console
        .printLine(s"✓ Use Case Pattern: Active workers: ${activeWorkers.length}")
        .orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 3: Use Case Pattern with DTOs */
  def example3UseCasePatternWithDTO
      : ZIO[WorkerUseCasesWithDTO.WorkerDependencies, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 3: Use Case Pattern (with DTOs) ===")
        .orDie

      // Register worker with Input DTO
      result <- WorkerUseCasesWithDTO.registerWorker(
        WorkerUseCasesWithDTO.RegisterWorkerInput(
          "worker-3",
          Map("region" -> "ap-south", "capacity" -> "150")
        )
      )
      _ <- Console
        .printLine(
          s"✓ Use Case with DTO: Registered ${result.worker.id.value}, status: ${result.worker.status}"
        )
        .orDie

      // Send heartbeat with Input DTO
      heartbeatResult <- WorkerUseCasesWithDTO.recordHeartbeat(
        WorkerUseCasesWithDTO.RecordHeartbeatInput(result.worker.id)
      )
      _ <- Console
        .printLine(
          s"✓ Use Case with DTO: Heartbeat recorded at ${heartbeatResult.recordedAt}, status: ${heartbeatResult.workerStatus}"
        )
        .orDie

      // List workers with Input/Output DTOs
      listResult <- WorkerUseCasesWithDTO.listWorkersByStatus(
        WorkerUseCasesWithDTO.ListWorkersByStatusInput(Worker.Status.Active)
      )
      _ <- Console
        .printLine(
          s"✓ Use Case with DTO: Active workers: ${listResult.count}"
        )
        .orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 4: Comparing Dependency Requirements */
  def example4DependencyComparison: ZIO[Any, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 4: Dependency Comparison ===")
        .orDie

      _ <- Console
        .printLine("Service Pattern: Requires ALL dependencies at service level")
        .orDie
      _ <- Console
        .printLine("  - WorkerRepository & HeartbeatRepository")
        .orDie
      _ <- Console.printLine("  - Even if you only call one method").orDie

      _ <- Console.printLine("").orDie

      _ <- Console
        .printLine("Use Case Pattern: Requires only needed dependencies")
        .orDie
      _ <- Console.printLine("  - registerWorker: only WorkerRepository").orDie
      _ <- Console
        .printLine(
          "  - recordHeartbeat: WorkerRepository & HeartbeatRepository"
        )
        .orDie
      _ <- Console.printLine("  - getActiveWorkers: only WorkerRepository").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 5: Stale Worker Cleanup - Complex Operation */
  def example5ComplexOperation
      : ZIO[WorkerService & WorkerUseCases.WorkerDependencies, WorkerError, Unit] =
    for
      _ <- Console
        .printLine("=== Example 5: Complex Operation (Stale Workers) ===")
        .orDie

      // Using Service Pattern
      staleIds1 <- ZIO.serviceWithZIO[WorkerService](
        _.cleanupStaleWorkers(Duration.ofMinutes(5))
      )
      _ <- Console
        .printLine(
          s"✓ Service Pattern: Found ${staleIds1.length} stale workers"
        )
        .orDie

      // Using Use Case Pattern
      staleIds2 <- WorkerUseCases.cleanupStaleWorkers(Duration.ofMinutes(5))
      _ <- Console
        .printLine(
          s"✓ Use Case Pattern: Found ${staleIds2.length} stale workers"
        )
        .orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Example 6: Error Handling Comparison */
  def example6ErrorHandling
      : ZIO[WorkerService & WorkerUseCases.WorkerDependencies, Nothing, Unit] =
    for
      _ <- Console
        .printLine("=== Example 6: Error Handling ===")
        .orDie

      // Service Pattern - duplicate worker error
      result1 <- ZIO
        .serviceWithZIO[WorkerService](
          _.register(
            "duplicate",
            Map("region" -> "us-east", "capacity" -> "100")
          )
        )
        .flatMap(_ =>
          ZIO.serviceWithZIO[WorkerService](
            _.register(
              "duplicate",
              Map("region" -> "us-west", "capacity" -> "50")
            )
          )
        )
        .either

      _ <- result1 match
        case Left(error: WorkerError.WorkerAlreadyExists) =>
          Console
            .printLine(
              s"✓ Service Pattern error caught: ${error.message}"
            )
            .orDie
        case _ =>
          Console.printLine("✗ Should have failed!").orDie

      // Use Case Pattern - same error
      result2 <- WorkerUseCases
        .registerWorker("duplicate2", Map("region" -> "us-east", "capacity" -> "100"))
        .flatMap(_ =>
          WorkerUseCases.registerWorker(
            "duplicate2",
            Map("region" -> "us-west", "capacity" -> "50")
          )
        )
        .either

      _ <- result2 match
        case Left(error: WorkerError.WorkerAlreadyExists) =>
          Console
            .printLine(
              s"✓ Use Case Pattern error caught: ${error.message}"
            )
            .orDie
        case _ =>
          Console.printLine("✗ Should have failed!").orDie

      _ <- Console.printLine("").orDie
    yield ()

  /** Main program - runs all comparison examples */
  override def run: ZIO[Any, Any, Unit] =
    val program = for
      _ <- Console
        .printLine("╔══════════════════════════════════════════════════╗")
        .orDie
      _ <- Console
        .printLine("║  Service Patterns Comparison                     ║")
        .orDie
      _ <- Console
        .printLine("║  From: SERVICE_PATTERNS_COMPARISON.md            ║")
        .orDie
      _ <- Console
        .printLine("╚══════════════════════════════════════════════════╝\n")
        .orDie

      _ <- example1ServicePattern.provideSomeLayer(WorkerService.layer)
      _ <- example2UseCasePattern
      _ <- example3UseCasePatternWithDTO
      _ <- example4DependencyComparison
      _ <- example5ComplexOperation.provideSomeLayer(WorkerService.layer)
      _ <- example6ErrorHandling.provideSomeLayer(WorkerService.layer)

      _ <- Console.printLine("✓ All comparison examples completed!").orDie
    yield ()

    program.provide(
      InMemoryWorkerRepository.layer,
      InMemoryHeartbeatRepository.layer
    )