package com.mycompany.worker.app

import com.mycompany.worker.*
import com.mycompany.worker.impl.WorkerLayers
import zio.*

/**
 * Worker management demo application.
 *
 * Demonstrates use case pattern with scheduled background jobs.
 */
object WorkerDemo extends ZIOAppDefault:

  val demo: ZIO[Any, WorkerError, Unit] =
    for
      _ <- ZIO.logInfo("=== Worker Management Demo ===\n")

      // 1. Register workers
      _ <- ZIO.logInfo("1. Registering workers...")
      worker1 <- WorkerUseCases.registerWorker(
        "worker-1",
        Worker.Config(region = "us-east-1", capacity = 10)
      )
      worker2 <- WorkerUseCases.registerWorker(
        "worker-2",
        Worker.Config(region = "eu-west-1", capacity = 5)
      )
      worker3 <- WorkerUseCases.registerWorker(
        "worker-3",
        Worker.Config(region = "ap-south-1", capacity = 8)
      )
      _ <- ZIO.logInfo(s"   Registered: ${worker1.id.value}, ${worker2.id.value}, ${worker3.id.value}")
      _ <- ZIO.logInfo(s"   Status: Pending (waiting for first heartbeat)\n")

      // 2. Send heartbeats for worker-1 and worker-2
      _ <- ZIO.logInfo("2. Sending heartbeats for worker-1 and worker-2...")
      _ <- WorkerUseCases.recordHeartbeat(worker1.id)
      _ <- WorkerUseCases.recordHeartbeat(worker2.id)
      _ <- ZIO.logInfo("   Heartbeats recorded\n")

      // 3. Wait and run scheduler to activate workers
      _ <- ZIO.logInfo("3. Running scheduler to activate workers with heartbeats...")
      _ <- ZIO.sleep(1.second)
      schedulerResult1 <- WorkerScheduler.runAllChecks(WorkerScheduler.Config())
      _ <- ZIO.logInfo(s"   Activated: ${schedulerResult1.activated.map(_.value).mkString(", ")}")
      _ <- ZIO.logInfo(s"   (worker-3 still Pending - no heartbeat yet)\n")

      // 4. Query active workers
      _ <- ZIO.logInfo("4. Querying active workers...")
      activeWorkers <- WorkerQueries.getActiveWorkers
      _ <- ZIO.logInfo(s"   Active workers: ${activeWorkers.map(_.id.value).mkString(", ")}\n")

      // 5. Query worker with metadata
      _ <- ZIO.logInfo("5. Getting worker-1 with metadata...")
      workersWithMeta <- WorkerQueries.getWorkersWithMetadata(Some(Worker.Status.Active))
      _ <- ZIO.foreach(workersWithMeta) { wm =>
        ZIO.logInfo(
          s"   ${wm.worker.id.value}: ${wm.worker.status}, region=${wm.worker.config.region}, " +
            s"capacity=${wm.worker.config.capacity}, last heartbeat=${wm.lastHeartbeat.getOrElse("never")}"
        )
      }
      _ <- ZIO.logInfo("")

      // 6. Simulate heartbeat timeout for worker-1
      _ <- ZIO.logInfo("6. Simulating heartbeat timeout (waiting 2 minutes)...")
      _ <- ZIO.logInfo("   (Using short timeout for demo: 3 seconds)")
      _ <- ZIO.sleep(3.seconds)

      // Run scheduler with short timeout to mark stale workers
      schedulerResult2 <- WorkerScheduler.runAllChecks(
        WorkerScheduler.Config(heartbeatTimeout = java.time.Duration.ofSeconds(2))
      )
      _ <- ZIO.logInfo(s"   Marked offline: ${schedulerResult2.markedOffline.map(_.value).mkString(", ")}\n")

      // 7. Send heartbeat for worker-1 to reactivate
      _ <- ZIO.logInfo("7. Sending heartbeat for worker-1 to reactivate...")
      _ <- WorkerUseCases.recordHeartbeat(worker1.id)
      _ <- ZIO.sleep(1.second)
      schedulerResult3 <- WorkerScheduler.runAllChecks(
        WorkerScheduler.Config(heartbeatTimeout = java.time.Duration.ofSeconds(2))
      )
      _ <- ZIO.logInfo(s"   Reactivated: ${schedulerResult3.reactivated.map(_.value).mkString(", ")}\n")

      // 8. Get all workers with status
      _ <- ZIO.logInfo("8. Final worker states:")
      allWorkers <- WorkerQueries.getAllWorkers
      _ <- ZIO.foreach(allWorkers) { worker =>
        ZIO.logInfo(s"   ${worker.id.value}: ${worker.status}")
      }
      _ <- ZIO.logInfo("")

      // 9. Unregister worker-3
      _ <- ZIO.logInfo("9. Unregistering worker-3...")
      _ <- WorkerUseCases.unregisterWorker(worker3.id)
      remainingWorkers <- WorkerQueries.getAllWorkers
      _ <- ZIO.logInfo(s"   Remaining workers: ${remainingWorkers.map(_.id.value).mkString(", ")}\n")

      _ <- ZIO.logInfo("=== Demo Complete ===")
    yield ()

  override def run: ZIO[Any, Any, Any] =
    demo
      .provide(WorkerLayers.inMemory)
      .catchAll { error =>
        ZIO.logError(s"Demo failed: ${error.getMessage}").as(ExitCode.failure)
      }
