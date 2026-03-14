package com.myco.ordering
package impl

import zio.*

/** In-memory implementation of [[OrderRepository]] for unit tests.
  *
  * Because the repository port works with domain entities directly,
  * this implementation is trivial — just a Ref over a Map.
  */
private[ordering] final case class OrderRepositoryInMemory(
  ref: Ref[Map[Order.Id, Order]]
) extends OrderRepository:

  override def save(order: Order): UIO[Unit] =
    ref.update(_.updated(order.id, order))

  override def findById(id: Order.Id): UIO[Option[Order]] =
    ref.get.map(_.get(id))

object OrderRepositoryInMemory:
  val layer: ULayer[OrderRepository] =
    ZLayer:
      Ref.make(Map.empty[Order.Id, Order]).map(OrderRepositoryInMemory(_))
