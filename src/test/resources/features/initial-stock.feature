Feature: Purchase seeded products with initial stock
  On a fresh deployment, each seeded catalog product can be purchased
  through the gateway without provisioning products or setting stock
  through test-support endpoints.

  Scenario Outline: Purchase a seeded product without setting its stock
    Given an existing seeded product with id "<productId>"
    When a buyer orders 1 unit of the product
    Then the purchase is confirmed

    Examples:
      | productId |
      | 1         |
      | 2         |
      | 3         |
