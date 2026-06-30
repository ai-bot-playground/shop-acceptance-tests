package com.shop.acceptance.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin HTTP client hitting the preprod environment through the API gateway.
 * Target is configured via SHOP_GATEWAY_URL (default http://localhost:8080,
 * e.g. a `kubectl port-forward` to the gateway).
 *
 * The create/set/delete calls use non-prod test-support endpoints so each test
 * can provision and tear down its own isolated data.
 *
 * Supports mock login: a randomly generated (or explicitly set) userId is
 * stored as the current user and sent as the {@code X-User-Id} header on every
 * request so that shop-catalog can apply user-specific margins.
 */
public class ShopClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String currentUserId;

    public ShopClient() {
        String url = System.getenv("SHOP_GATEWAY_URL");
        this.baseUrl = (url == null || url.isBlank())
                ? "http://localhost:8080"
                : url.replaceAll("/+$", "");
    }

    // ---- mock login / user context ----

    /**
     * Generates a random mock userId, stores it as the current user, and
     * returns it. Subsequent requests will carry the {@code X-User-Id} header.
     */
    public String login() {
        this.currentUserId = UUID.randomUUID().toString();
        return this.currentUserId;
    }

    /**
     * Sets an explicit userId as the current user (useful for deterministic
     * margin verification in scenarios).
     */
    public void loginAs(String userId) {
        this.currentUserId = userId;
    }

    /** Clears the current user context; no {@code X-User-Id} header is sent. */
    public void logout() {
        this.currentUserId = null;
    }

    public String currentUserId() {
        return currentUserId;
    }

    // ---- test-data provisioning (non-prod) ----

    /** POST /api/products {name, price} -> { "id": <id> } */
    public String createProduct(double price) {
        String name = "acc-" + UUID.randomUUID();
        String payload = "{\"name\":\"" + name + "\",\"price\":" + price
                + ",\"description\":\"acceptance test product\"}";
        return send(post("/api/products", payload)).path("id").asText();
    }

    /** PUT /api/inventory/{productId} {stock} */
    public void setStock(String productId, int units) {
        send(request(URI.create(baseUrl + "/api/inventory/" + productId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"stock\":" + units + "}"))
                .build());
    }

    public void deleteProduct(String productId) {
        sendQuietly(request(URI.create(baseUrl + "/api/products/" + productId)).DELETE().build());
    }

    public void deleteStock(String productId) {
        sendQuietly(request(URI.create(baseUrl + "/api/inventory/" + productId)).DELETE().build());
    }

    // ---- purchase flow ----

    /** GET /api/inventory/{productId} -> { "available": <int> } */
    public int availableStock(String productId) {
        return send(get("/api/inventory/" + productId)).path("available").asInt();
    }

    /**
     * GET /api/products/{productId} -> { ..., "price": <double> }
     * Returns the price as seen by the current user (margin applied by
     * shop-catalog when {@code X-User-Id} is set).
     */
    public double productPrice(String productId) {
        return send(get("/api/products/" + productId)).path("price").asDouble();
    }

    /** POST /api/orders (Idempotency-Key) -> 202 { "orderId": "<id>" } */
    public String createOrder(String productId, long quantity) {
        String payload = "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
        HttpRequest req = request(URI.create(baseUrl + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return send(req).path("orderId").asText();
    }

    /** GET /api/orders/{orderId} -> { "status": "<STATE>" } */
    public String orderStatus(String orderId) {
        return send(get("/api/orders/" + orderId)).path("status").asText();
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ---- helpers ----

    /**
     * Base request builder that automatically attaches the {@code X-User-Id}
     * header when a user is logged in.
     */
    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if (currentUserId != null && !currentUserId.isBlank()) {
            builder.header("X-User-Id", currentUserId);
        }
        return builder;
    }

    private HttpRequest get(String path) {
        return request(URI.create(baseUrl + path)).GET().build();
    }

    private HttpRequest post(String path, String json) {
        return request(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + res.statusCode() + " for "
                        + req.method() + " " + req.uri() + ": " + res.body());
            }
            return res.body() == null || res.body().isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(res.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Request failed: " + req.method() + " " + req.uri(), e);
        }
    }

    /** Best-effort call used in teardown; never fails a scenario. */
    private void sendQuietly(HttpRequest req) {
        try {
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // teardown is best-effort
        }
    }
}
