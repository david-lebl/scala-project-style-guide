package com.myco.ordering
package impl
package postgres

// Order, Order.Id, OrderStatus, LineItem, Address, CustomerId, etc.
// are all auto-imported from the parent packages via chained clause.
// No import statements needed.

/** Data Access Object — optimised for the database schema.
  *
  * This type is `private[postgres]`: it never leaks beyond this adapter.
  * The adapter owns the bidirectional mapping to/from domain entities.
  */
private[postgres] final case class OrderDAO(
  id:              String,
  customerId:      String,
  status:          String,
  itemsJson:       String,    // serialised items (JSON, CSV, etc.)
  shippingCountry: String,
  shippingCity:    String,
  shippingPostal:  String,
  shippingLine1:   String,
  shippingLine2:   String
):

  /** DAO → Domain. In a real project, parse `itemsJson` properly. */
  def toDomain: Order =
    Order(
      id = Order.Id(id),
      data = Order.Data(
        customerId = CustomerId(customerId),
        shippingAddress = Address(
          country    = Address.Country(shippingCountry),
          city       = shippingCity,
          postalCode = shippingPostal,
          line1      = shippingLine1,
          line2      = shippingLine2
        ),
        items = parseItems(itemsJson),
        status = OrderStatus.valueOf(status)
      )
    )

  private def parseItems(json: String): List[LineItem] =
    // Simplified — in production use zio-json or circe
    json.split("\\|").toList.collect:
      case s if s.nonEmpty =>
        val parts = s.split(",")
        LineItem(
          catalogueNumber = LineItem.CatalogueNumber(parts(0)),
          unitPrice       = BigDecimal(parts(1)),
          quantity        = parts(2).toInt
        )

private[postgres] object OrderDAO:

  /** Domain → DAO. */
  def fromDomain(order: Order): OrderDAO =
    val d = order.data
    OrderDAO(
      id              = order.id.value,
      customerId      = d.customerId.value,
      status          = d.status.toString,
      itemsJson       = serializeItems(d.items),
      shippingCountry = d.shippingAddress.country.value,
      shippingCity    = d.shippingAddress.city,
      shippingPostal  = d.shippingAddress.postalCode,
      shippingLine1   = d.shippingAddress.line1,
      shippingLine2   = d.shippingAddress.line2
    )

  private def serializeItems(items: List[LineItem]): String =
    items.map(i => s"${i.catalogueNumber.value},${i.unitPrice},${i.quantity}").mkString("|")
