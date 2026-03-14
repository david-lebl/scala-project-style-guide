package com.myco.ordering

import java.util.UUID

/** Stable ID types used in the public contract. */

opaque type OrderId = String
object OrderId:
  def apply(value: String): OrderId      = value
  def generate: OrderId                  = UUID.randomUUID().toString
  extension (id: OrderId) def value: String = id

opaque type CustomerId = String
object CustomerId:
  def apply(value: String): CustomerId       = value
  extension (id: CustomerId) def value: String = id
