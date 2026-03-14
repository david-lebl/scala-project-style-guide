package com.myco.app

import com.myco.ordering.*
import com.myco.ordering.impl.{OrderServiceLive, OrderRepositoryInMemory}
import com.myco.ordering.impl.postgres.PostgresOrderRepository
import zio.*

/** Application entry point — the only module that sees all layers.
  *
  * In a real project this would also wire HTTP routes, config, and other contexts.
  */
object Main extends ZIOAppDefault:

  override val run =
    program.provide(
      OrderServiceLive.layer,
      // Swap to PostgresOrderRepository.layer for the "real" adapter:
      // PostgresOrderRepository.layer,
      OrderRepositoryInMemory.layer
    )

  private val program =
    for
      _    <- ZIO.logInfo("=== Scala Style Guide — Example App ===")

      // Create an order
      input = CheckoutInput(
                customerId      = CustomerId("cust-1"),
                shippingAddress = AddressInput("US", "New York", "10001", "123 Main St", ""),
                items           = List(
                  LineItemInput("SKU-001", 2),
                  LineItemInput("SKU-002", 1)
                )
              )
      view  <- OrderService.checkout(input)
      _     <- ZIO.logInfo(s"Created order: ${view.id.value}, total: ${view.total} ${view.currency}")

      // Fetch it back
      fetched <- OrderService.getOrder(view.id)
      _       <- ZIO.logInfo(s"Fetched order status: ${fetched.status}")

      // Cancel it
      _         <- OrderService.cancelOrder(view.id)
      cancelled <- OrderService.getOrder(view.id)
      _         <- ZIO.logInfo(s"After cancel: ${cancelled.status}")

      // Demonstrate error handling
      result <- OrderService.checkout(input.copy(items = Nil)).exit
      _      <- result match
                  case Exit.Failure(cause) =>
                    cause.failureOption match
                      case Some(err) => ZIO.logWarning(s"Expected error: ${err.message}")
                      case None      => ZIO.logError("Unexpected defect")
                  case Exit.Success(_) =>
                    ZIO.logError("Should have failed!")

      _ <- ZIO.logInfo("=== Done ===")
    yield ()
