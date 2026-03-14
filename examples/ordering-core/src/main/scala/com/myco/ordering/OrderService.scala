package com.myco.ordering

import zio.*

/** Public service trait — the stable contract of the Ordering bounded context.
  *
  * Uses only types from this public package. Knows nothing about HTTP, JSON,
  * databases, or the internal domain model.
  */
trait OrderService:
  def checkout(input: CheckoutInput): IO[OrderError, OrderView]
  def getOrder(id: OrderId): IO[OrderError, OrderView]
  def cancelOrder(id: OrderId): IO[OrderError, Unit]

object OrderService:
  def checkout(input: CheckoutInput): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.checkout(input))

  def getOrder(id: OrderId): ZIO[OrderService, OrderError, OrderView] =
    ZIO.serviceWithZIO(_.getOrder(id))

  def cancelOrder(id: OrderId): ZIO[OrderService, OrderError, Unit] =
    ZIO.serviceWithZIO(_.cancelOrder(id))
