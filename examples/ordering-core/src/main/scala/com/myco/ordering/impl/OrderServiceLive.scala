package com.myco.ordering
package impl

import zio.*

/** Live implementation of [[OrderService]].
  *
  * Data flow:
  *   public DTO in → parse to domain → business logic → persist domain entity → map to public view out
  *
  * All parse/map methods are private. If you restructure the domain model tomorrow,
  * you update these methods and nothing else.
  */
private[ordering] final case class OrderServiceLive(
  orderRepo: OrderRepository
) extends OrderService:

  override def checkout(input: CheckoutInput): IO[OrderError, OrderView] =
    for
      items   <- ZIO.fromEither(parseItems(input.items))
      address <- ZIO.fromEither(parseAddress(input.shippingAddress))
      orderId  = Order.Id(OrderId.generate.value)
      order    = Order(
                   orderId,
                   Order.Data(
                     customerId      = input.customerId,
                     shippingAddress = address,
                     items           = items,
                     status          = OrderStatus.Unpaid
                   )
                 )
      _       <- orderRepo.save(order)
    yield toView(order)

  override def getOrder(id: OrderId): IO[OrderError, OrderView] =
    orderRepo.findById(Order.Id(id.value))
      .someOrFail(OrderError.OrderNotFound(id)) // Option → typed error
      .map(toView)

  override def cancelOrder(id: OrderId): IO[OrderError, Unit] =
    for
      order <- orderRepo.findById(Order.Id(id.value))
                 .someOrFail(OrderError.OrderNotFound(id))
      _     <- order.data.status match
                 case OrderStatus.Cancelled =>
                   ZIO.fail(OrderError.OrderAlreadyCancelled(id))
                 case _ =>
                   val cancelled = order.copy(
                     data = order.data.copy(status = OrderStatus.Cancelled)
                   )
                   orderRepo.save(cancelled)
    yield ()

  // ── internal conversions (free to change) ─────────────────────

  private def parseItems(
    inputs: List[LineItemInput]
  ): Either[OrderError, List[LineItem]] =
    inputs match
      case Nil => Left(OrderError.EmptyItemList)
      case nonEmpty =>
        Right(nonEmpty.map { i =>
          LineItem(
            catalogueNumber = LineItem.CatalogueNumber(i.catalogueNumber),
            unitPrice       = BigDecimal(9.99), // simplified — would come from catalogue
            quantity        = i.quantity
          )
        })

  private def parseAddress(
    input: AddressInput
  ): Either[OrderError, Address] =
    if input.city.isBlank then Left(OrderError.InvalidAddress("city is required"))
    else Right(Address(
      country    = Address.Country(input.country),
      city       = input.city,
      postalCode = input.postalCode,
      line1      = input.line1,
      line2      = input.line2
    ))

  private def toView(order: Order): OrderView =
    OrderView(
      id         = OrderId(order.id.value),
      customerId = order.data.customerId,
      status     = order.data.status.toString,
      items      = order.data.items.map { item =>
        LineItemView(
          catalogueNumber = item.catalogueNumber.value,
          quantity        = item.quantity,
          unitPrice       = item.unitPrice,
          lineTotal       = item.lineTotal
        )
      },
      total    = order.data.items.map(_.lineTotal).sum,
      currency = "USD"  // simplified
    )

object OrderServiceLive:
  val layer: URLayer[OrderRepository, OrderService] =
    ZLayer.fromFunction(OrderServiceLive.apply)
