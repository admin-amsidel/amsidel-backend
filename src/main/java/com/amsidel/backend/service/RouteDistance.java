/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The distance of a trip through its points in order. With a Google Maps key (app.maps.google-api-key) each leg asks
 * Google's Directions for its routes, and the trip's shortest and longest are the sums of each leg's shortest and longest
 * route. Without a key, or when Google cannot answer, it is estimated from the GPS points: the straight line between
 * them as the shortest, and that times 1.4 (roads are seldom straight) as the longest.
 */
@Slf4j
@Component
public class RouteDistance {

    public record Point(double latitude, double longitude) {
    }

    /** source: GOOGLE or STRAIGHT_LINE. */
    public record Result(double shortestKm, double longestKm, String source) {
    }

    static final double ROAD_FACTOR = 1.4;
    private static final double EARTH_KM = 6371.0088;

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public RouteDistance(@Value("${app.maps.google-api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public Result of(List<Point> points) {
        if (points.size() < 2) {
            return new Result(0, 0, "STRAIGHT_LINE");
        }
        if (!apiKey.isEmpty()) {
            try {
                double shortest = 0;
                double longest = 0;
                for (int i = 1; i < points.size(); i++) {
                    double[] leg = googleLeg(points.get(i - 1), points.get(i));
                    shortest += leg[0];
                    longest += leg[1];
                }
                return new Result(round(shortest), round(longest), "GOOGLE");
            } catch (Exception e) {
                log.warn("Google Maps could not give the route, estimated from the GPS points instead: {}", e.getMessage());
            }
        }
        double straight = 0;
        for (int i = 1; i < points.size(); i++) {
            straight += straightKm(points.get(i - 1), points.get(i));
        }
        return new Result(round(straight), round(straight * ROAD_FACTOR), "STRAIGHT_LINE");
    }

    /** The shortest and longest route of one leg, in km. */
    private double[] googleLeg(Point a, Point b) throws Exception {
        String url = "https://maps.googleapis.com/maps/api/directions/json?alternatives=true&mode=driving&origin=" + coords(a)
                + "&destination=" + coords(b) + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = json.readTree(response.body());
        String status = body.path("status").asString();
        if ("ZERO_RESULTS".equals(status)) {
            double km = straightKm(a, b);
            return new double[]{km, km};
        }
        if (!"OK".equals(status)) {
            throw new IllegalStateException("Directions answered " + status);
        }
        double min = Double.MAX_VALUE;
        double max = 0;
        for (JsonNode route : body.path("routes")) {
            double metres = 0;
            for (JsonNode leg : route.path("legs")) {
                metres += leg.path("distance").path("value").asDouble();
            }
            min = Math.min(min, metres / 1000);
            max = Math.max(max, metres / 1000);
        }
        if (min == Double.MAX_VALUE) {
            throw new IllegalStateException("Directions gave no route");
        }
        return new double[]{min, max};
    }

    private static String coords(Point p) {
        return String.format(Locale.ROOT, "%.6f,%.6f", p.latitude(), p.longitude());
    }

    /** Along the earth's surface. */
    static double straightKm(Point a, Point b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLng = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude())) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static double round(double km) {
        return Math.round(km * 100) / 100.0;
    }
}
