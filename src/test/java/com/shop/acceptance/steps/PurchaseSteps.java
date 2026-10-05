package com.shop.acceptance.steps;

import com.shop.acceptance.support.TestContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class PurchaseSteps {

    private static final Duration ORDER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOCK_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL = Duration.ofSeconds(1);

    private final TestContext ctx;

    public PurchaseSteps(TestContext ctx) {
        this.ctx = ctx;
    }

    @Given("an existing seeded product with id {string}")
    public void anExistingSeededProduct(String productId) {
        assertThat(productId).isIn("1", "2", "3");
        ctx.productId = productId;
    }

    @Given("a test product priced {double} with {int} unit(s) in stock")
    public void aTestProduct(double price, int units) {
        ctx.productId = ctx.shop.createProduct(price);
        ctx.createdProductIds.add(ctx.productId);
        ctx.shop.setStock(ctx.productId, units);
        await().atMost(STOCK_TIMEOUT).pollInterval(POLL)
                .untilAsserted(() -> assertThat(ctx.shop.availableStock(ctx.productId)).isEqualTo(units));
    }

    @When("a buyer orders {int} unit(s) of the product")
    public void aBuyerOrders(int quantity) {
        ctx.orderId = ctx.shop.createOrder(ctx.productId, quantity);
        assertThat(ctx.orderId).as("created order id").isNotBlank();
    }

    @Then("the purchase is confirmed")
    public void thePurchaseIsConfirmed() {
        theOrderEventuallyBecomes("CONFIRMED");
    }

    @Then("the order eventually becomes {string}")
    public void theOrderEventuallyBecomes(String expectedStatus) {
        await().atMost(ORDER_TIMEOUT).pollInterval(POLL)
                .untilAsserted(() -> assertThat(ctx.shop.orderStatus(ctx.orderId))
                        .as("status of order %s", ctx.orderId)
                        .isEqualTo(expectedStatus));
    }

    @Then("the available stock of the product is {int}")
    public void theAvailableStockOfTheProductIs(int expected) {
        await().atMost(STOCK_TIMEOUT).pollInterval(POLL)
                .untilAsserted(() -> assertThat(ctx.shop.availableStock(ctx.productId)).isEqualTo(expected));
    }
}
