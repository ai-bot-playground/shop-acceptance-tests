package com.shop.acceptance.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin HTTP client hitting the preprod environment through the API gateway.
 * Target is configured via SHOP_GATEWAY_URL (default http://localhost:8080,
 * e.g. a `kubectl port-forward` to the gateway).
 *
 * The create/set/delete calls use non-prod test-support endpoints so each test
 * can provision and tear down its own isolated data.
 *
 * Session support: {@link #login(String)} hits POST /api/login and captures the
 * session cookie returned by shop-catalog. All subsequent requests carry that
 * cookie so the backend can apply user-specific margins to product prices.
 */
public class ShopClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern COOKIE_PATTERN = Pattern.compile("([^=;]+)=([^;]+)");

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String sessionCookie;

    public ShopClient() {
        String url = System.getenv("SHOP_GATEWAY_URL");
        this.baseUrl = (url == null || url.isBlank())
                ? "http://localhost:8080"
                : url.replaceAll("/+$", "");
    }

    // ---- authentication ----

    /**
     * POST /api/login { "username": "<name>" } -> 200 with Set-Cookie: SESSION=...
     * Simple passwordless login; the returned session cookie is stored and
     * attached to all subsequent requests so shop-catalog can resolve the
     * user's price margin.
     */
    public void login(String username) {
        String payload = "{\"username\":\"" + username + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        send(req);
    }

    /** Removes the stored session cookie, effectively logging out. */
    public void logout() {
        this.sessionCookie = null;
    }

    /** Returns the raw session cookie value, if any. */
    public Optional<String> sessionCookie() {
        return Optional.ofNullable(sessionCookie);
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
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/inventory/" + productId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"stock\":" + units + "}"))
                .build());
    }

    public void deleteProduct(String productId) {
        sendQuietly(HttpRequest.newBuilder(URI.create(baseUrl + "/api/products/" + productId)).DELETE().build());
    }

    public void deleteStock(String productId) {
        sendQuietly(HttpRequest.newBuilder(URI.create(baseUrl + "/api/inventory/" + productId)).DELETE().build());
    }

    // ---- purchase flow ----

    /** GET /api/inventory/{productId} -> { "available": <int> } */
    public int availableStock(String productId) {
        return send(get("/api/inventory/" + productId)).path("available").asInt();
    }

    /** POST /api/orders (Idempotency-Key) -> 202 { "orderId": "<id>" } */
    public String createOrder(String productId, long quantity) {
        String payload = "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
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

    private HttpRequest get(String path) {
        return withSession(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET()).build();
    }

    private HttpRequest post(String path, String json) {
        return withSession(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))).build();
    }

    /**
     * Attaches the session cookie to the builder if one is present.
     * Wrapped in a small helper so every outbound request automatically
     * carries authentication context.
     */
    private HttpRequest.Builder withSession(HttpRequest.Builder builder) {
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return builder;
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + res.statusCode() + " for "
                        + req.method() + " " + req.uri() + ": " + res.body());
            }
            captureSessionCookie(res);
            return res.body() == null || res.body().isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(res.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Request failed: " + req.method() + " " + req.uri(), e);
        }
    }

    /**
     * Parses Set-Cookie headers from the response and stores the first
     * SESSION cookie found. Only the SESSION name is tracked — other
     * cookies are ignored to keep the test client focused.
     */
    private void captureSessionCookie(HttpResponse<String> res) {
        List<String> cookies = res.headers().allValues("Set-Cookie");
        if (cookies.isEmpty()) {
            return;
        }
        for (String raw : cookies) {
            Matcher m = COOKIE_PATTERN.matcher(raw);
            while (m.find()) {
                String name = m.group(1).trim();
                String value = m.group(2).trim();
                if ("SESSION".equals(name)) {
                    this.sessionCookie = "SESSION=" + value;
                    return;
                }
            }
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
