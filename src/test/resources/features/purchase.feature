Feature: End-to-end purchase on preprod
  Full saga across gateway, order, inventory, payment and notification,
  verified against the running preprod environment through the API gateway.

  # Each scenario provisions its OWN isolated product (+ stock) on preprod and a
  # @After hook deletes it, so scenarios are independent, order-free and re-runnable.
  # Stock assertions are therefore absolute (the product starts fresh).

  Scenario: Happy path - order confirmed and stock decremented
    Given a test product priced 49.99 with 5 units in stock
    When a buyer orders 2 units of the product
    Then the order eventually becomes "CONFIRMED"
    And the available stock of the product is 3

  Scenario: Out of stock - order rejected and stock unchanged
    Given a test product priced 49.99 with 1 unit in stock
    When a buyer orders 5 units of the product
    Then the order eventually becomes "REJECTED"
    And the available stock of the product is 1

  Scenario: Payment declined - order cancelled and reserved stock released
    Given a test product priced 6.66 with 5 units in stock
    When a buyer orders 2 units of the product
    Then the order eventually becomes "CANCELLED"
    And the available stock of the product is 5
