/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.HistoryView;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.TradeHistory;
import com.amsidel.backend.entity.TradeSubject;
import com.amsidel.backend.repository.TradeHistoryRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Writes the history of product-portal records (who did what, when, and what changed) in the same transaction as the
 * change, and reads it back for a record or for a brand's activity.
 */
@Component
@RequiredArgsConstructor
public class TradeHistoryLog {

    private final TradeHistoryRepository repository;
    private final TradeAccess access;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthUser me, Brand brand, TradeSubject type, Long id, String label, String action, String details) {
        record(me, brand, type, id, label, action, details, null);
    }

    /** projectId: the project this belongs to, so the project shows it in its history. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthUser me, Brand brand, TradeSubject type, Long id, String label, String action, String details, Long projectId) {
        TradeHistory h = new TradeHistory();
        h.setProjectId(projectId);
        h.setBrand(brand);
        h.setSubjectType(type);
        h.setSubjectId(id);
        h.setSubjectLabel(cut(label, 200));
        h.setAction(action);
        h.setDetails(cut(details, 2000));
        h.setDoneBy(access.me(me));
        repository.save(h);
    }

    /** A project's whole story: the project, its tasks, its bills and their payments. */
    public List<HistoryView> ofProject(Long projectId) {
        return repository.forProject(projectId).stream().map(TradeHistoryLog::view).toList();
    }

    public List<HistoryView> of(Collection<TradeSubject> types, Long id) {
        return repository.forSubject(types, id).stream().map(TradeHistoryLog::view).toList();
    }

    /** One record's history, for someone who may see its brand. */
    @Transactional(readOnly = true)
    public List<HistoryView> forRecord(AuthUser me, TradeSubject type, Long id) {
        List<TradeHistory> list = repository.forSubject(List.of(type), id);
        list.forEach(h -> access.check(me, h.getBrand()));
        return list.stream().map(TradeHistoryLog::view).toList();
    }

    /** Everything done in a brand (or all mine) from..to (dates in the office time zone), newest first. */
    @Transactional(readOnly = true)
    public List<HistoryView> activity(AuthUser me, Long brandId, java.time.LocalDate from, java.time.LocalDate to, java.time.ZoneId zone) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw com.amsidel.backend.exception.ApiException.badRequest("Ask for at most a year at a time");
        }
        return repository.inRange(access.scope(me, brandId), from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(TradeHistoryLog::view).toList();
    }

    public static HistoryView view(TradeHistory h) {
        return new HistoryView(h.getId(), h.getDoneAt(), h.getSubjectType(), h.getSubjectId(), h.getSubjectLabel(), h.getAction(),
                h.getDetails(), h.getDoneBy() == null ? null : h.getDoneBy().getName(),
                h.getBrand() == null ? null : h.getBrand().getCode());
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** "Showroom price ₹2,800.00 → ₹3,000.00; Name …": only what really changed. */
    public static final class Changes {
        private final List<String> parts = new ArrayList<>();

        public Changes add(String label, Object before, Object after) {
            String b = show(before);
            String a = show(after);
            if (!Objects.equals(b, a)) {
                parts.add(label + " " + (b.isEmpty() ? "—" : b) + " → " + (a.isEmpty() ? "—" : a));
            }
            return this;
        }

        /** A change without before / after values. */
        public Changes note(String text) {
            parts.add(text);
            return this;
        }

        public boolean isEmpty() {
            return parts.isEmpty();
        }

        @Override
        public String toString() {
            return String.join("; ", parts);
        }

        private static String show(Object v) {
            if (v == null) {
                return "";
            }
            if (v instanceof BigDecimal d) {
                return PayslipPdf.rupees(d);
            }
            if (v instanceof Boolean b) {
                return b ? "yes" : "no";
            }
            return v.toString().strip();
        }
    }
}
