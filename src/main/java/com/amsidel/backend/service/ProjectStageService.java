/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.ProjectStage;
import com.amsidel.backend.entity.ProjectStage.Phase;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.ProjectStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The project statuses, from the project_statuses table. Seeded once in their order; the codes projects had before
 * (QUEUED, IN_PROGRESS, COMPLETED, CANCELLED) are kept, In progress now reads Started.
 */
@Service
@RequiredArgsConstructor
public class ProjectStageService {

    /** One project status as the portal and the customer's link show it. */
    public record StageView(String code, String name, Phase phase, int sortOrder, boolean active) {
    }

    private record Seed(String code, String name, Phase phase) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed(ProjectStage.QUEUED, "Queued", Phase.OPEN),
            new Seed("QUOTATION_ISSUED", "Quotation issued", Phase.OPEN),
            new Seed("QUOTATION_APPROVED", "Quotation approved", Phase.OPEN),
            new Seed("CONFIRMED", "Confirmed", Phase.OPEN),
            new Seed(ProjectStage.STARTED, "Started", Phase.OPEN),
            new Seed(ProjectStage.COMPLETED, "Completed", Phase.DONE),
            new Seed(ProjectStage.CANCELLED, "Cancelled", Phase.CANCELLED));

    private final ProjectStageRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    public void seed() {
        int order = 0;
        for (Seed s : SEEDS) {
            order += 10;
            if (!repository.existsById(s.code())) {
                ProjectStage st = new ProjectStage();
                st.setCode(s.code());
                st.setName(s.name());
                st.setPhase(s.phase());
                st.setSortOrder(order);
                st.setUpdatedAt(Instant.now());
                repository.save(st);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<StageView> list() {
        return all().stream().map(ProjectStageService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectStage> all() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    /** The status a project is set to: it must exist, and be active unless the project already has it. */
    @Transactional(readOnly = true)
    public ProjectStage require(String code, String current) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Choose the status");
        }
        ProjectStage s = repository.findById(code.strip().toUpperCase(java.util.Locale.ROOT))
                .orElseThrow(() -> ApiException.badRequest("Unknown project status: " + code));
        if (!s.isActive() && !s.getCode().equals(current)) {
            throw ApiException.badRequest("The status \"" + s.getName() + "\" is switched off");
        }
        return s;
    }

    /** The status of this code (null when unknown). */
    @Transactional(readOnly = true)
    public ProjectStage find(String code) {
        return code == null ? null : repository.findById(code).orElse(null);
    }

    /** "Started" for IN_PROGRESS; the code itself when unknown. */
    public String name(String code) {
        if (code == null) {
            return null;
        }
        ProjectStage s = find(code);
        return s == null ? code : s.getName();
    }

    /** Still being worked on (not done, not called off); an unknown code counts as open. */
    public boolean isOpen(String code) {
        ProjectStage s = find(code);
        return s == null || s.isOpen();
    }

    public boolean isDone(String code) {
        ProjectStage s = find(code);
        return s != null && s.isDone();
    }

    static StageView view(ProjectStage s) {
        return new StageView(s.getCode(), s.getName(), s.getPhase(), s.getSortOrder(), s.isActive());
    }
}
