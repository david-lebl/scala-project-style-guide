package com.myco.ordering
package impl

// All types here are auto-imported in impl sub-packages via chained package clauses.

private[ordering] enum OrderStatus:
  case Unpaid, Paid, InProgress, Shipped, Cancelled

private[ordering] final case class LineItem(
  catalogueNumber: LineItem.CatalogueNumber,
  unitPrice:       BigDecimal,
  quantity:        Int
):
  def lineTotal: BigDecimal = unitPrice * quantity

private[ordering] object LineItem:
  opaque type CatalogueNumber = String
  object CatalogueNumber:
    def apply(value: String): CatalogueNumber          = value
    extension (cn: CatalogueNumber) def value: String   = cn

private[ordering] final case class Address(
  country:    Address.Country,
  city:       String,
  postalCode: String,
  line1:      String,
  line2:      String
)

private[ordering] object Address:
  opaque type Country = String
  object Country:
    def apply(value: String): Country          = value
    extension (c: Country) def value: String   = c
