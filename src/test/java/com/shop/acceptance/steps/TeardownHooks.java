package com.shop.acceptance.steps;

import com.shop.acceptance.support.TestContext;
import io.cucumber.java.After;

/**
 * Removes everything the scenario provisioned on preprod, so each test leaves
 * the environment clean and tests stay independent. Best-effort (never fails).
 */
public class TeardownHooks {

    private final TestContext ctx;

    public TeardownHooks(TestContext ctx) {
        this.ctx = ctx;
    }

    @After
    public void removeProvisionedData() {
        for (String productId : ctx.createdProductIds) {
            ctx.shop.deleteStock(productId);
            ctx.shop.deleteProduct(productId);
        }
        ctx.createdProductIds.clear();
    }
}
