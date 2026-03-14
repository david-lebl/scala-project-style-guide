package com.myco.ordering

/** Response DTOs — read-model, not the domain entity. */

final case class OrderView(
  id:         OrderId,
  customerId: CustomerId,
  status:     String,
  items:      List[LineItemView],
  total:      BigDecimal,
  currency:   String
)

final case class LineItemView(
  catalogueNumber: String,
  quantity:        Int,
  unitPrice:       BigDecimal,
  lineTotal:       BigDecimal
)
