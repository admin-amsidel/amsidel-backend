/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AgentDtos.*;
import com.amsidel.backend.dto.TradeDtos.ProjectMoney;
import com.amsidel.backend.entity.Agent;
import com.amsidel.backend.entity.AgentCommission;
import com.amsidel.backend.entity.AgentPhoto;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Project;
import com.amsidel.backend.entity.SalesDoc;
import com.amsidel.backend.entity.TradeSubject;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.AgentCommissionRepository;
import com.amsidel.backend.repository.AgentPhotoRepository;
import com.amsidel.backend.repository.AgentRepository;
import com.amsidel.backend.repository.SalesDocRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agents who bring in projects, each brand with its own, and what they earn. A project names its agent; settling it records
 * the commission, Queued until it is marked Settled with the payment's details. History on the project says what happened,
 * never how much: whoever can see a project cannot necessarily see commissions.
 */
@Service
public class AgentService {

    private static final int MAX_PHOTO_BYTES = 1024 * 1024;

    private final AgentRepository agents;
    private final AgentPhotoRepository photos;
    private final AgentCommissionRepository commissions;
    private final SalesDocRepository sales;
    private final TradeAccess access;
    private final TradeNumbers numbers;
    private final TradeHistoryLog history;
    private final MenuAccessService menuAccess;
    private final ZoneId zone;

    public AgentService(AgentRepository agents, AgentPhotoRepository photos, AgentCommissionRepository commissions, SalesDocRepository sales, TradeAccess access, TradeNumbers numbers,
                        TradeHistoryLog history, MenuAccessService menuAccess, @Value("${app.leave.zone}") ZoneId zone) {
        this.agents = agents;
        this.photos = photos;
        this.commissions = commissions;
        this.sales = sales;
        this.access = access;
        this.numbers = numbers;
        this.history = history;
        this.menuAccess = menuAccess;
        this.zone = zone;
    }

    // ---- agents ----

