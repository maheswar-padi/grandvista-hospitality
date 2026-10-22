package com.grandvista.payment.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.grandvista.payment.provider.PaymentProvider.ProviderUnavailableException;
import com.grandvista.platform.client.HttpClients;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Minimal Razorpay REST client (orders, payments, capture, refunds) using HTTP Basic auth with the key id and
 * secret. 4xx answers are returned as {@link RazorpayApiException}; network errors, timeouts and 5xx mean the
 * outcome is unknown and are raised as {@link ProviderUnavailableException}.
 */
public class RazorpayClient {

    private final RestClient http;

    public RazorpayClient(RestClient.Builder builder, RazorpayProperties properties) {
        String basic = Base64.getEncoder().encodeToString(
                (properties.keyId() + ":" + properties.keySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(HttpClients.requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    public JsonNode createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes)
            throws ProviderUnavailableException {
        return post("/v1/orders", Map.of("amount", amountMinor, "currency", currency, "receipt", receipt, "notes", notes));
    }

    public JsonNode fetchPayment(String paymentId) throws ProviderUnavailableException {
        return get("/v1/payments/" + paymentId);
    }

    public JsonNode orderPayments(String orderId) throws ProviderUnavailableException {
        return get("/v1/orders/" + orderId + "/payments");
    }

    public JsonNode capture(String paymentId, long amountMinor, String currency) throws ProviderUnavailableException {
        return post("/v1/payments/" + paymentId + "/capture", Map.of("amount", amountMinor, "currency", currency));
    }

    public JsonNode refund(String paymentId, long amountMinor, String receipt) throws ProviderUnavailableException {
        return post("/v1/payments/" + paymentId + "/refund",
                Map.of("amount", amountMinor, "speed", "normal", "receipt", receipt, "notes", Map.of("idempotencyKey", receipt)));
    }

    private JsonNode get(String path) throws ProviderUnavailableException {
        try {
            return http.get().uri(path).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException ex) {
            throw RazorpayApiException.from(ex);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("Razorpay GET " + path + " failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode post(String path, Object body) throws ProviderUnavailableException {
        try {
            return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException ex) {
            throw RazorpayApiException.from(ex);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("Razorpay POST " + path + " failed: " + ex.getMessage(), ex);
        }
    }

    /** Razorpay answered 4xx: the request was understood and refused (e.g. already captured, bad id). */
    public static class RazorpayApiException extends RuntimeException {
        private final int status;
        private final String code;

        RazorpayApiException(int status, String code, String description) {
            super("Razorpay " + status + " " + code + ": " + description);
            this.status = status;
            this.code = code;
        }

        static RazorpayApiException from(HttpClientErrorException ex) {
            String code = "UNKNOWN";
            String description = ex.getStatusText();
            try {
                JsonNode error = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ex.getResponseBodyAsString()).path("error");
                code = error.path("code").asText(code);
                description = error.path("description").asText(description);
            } catch (Exception ignored) {
                // keep defaults
            }
            return new RazorpayApiException(ex.getStatusCode().value(), code, description);
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
