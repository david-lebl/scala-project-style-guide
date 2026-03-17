package com.myco.ordering
package impl

import zio.*

/** Repository out-port — defined in terms of rich domain entities.
  *
  * How an [[Order]] becomes a database row (or document, or event) is the
  * adapter's problem, not the domain's. The port speaks the language of the domain.
  *
  * Returns [[UIO]] because infrastructure failures are defects, not domain
  * concerns — adapters call `.orDie` at their own boundary.
  */
private[ordering] trait OrderRepository:
  def save(order: Order): UIO[Unit]
  def findById(id: Order.Id): UIO[Option[Order]]
