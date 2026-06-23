package com.shop.acceptance.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Scenario-scoped state, shared between step/hook classes via PicoContainer DI.
 * Cucumber creates a fresh instance per scenario, which keeps tests isolated.
 */
public class TestContext {

    public final ShopClient shop = new ShopClient();

    /** Product provisioned by the current scenario. */
    public String productId;

    /** Order created by the current scenario. */
    public String orderId;

    /** Everything provisioned by this scenario, removed in @After. */
    public final List<String> createdProductIds = new ArrayList<>();
}