    @Transactional(readOnly = true)
    public List<BrandOption> brands(AuthUser me) {
        return access.brands(me).stream().map(b -> new BrandOption(b.getId(), b.getCode(), b.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Row> list(AuthUser me, Long brandId) {
        Long scope = access.scope(me, brandId);
        boolean money = menuAccess.can(me, Menus.AGENT_COMMISSIONS);
        Map<Long, List<AgentCommission>> byAgent = new HashMap<>();
        if (money) {
            commissions.forBrand(scope).forEach(c -> byAgent.computeIfAbsent(c.getAgent().getId(), k -> new java.util.ArrayList<>()).add(c));
        }
        return agents.forBrand(scope).stream().map(a -> row(a, money ? byAgent.getOrDefault(a.getId(), List.of()) : null)).toList();
    }

    /** For the project form: who can be chosen. */
    @Transactional(readOnly = true)
    public List<Option> options(AuthUser me, Long brandId) {
        return agents.forBrand(access.scope(me, brandId)).stream()
                .map(a -> new Option(a.getId(), a.getCode(), a.getName(), a.getPhone(), a.getBrand().getId(), a.isActive())).toList();
    }

    @Transactional
    public Row create(AuthUser me, Body body) {
        Brand brand = access.brandFor(me, body.brandId());
        Agent a = new Agent();
        a.setBrand(brand);
        a.setCode(numbers.agent(brand));
        a.setCreatedBy(access.me(me));
        apply(a, body);
        return row(agents.saveAndFlush(a), List.of());
    }

    @Transactional
    public Row update(AuthUser me, Long id, Body body) {
        Agent a = agent(me, id);
        apply(a, body);
        if (body.active() != null) {
            a.setActive(body.active());
        }
        return row(agents.saveAndFlush(a), null);
    }

    private static void apply(Agent a, Body body) {
        a.setName(body.name().strip());
        a.setPhone(Phones.normalize(body.phone(), "Phone"));
        a.setAddress(text(body.address()));
        a.setLocation(text(body.location()));
        a.setUpiId(text(body.upiId()));
        a.setNotes(text(body.notes()));
    }

    private Agent agent(AuthUser me, Long id) {
        Agent a = agents.findById(id).orElseThrow(() -> new ResourceNotFoundException("Agent not found: " + id));
        access.check(me, a.getBrand());
        return a;
    }

    // ---- the photo ----

    @Transactional(readOnly = true)
    public AgentPhoto photo(AuthUser me, Long id) {
        agent(me, id);
        return photos.findById(id).orElseThrow(() -> new ResourceNotFoundException("No photo for this agent"));
    }

    /** A JPEG or PNG; what is sent is checked, since it is served back to other people. */
    @Transactional
    public Row savePhoto(AuthUser me, Long id, MultipartFile file) {
        Agent a = agent(me, id);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The photo could not be read; please try again");
        }
        if (bytes.length == 0 || bytes.length > MAX_PHOTO_BYTES) {
            throw ApiException.badRequest("The photo can be at most 1 MB");
        }
        String type = imageType(bytes);
        if (type == null) {
            throw ApiException.badRequest("Choose a JPG or PNG picture");
        }
        AgentPhoto p = photos.findById(id).orElseGet(AgentPhoto::new);
        p.setAgentId(id);
        p.setContent(bytes);
        p.setContentType(type);
        photos.save(p);
        a.setPhotoUpdatedAt(Instant.now());
        return row(agents.saveAndFlush(a), null);
    }

    @Transactional
    public Row removePhoto(AuthUser me, Long id) {
        Agent a = agent(me, id);
        photos.findById(id).ifPresent(photos::delete);
        a.setPhotoUpdatedAt(null);
        return row(agents.saveAndFlush(a), null);
    }

    private static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    // ---- the project's side ----

    /** The agent a project may name: of its brand and active, unless it already names them. */
    Agent forProject(Project p, Long agentId) {
        if (agentId == null) {
            return null;
        }
        Agent a = agents.findById(agentId).orElseThrow(() -> ApiException.badRequest("Agent not found: " + agentId));
        if (!a.getBrand().getId().equals(p.getBrand().getId())) {
            throw ApiException.badRequest(a.getName() + " is not an agent of " + p.getBrand().getName());
        }
        boolean already = p.getAgent() != null && p.getAgent().getId().equals(agentId);
        if (!a.isActive() && !already) {
            throw ApiException.badRequest(a.getName() + " is no longer active");
        }
        return a;
    }

    /**
     * The project is being settled. With an agent, what they earned has to be said: 0 means nothing. A commission already
     * paid (the project was reopened) stays exactly as it is; one still queued takes the new figures.
     */
    void recordAtSettlement(AuthUser me, Project p, BigDecimal amount, String remarks) {
        if (p.getAgent() == null) {
            return;
        }
        AgentCommission existing = commissions.findByProject_Id(p.getId()).orElse(null);
        if (existing != null && existing.getStatus() == AgentCommission.Status.SETTLED) {
            return;
        }
        if (amount == null) {
            throw ApiException.badRequest("Enter the commission for " + p.getAgent().getName() + " (0 if none)");
        }
        if (amount.signum() == 0) {
            if (existing != null) {
                commissions.delete(existing);
            }
            return;
        }
        AgentCommission c = existing != null ? existing : new AgentCommission();
        c.setAgent(p.getAgent());
        c.setProject(p);
        c.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        c.setRemarks(text(remarks));
        c.setCompletedOn(p.getCompletedAt() == null ? access.today() : LocalDate.ofInstant(p.getCompletedAt(), zone));
        if (c.getCreatedBy() == null) {
            c.setCreatedBy(access.me(me));
        }
        commissions.save(c);
        history.record(me, p.getBrand(), TradeSubject.PROJECT, p.getId(), "Project " + p.getCode() + " · " + p.getName(), "COMMENT",
                "Commission recorded for " + p.getAgent().getName(), p.getId());
    }

    /** Whether the project's commission has been paid. */
    boolean paid(Project p) {
        return commissions.findByProject_Id(p.getId()).filter(c -> c.getStatus() == AgentCommission.Status.SETTLED).isPresent();
    }

    /** A project's agent changed: a commission not yet paid follows, and goes if there is no agent now. One already paid holds the agent in place. */
    void agentChanged(Project p) {
        commissions.findByProject_Id(p.getId()).ifPresent(c -> {
            if (c.getStatus() == AgentCommission.Status.SETTLED) {
                throw ApiException.conflict("The commission has been paid to " + c.getAgent().getName() + ", so the agent cannot be changed. Move it back to queued first");
            }
            if (p.getAgent() == null) {
                commissions.delete(c);
            } else {
                c.setAgent(p.getAgent());
            }
        });
    }

    /** A project with a paid commission stays, as the record of that payment; one not yet paid goes with it. */
    void projectDeleted(Project p) {
        commissions.findByProject_Id(p.getId()).ifPresent(c -> {
            if (c.getStatus() == AgentCommission.Status.SETTLED) {
                throw ApiException.conflict("A commission for this project has been paid to " + c.getAgent().getName() + ", so it cannot be deleted");
            }
            commissions.delete(c);
            commissions.flush();
        });
    }

    // ---- commissions ----

    @Transactional(readOnly = true)
    public CommissionPage commissions(AuthUser me, Long brandId, Long agentId, AgentCommission.Status status, LocalDate from, LocalDate to) {
        List<AgentCommission> lines = commissions.forBrand(access.scope(me, brandId)).stream()
                .filter(c -> agentId == null || c.getAgent().getId().equals(agentId))
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> from == null || !c.getCompletedOn().isBefore(from))
                .filter(c -> to == null || !c.getCompletedOn().isAfter(to))
                .toList();
        // every project's bills in one query, not one for each line
        Map<Long, List<SalesDoc>> bills = lines.isEmpty() ? Map.of() : sales.forProjects(lines.stream().map(c -> c.getProject().getId()).toList()).stream()
                .collect(Collectors.groupingBy(d -> d.getProject().getId()));
        List<Commission> rows = lines.stream().map(c -> commission(c, bills.getOrDefault(c.getProject().getId(), List.of()))).toList();
        return new CommissionPage(menuAccess.can(me, Menus.AGENT_COMMISSION_STATUS), rows);
    }

    @Transactional
    public Commission setStatus(AuthUser me, Long id, StatusBody body) {
        AgentCommission c = commissions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Commission not found: " + id));
        Project p = c.getProject();
        access.check(me, p.getBrand());
        AgentCommission.Status before = c.getStatus();
        String details;
        if (body.status() == AgentCommission.Status.SETTLED) {
            if (!StringUtils.hasText(body.transactionId())) {
                throw ApiException.badRequest("Give the transaction ID (for cash, write CASH)");
            }
            if (body.paidOn() == null) {
                throw ApiException.badRequest("Give the date it was paid");
            }
            if (body.paidOn().isAfter(access.today())) {
                throw ApiException.badRequest("The date paid cannot be in the future");
            }
            c.setTransactionId(body.transactionId().strip());
            c.setPaidOn(body.paidOn());
            c.setPaymentRemarks(text(body.remarks()));
            details = before == AgentCommission.Status.SETTLED ? "Agent's payment details changed" : "Agent's commission marked as paid";
        } else {
            c.setTransactionId(null);
            c.setPaidOn(null);
            c.setPaymentRemarks(text(body.remarks()));
            details = before == AgentCommission.Status.SETTLED ? "Agent's commission moved back to queued" : null;
        }
        c.setStatus(body.status());
        c.setStatusChangedBy(access.me(me));
        c.setStatusChangedAt(Instant.now());
        if (details != null) {
            history.record(me, p.getBrand(), TradeSubject.PROJECT, p.getId(), "Project " + p.getCode() + " · " + p.getName(), "COMMENT", details, p.getId());
        }
        return commission(c, sales.forProject(p.getId()));
    }

    private static Commission commission(AgentCommission c, List<SalesDoc> bills) {
        Agent a = c.getAgent();
        Project p = c.getProject();
        ProjectMoney money = ProjectService.money(p, bills);
        return new Commission(c.getId(), a.getId(), a.getCode(), a.getName(), a.getPhone(), a.getUpiId(), p.getId(), p.getCode(), p.getName(), p.getCustomer().getName(),
                p.getBrand().getId(), p.getBrand().getCode(), p.getBrand().getCurrency().symbol(), c.getAmount(), c.getCompletedOn(), c.getRemarks(), c.getStatus(),
                c.getTransactionId(), c.getPaidOn(), c.getPaymentRemarks(), c.getStatusChangedBy() == null ? null : c.getStatusChangedBy().getName(), c.getStatusChangedAt(),
                money.paid(), money.toPay());
    }

    /** commissions null: the person may not see what the agent earned. */
    private static Row row(Agent a, List<AgentCommission> commissions) {
        BigDecimal queued = commissions == null ? null : sum(commissions, AgentCommission.Status.QUEUED);
        BigDecimal settled = commissions == null ? null : sum(commissions, AgentCommission.Status.SETTLED);
        Brand b = a.getBrand();
        return new Row(a.getId(), a.getCode(), b.getId(), b.getCode(), b.getName(), a.getName(), a.getPhone(), a.getAddress(), a.getLocation(), a.getUpiId(), a.getNotes(),
                a.isActive(), a.getPhotoUpdatedAt(), commissions == null ? null : commissions.size(), queued, settled, b.getCurrency().symbol());
    }

    private static BigDecimal sum(List<AgentCommission> list, AgentCommission.Status status) {
        return list.stream().filter(c -> c.getStatus() == status).map(AgentCommission::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String text(String v) {
        return StringUtils.hasText(v) ? v.strip() : null;
    }
}
