package com.myco.ordering
package impl

/** Rich domain entity — invisible outside com.myco.ordering.*.
  *
  * Separated into id + data so you can compare identities, compare data,
  * or pattern match on either independently.
  */
private[ordering] final case class Order(id: Order.Id, data: Order.Data)

private[ordering] object Order:
  opaque type Id = String
  object Id:
    def apply(value: String): Id          = value
    extension (id: Id) def value: String  = id

  final case class Data(
    customerId:      CustomerId,
    shippingAddress: Address,
    items:           List[LineItem],   // guaranteed non-empty at construction
    status:          OrderStatus
  )
