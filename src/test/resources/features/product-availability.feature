Feature: Product availability through the gateway
  Buyers can read the available stock using the inventory API used by the shop list
  without placing an order. Displayed availability is not a reservation guarantee.

  Scenario: Read availability for a product with positive stock
    Given an isolated product with 100 units in stock
    When I request the product availability through the gateway
    Then the availability response status should be 200
    And the product available quantity should be 100

  Scenario: Read availability for a product with no stock
    Given an isolated product with 0 units in stock
    When I request the product availability through the gateway
    Then the availability response status should be 200
    And the product available quantity should be 0
