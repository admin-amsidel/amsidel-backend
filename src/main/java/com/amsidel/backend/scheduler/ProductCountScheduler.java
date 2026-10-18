package com.amsidel.backend.scheduler;

import com.amsidel.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically reads product counts (read-only). Keeps the DB connection pool and the
 * Supabase project active, and logs a simple data snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCountScheduler {

    private final ProductRepository productRepository;

    @Scheduled(initialDelayString = "${app.scheduler.product-count.initial-delay:PT1M}",
               fixedDelayString = "${app.scheduler.product-count.interval:PT15M}")
    @Transactional(readOnly = true)
    public void logProductCounts() {
        try {
            long total = productRepository.count();
            long active = productRepository.countByActiveTrue();
            log.info("Product count check: total={}, active={}, inactive={}", total, active, total - active);
        } catch (Exception ex) {
            // Log and keep going; the pool reconnects and the next run retries
            log.warn("Product count check failed: {}", ex.getMessage());
        }
    }
}
