package com.myco.ordering

/** Input DTOs — shaped for callers, not the internal domain. */

final case class CheckoutInput(
  customerId:      CustomerId,
  shippingAddress: AddressInput,
  items:           List[LineItemInput]
)

final case class LineItemInput(
  catalogueNumber: String,
  quantity:        Int
)

final case class AddressInput(
  country:    String,
  city:       String,
  postalCode: String,
  line1:      String,
  line2:      String
)
