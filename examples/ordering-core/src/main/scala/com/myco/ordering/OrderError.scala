package com.myco.ordering

/** Domain error enum — part of the stable public contract.
  *
  * Each case carries:
  *   - A stable [[Code]] (machine-readable, never changes)
  *   - A human-readable [[details]] string (maybe refined over time)
  *   - Contextual fields for structured diagnostics
  */
enum OrderError(val code: OrderError.Code, val details: String):

  /** Computed message combining code and details. */
  def message: String = s"[${code.value}] $details"

  case EmptyItemList
    extends OrderError(Code.EmptyItemList, "Cannot create an order with no items")

  case ItemNotFound(catalogueNumber: String)
    extends OrderError(Code.ItemNotFound, s"Item '$catalogueNumber' does not exist in the catalogue")

  case InsufficientStock(catalogueNumber: String, requested: Int, available: Int)
    extends OrderError(
      Code.InsufficientStock,
      s"Item '$catalogueNumber': requested $requested but only $available available"
    )

  case InvalidAddress(reason: String)
    extends OrderError(Code.InvalidAddress, s"Shipping address is invalid: $reason")

  case OrderNotFound(orderId: OrderId)
    extends OrderError(Code.OrderNotFound, s"Order '${orderId.value}' not found")

  case OrderAlreadyCancelled(orderId: OrderId)
    extends OrderError(Code.OrderAlreadyCancelled, s"Order '${orderId.value}' is already cancelled")

end OrderError

object OrderError:

  /** Stable, machine-readable error codes.
    *
    * `value`      — string ID for API consumers (e.g. "ORD-001")
    * `httpStatus` — hint for the HTTP layer (maybe overridden contextually)
    */
  enum Code(val value: String, val httpStatus: Int):
    case EmptyItemList         extends Code("ORD-001", 422)
    case ItemNotFound          extends Code("ORD-002", 404)
    case InsufficientStock     extends Code("ORD-003", 409)
    case InvalidAddress        extends Code("ORD-004", 422)
    case OrderNotFound         extends Code("ORD-005", 404)
    case OrderAlreadyCancelled extends Code("ORD-006", 409)

end OrderError
