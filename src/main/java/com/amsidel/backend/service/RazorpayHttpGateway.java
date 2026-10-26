package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Razorpay's REST API (https://razorpay.com/docs/api), with basic auth from the brand's key ID and secret. */
@Slf4j
@Component
public class RazorpayHttpGateway implements RazorpayGateway {

    private final String api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public RazorpayHttpGateway(@Value("${app.razorpay.api:https://api.razorpay.com/v1}") String api) {
        this.api = api.replaceAll("/+$", "");
    }

    @Override
    public Order createOrder(Keys keys, long amount, String receipt, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("receipt", receipt.length() > 40 ? receipt.substring(0, 40) : receipt);
        body.put("notes", notes);
        JsonNode o = call(keys, "POST", "/orders", body);
        return new Order(o.path("id").asString(), o.path("amount").asLong(), o.path("currency").asString(), o.path("status").asString());
    }

    @Override
    public Payment payment(Keys keys, String paymentId) {
        return payment(call(keys, "GET", "/payments/" + paymentId + "?expand[]=card", null));
    }

    @Override
    public List<Payment> orderPayments(Keys keys, String orderId) {
        List<Payment> list = new ArrayList<>();
        call(keys, "GET", "/orders/" + orderId + "/payments", null).path("items").forEach(p -> list.add(payment(p)));
        return list;
    }

    @Override
    public Payment capture(Keys keys, String paymentId, long amount, String currency) {
        return payment(call(keys, "POST", "/payments/" + paymentId + "/capture", Map.of("amount", amount, "currency", currency)));
    }

    @Override
    public Refund refund(Keys keys, String paymentId, long amount, Map<String, String> notes) {
        JsonNode r = call(keys, "POST", "/payments/" + paymentId + "/refund", Map.of("amount", amount, "notes", notes));
        return new Refund(r.path("id").asString(), r.path("amount").asLong(), r.path("status").asString());
    }

    @Override
    public Methods methods(String keyId) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(api + "/methods?key_id=" + java.net.URLEncoder.encode(keyId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Razorpay methods for {} answered {}", keyId, res.statusCode());
                return null;
            }
            JsonNode m = json.readTree(res.body());
            return new Methods(m.path("card").asBoolean(false), m.path("upi").asBoolean(false), m.path("upi_intent").asBoolean(false));
        } catch (IOException | RuntimeException e) {
            log.warn("Razorpay methods for {} could not be read: {}", keyId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Payment payment(JsonNode p) {
        JsonNode card = p.path("card");
        boolean hasCard = card.isObject();
        return new Payment(p.path("id").asString(), text(p, "order_id"), text(p, "status"), p.path("amount").asLong(),
                text(p, "currency"), text(p, "method"),
                hasCard ? text(card, "network") : null, hasCard ? text(card, "last4") : null, hasCard ? text(card, "issuer") : null,
                hasCard ? text(card, "type") : null, hasCard && card.has("international") ? card.path("international").asBoolean() : null,
                text(p, "vpa"), text(p, "bank"), text(p, "wallet"), text(p, "email"), text(p, "contact"), text(p, "error_description"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    private JsonNode call(Keys keys, String method, String path, Object body) {
        String auth = Base64.getEncoder().encodeToString((keys.keyId() + ":" + keys.keySecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth).header("Accept", "application/json");
        if (body == null) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Razorpay {} {} could not be reached: {}", method, path, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Razorpay could not be reached; please try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Razorpay could not be reached; please try again");
        }
        JsonNode node;
        try {
            node = json.readTree(res.body());
        } catch (RuntimeException e) {
            node = json.createObjectNode();
        }
        if (res.statusCode() == 401) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Razorpay refused the keys " + keys.keyId()
                    + " (Authentication failed): check the key ID and secret in Brands, or RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET");
        }
        if (res.statusCode() >= 300) {
            String why = text(node.path("error"), "description");
            log.warn("Razorpay {} {} answered {}: {}", method, path, res.statusCode(), why);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Razorpay: " + (why == null ? "the request was refused (" + res.statusCode() + ")" : why));
        }
        return node;
    }
}
