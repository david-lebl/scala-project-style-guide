package com.myco.ordering
package impl

import zio.*
import zio.test.*
import zio.test.Assertion.*

object OrderServiceLiveSpec extends ZIOSpecDefault:

  private val testCustomer = CustomerId("cust-1")
  private val validAddress = AddressInput("US", "New York", "10001", "123 Main St", "Apt 4")
  private val validItem    = LineItemInput("SKU-001", 2)

  def spec = suite("OrderServiceLive")(
    checkoutSuite,
    getOrderSuite,
    cancelOrderSuite,
    errorModelSuite
  ).provide(
    OrderServiceLive.layer,
    OrderRepositoryInMemory.layer
  )

  private val checkoutSuite = suite("checkout")(
    test("succeeds with valid input"):
      val input = CheckoutInput(testCustomer, validAddress, List(validItem))
      for
        view <- OrderService.checkout(input)
      yield assertTrue(
        view.customerId == testCustomer,
        view.status == "Unpaid",
        view.items.size == 1,
        view.items.head.catalogueNumber == "SKU-001",
        view.items.head.quantity == 2
      ),

    test("fails with EmptyItemList when items are empty"):
      val input = CheckoutInput(testCustomer, validAddress, items = List.empty)
      for
        result <- OrderService.checkout(input).exit
      yield assert(result)(fails(equalTo(OrderError.EmptyItemList))),

    test("fails with InvalidAddress when city is blank"):
      val badAddress = validAddress.copy(city = "")
      val input      = CheckoutInput(testCustomer, badAddress, List(validItem))
      for
        result <- OrderService.checkout(input).exit
      yield assert(result)(fails(isSubtype[OrderError.InvalidAddress](anything)))
  )

  private val getOrderSuite = suite("getOrder")(
    test("returns the order after checkout"):
      val input = CheckoutInput(testCustomer, validAddress, List(validItem))
      for
        created  <- OrderService.checkout(input)
        fetched  <- OrderService.getOrder(created.id)
      yield assertTrue(fetched.id == created.id),

    test("fails with OrderNotFound for unknown id"):
      val unknownId = OrderId("nonexistent")
      for
        result <- OrderService.getOrder(unknownId).exit
      yield assert(result)(fails(isSubtype[OrderError.OrderNotFound](anything)))
  )

  private val cancelOrderSuite = suite("cancelOrder")(
    test("cancels an existing order"):
      val input = CheckoutInput(testCustomer, validAddress, List(validItem))
      for
        created <- OrderService.checkout(input)
        _       <- OrderService.cancelOrder(created.id)
        fetched <- OrderService.getOrder(created.id)
      yield assertTrue(fetched.status == "Cancelled"),

    test("fails with OrderAlreadyCancelled on double cancel"):
      val input = CheckoutInput(testCustomer, validAddress, List(validItem))
      for
        created <- OrderService.checkout(input)
        _       <- OrderService.cancelOrder(created.id)
        result  <- OrderService.cancelOrder(created.id).exit
      yield assert(result)(fails(isSubtype[OrderError.OrderAlreadyCancelled](anything)))
  )

  private val errorModelSuite = suite("error model")(
    test("EmptyItemList has correct code and status"):
      val error = OrderError.EmptyItemList
      assertTrue(
        error.code == OrderError.Code.EmptyItemList,
        error.code.value == "ORD-001",
        error.code.httpStatus == 422,
        error.message.contains("no items")
      ),

    test("OrderNotFound carries the order id in the message"):
      val id    = OrderId("ord-42")
      val error = OrderError.OrderNotFound(id)
      assertTrue(
        error.code.value == "ORD-005",
        error.code.httpStatus == 404,
        error.message.contains("ord-42")
      ),

    test("InsufficientStock carries structured context"):
      val error = OrderError.InsufficientStock("SKU-99", requested = 10, available = 3)
      assertTrue(
        error.code.value == "ORD-003",
        error.details.contains("SKU-99"),
        error.details.contains("10"),
        error.details.contains("3")
      )
  )
