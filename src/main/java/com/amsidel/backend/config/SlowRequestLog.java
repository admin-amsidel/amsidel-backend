/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Logs each API call slower than app.slow-request-ms (2 s at first) with the database pool at that moment: how
 * many connections are in use, idle, and how many calls are waiting for one. Waiting calls mean the pool is the
 * bottleneck (e.g. the whole office opening the portal at 9:00); none waiting means the time went elsewhere
 * (the database, the network, or a cold server).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestLog extends OncePerRequestFilter {

    private final long slowMs;
    private final HikariDataSource hikari;

    public SlowRequestLog(@Value("${app.slow-request-ms:2000}") long slowMs, DataSource dataSource) {
        this.slowMs = slowMs;
        this.hikari = dataSource instanceof HikariDataSource h ? h : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= slowMs) {
                // the pool MXBean is there once the pool has started (after the first connection)
                HikariPoolMXBean p = hikari == null ? null : hikari.getHikariPoolMXBean();
                log.warn("Slow call: {} {} took {} ms (status {}); database pool: {}", request.getMethod(), request.getRequestURI(), ms,
                        response.getStatus(), p == null ? "n/a" : p.getActiveConnections() + " in use, " + p.getIdleConnections()
                                + " idle, " + p.getThreadsAwaitingConnection() + " waiting, of " + p.getTotalConnections());
            }
        }
    }
}
