package com.myco.ordering
package impl
package postgres

// ↑ Chained package clause — brings into scope automatically:
//   • com.myco.ordering       → OrderService, OrderId, CustomerId, OrderError, …
//   • com.myco.ordering.impl  → Order, OrderRepository, OrderStatus, LineItem, Address, …
//   • com.myco.ordering.impl.postgres → OrderDAO (defined below in this package)
//
// Zero imports needed for any of the above.

import zio.*

/** Postgres implementation of [[OrderRepository]].
  *
  * In a real project this would use quill or doobie. Here we use a simple Ref
  * to demonstrate the DAO mapping pattern without requiring a database dependency.
  */
final case class PostgresOrderRepository(
  ref: Ref[Map[String, OrderDAO]]       // simulates a DB table
) extends OrderRepository:

  override def save(order: Order): Task[Unit] =
    val dao = OrderDAO.fromDomain(order)       // domain → DAO
    ref.update(_.updated(dao.id, dao))

  override def findById(id: Order.Id): Task[Option[Order]] =
    ref.get.map:
      _.get(id.value).map(_.toDomain)          // DAO → domain

object PostgresOrderRepository:
  val layer: ULayer[OrderRepository] =
    ZLayer:
      Ref.make(Map.empty[String, OrderDAO]).map(PostgresOrderRepository(_))
