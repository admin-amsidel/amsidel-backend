/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.ProjectUpdateDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProjectRepository;
import com.amsidel.backend.repository.ProjectUpdatePhotoRepository;
import com.amsidel.backend.repository.ProjectUpdateRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Project status updates for customers. People with the Project status checkpoint post updates (stage, title, what was
 * done, when, photos); everyone who sees projects sees them. The newest update sets the project's status. A project
 * can be shared with its customer by a link (/track/{project code}/{secret}) that needs no sign-in and shows only the
 * updates and their photos; it can be switched off or replaced (the old link stops working).
 */
@Service
@RequiredArgsConstructor
public class ProjectUpdateService {

    private static final int MAX_PHOTOS = 8;
    private static final int MAX_PHOTO = 5 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final ProjectRepository projects;
    private final ProjectUpdateRepository updates;
    private final ProjectUpdatePhotoRepository photos;
    private final TradeAccess access;
    private final TradeHistoryLog history;
    private final ProjectStageService stages;

    private void canSee(AuthUser me) {
        access.require(me, "You cannot see projects", Menus.PROD_PROJECT_STATUS, Menus.PROD_PROJECTS, Menus.PROD_QUOTES,
                Menus.PROD_INVOICES, Menus.PROD_REPORTS);
    }

    private boolean canPost(AuthUser me) {
        return access.has(me, Menus.PROD_PROJECT_STATUS);
    }

    private void requirePost(AuthUser me) {
        if (!canPost(me)) {
            throw ApiException.forbidden("Posting project status updates needs the Project status access");
        }
    }

    private Project project(AuthUser me, Long id) {
        Project p = projects.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
        access.check(me, p.getBrand());
        return p;
    }

    // ---- reading ----

    /** The Project status page: every project of my brands with its updates count, last update and whether it is shared. */
    @Transactional(readOnly = true)
    public List<StatusRow> board(AuthUser me, Long brandId) {
        canSee(me);
        List<Project> list = projects.forBrand(access.scope(me, brandId));
        Map<Long, Object[]> sums = list.isEmpty() ? Map.of() : updates.summary(list.stream().map(Project::getId).toList()).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> r));
        return list.stream().map(p -> row(p, sums.get(p.getId())))
                .sorted(Comparator.comparing((StatusRow r) -> r.lastUpdateAt() == null ? Instant.EPOCH : r.lastUpdateAt()).reversed()
                        .thenComparing(StatusRow::code, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectStatusView of(AuthUser me, Long projectId) {
        return of(me, projectId, null);
    }

    private ProjectStatusView of(AuthUser me, Long projectId, Long touchedId) {
        canSee(me);
        Project p = project(me, projectId);
        List<ProjectUpdate> list = updates.forProject(p.getId());
        Instant last = list.isEmpty() ? null : list.getFirst().getHappenedAt();
        StatusRow row = row(p, new Object[] {p.getId(), (long) list.size(), last});
        return new ProjectStatusView(row, list.stream().map(u -> view(me, u)).toList(), sharePath(p), canPost(me), touchedId);
    }

    private StatusRow row(Project p, Object[] sum) {
        return new StatusRow(p.getId(), p.getCode(), p.getName(), p.getBrand().getCode(), p.getCustomer().getId(), p.getCustomer().getName(),
                p.getCustomer().getPhone(), p.getStatus(), p.isSettled(), sum == null ? 0 : ((Number) sum[1]).intValue(),
                sum == null ? null : (Instant) sum[2], p.getShareToken() != null);
    }

    private UpdateView view(AuthUser me, ProjectUpdate u) {
        return new UpdateView(u.getId(), u.getProject().getId(), u.getStatus(), u.getTitle(), u.getDescription(), u.getHappenedAt(),
                u.getPhotos().stream().map(ProjectUpdatePhoto::getId).toList(),
                u.getCreatedBy() == null ? null : u.getCreatedBy().getName(), u.getCreatedAt(),
                u.getUpdatedBy() == null ? null : u.getUpdatedBy().getName(), u.getUpdatedAt(), canEdit(me, u));
    }

    /** The person who posted it, or an owner or admin, with the Project status access. */
    private boolean canEdit(AuthUser me, ProjectUpdate u) {
        return canPost(me) && (access.admin(me) || u.getCreatedBy() != null && u.getCreatedBy().getId().equals(me.id()));
    }

    // ---- posting ----

    @Transactional
    public ProjectStatusView post(AuthUser me, Long projectId, UpdateBody body) {
        canSee(me);
        requirePost(me);
        Project p = project(me, projectId);
        ProjectUpdate u = new ProjectUpdate();
        u.setProject(p);
        fill(u, body);
        u.setCreatedBy(access.me(me));
        u.setCreatedAt(Instant.now());
        updates.saveAndFlush(u);
        log(me, p, "STATUS UPDATE", "Posted “" + u.getTitle() + "” (" + word(u.getStatus()) + ", " + WHEN.format(u.getHappenedAt()) + ")");
        follow(me, p);
        return of(me, projectId, u.getId());
    }

    @Transactional
    public ProjectStatusView edit(AuthUser me, Long updateId, UpdateBody body) {
        canSee(me);
        ProjectUpdate u = editable(me, updateId);
        String before = u.getTitle();
        fill(u, body);
        u.setUpdatedBy(access.me(me));
        u.setUpdatedAt(Instant.now());
        updates.saveAndFlush(u);
        log(me, u.getProject(), "STATUS UPDATE", "Changed “" + before + "”" + (before.equals(u.getTitle()) ? "" : " to “" + u.getTitle() + "”")
                + " (" + word(u.getStatus()) + ", " + WHEN.format(u.getHappenedAt()) + ")");
        follow(me, u.getProject());
        return of(me, u.getProject().getId(), u.getId());
    }

    @Transactional
    public ProjectStatusView delete(AuthUser me, Long updateId) {
        canSee(me);
        ProjectUpdate u = editable(me, updateId);
        Project p = u.getProject();
        updates.delete(u);
        updates.flush();
        log(me, p, "STATUS UPDATE", "Removed “" + u.getTitle() + "” (" + WHEN.format(u.getHappenedAt()) + ")");
        return of(me, p.getId());
    }

    private ProjectUpdate editable(AuthUser me, Long updateId) {
        requirePost(me);
        ProjectUpdate u = updates.findFull(updateId).orElseThrow(() -> new ResourceNotFoundException("Update not found: " + updateId));
        access.check(me, u.getProject().getBrand());
        if (!canEdit(me, u)) {
            throw ApiException.forbidden("Only the person who posted it, or an owner or admin, can change this update");
        }
        return u;
    }

    private void fill(ProjectUpdate u, UpdateBody body) {
        Instant when = body.getHappenedAt() == null ? Instant.now() : body.getHappenedAt();
        if (when.isAfter(Instant.now().plus(Duration.ofMinutes(10)))) {
            throw ApiException.badRequest("The date and time cannot be in the future");
        }
        u.setStatus(stages.require(body.getStatus(), u.getStatus()).getCode());
        u.setTitle(body.getTitle().strip());
        u.setDescription(blank(body.getDescription()));
        u.setHappenedAt(when);
    }

    /** The project's status follows its newest update. */
    private void follow(AuthUser me, Project p) {
        List<ProjectUpdate> list = updates.forProject(p.getId());
        if (list.isEmpty() || list.getFirst().getStatus().equals(p.getStatus())) {
            return;
        }
        String before = p.getStatus();
        String now = list.getFirst().getStatus();
        p.setStatus(now);
        p.setCompletedAt(stages.isDone(now) ? (p.getCompletedAt() == null ? Instant.now() : p.getCompletedAt()) : null);
        p.setUpdatedBy(access.me(me));
        p.setUpdatedAt(Instant.now());
        projects.saveAndFlush(p);
        log(me, p, "STATUS", word(before) + " → " + word(now) + " (from the status update “" + list.getFirst().getTitle() + "”)");
    }

    // ---- photos ----

    @Transactional
    public ProjectStatusView addPhotos(AuthUser me, Long updateId, List<MultipartFile> files) {
        canSee(me);
        ProjectUpdate u = editable(me, updateId);
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("Choose the photos to add");
        }
        if (u.getPhotos().size() + files.size() > MAX_PHOTOS) {
            throw ApiException.badRequest("An update can have at most " + MAX_PHOTOS + " photos");
        }
        int order = u.getPhotos().stream().mapToInt(ProjectUpdatePhoto::getSortOrder).max().orElse(0);
        for (MultipartFile f : files) {
            if (f.getSize() > MAX_PHOTO) {
                throw ApiException.badRequest("Each photo must be 5 MB or smaller");
            }
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException e) {
                throw ApiException.badRequest("A photo could not be read; please try again");
            }
            String type = imageType(bytes);
            if (type == null) {
                throw ApiException.badRequest("Photos must be JPEG, PNG or WebP images");
            }
            ProjectUpdatePhoto ph = new ProjectUpdatePhoto();
            ph.setUpdate(u);
            ph.setContent(bytes);
            ph.setContentType(type);
            ph.setSortOrder(order += 10);
            ph.setCreatedAt(Instant.now());
            u.getPhotos().add(ph);
        }
        u.setUpdatedBy(access.me(me));
        u.setUpdatedAt(Instant.now());
        updates.saveAndFlush(u);
        log(me, u.getProject(), "STATUS UPDATE", files.size() + " photo" + (files.size() == 1 ? "" : "s") + " added to “" + u.getTitle() + "”");
        return of(me, u.getProject().getId());
    }

    @Transactional
    public ProjectStatusView deletePhoto(AuthUser me, Long photoId) {
        canSee(me);
        ProjectUpdatePhoto ph = photos.findFull(photoId).orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + photoId));
        ProjectUpdate u = editable(me, ph.getUpdate().getId());
        u.getPhotos().removeIf(x -> x.getId().equals(photoId));
        u.setUpdatedBy(access.me(me));
        u.setUpdatedAt(Instant.now());
        updates.saveAndFlush(u);
        return of(me, u.getProject().getId());
    }

    @Transactional(readOnly = true)
    public ProjectUpdatePhoto photo(AuthUser me, Long photoId) {
        canSee(me);
        ProjectUpdatePhoto ph = photos.findFull(photoId).orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + photoId));
        access.check(me, ph.getUpdate().getProject().getBrand());
        ph.getContent();
        return ph;
    }

    // ---- the customer's link ----

    /** Shares the project with its customer (a new secret each time: an older link stops working). */
    @Transactional
    public ProjectStatusView share(AuthUser me, Long projectId) {
        canSee(me);
        requirePost(me);
        Project p = project(me, projectId);
        boolean renewed = p.getShareToken() != null;
        p.setShareToken(token());
        projects.saveAndFlush(p);
        log(me, p, "SHARED", renewed ? "A new status link for the customer (the old one no longer works)" : "Status link made for the customer");
        return of(me, projectId);
    }

    @Transactional
    public ProjectStatusView unshare(AuthUser me, Long projectId) {
        canSee(me);
        requirePost(me);
        Project p = project(me, projectId);
        if (p.getShareToken() != null) {
            p.setShareToken(null);
            projects.saveAndFlush(p);
            log(me, p, "SHARED", "Status link switched off");
        }
        return of(me, projectId);
    }

    /** What the customer sees: only with the right project code and secret, and while the link is on. */
    @Transactional(readOnly = true)
    public PublicStatus publicStatus(String code, String token) {
        Project p = shared(code, token);
        Brand b = p.getBrand();
        List<PublicUpdate> list = updates.forProject(p.getId()).stream()
                .map(u -> new PublicUpdate(u.getId(), u.getStatus(), u.getTitle(), u.getDescription(), u.getHappenedAt(),
                        u.getPhotos().stream().map(ProjectUpdatePhoto::getId).toList()))
                .toList();
        return new PublicStatus(b.getName(), b.getId(), b.getLogoUpdatedAt(), b.getContactNumber(), b.getEmail(), p.getCode(), p.getName(),
                p.getCustomer().getName(), p.getStatus(), p.getStartDate(), p.getEndDate(), list,
                stages.all().stream().filter(s -> s.isActive() || s.getCode().equals(p.getStatus())).map(ProjectStageService::view).toList());
    }

    @Transactional(readOnly = true)
    public ProjectUpdatePhoto publicPhoto(String code, String token, Long photoId) {
        Project p = shared(code, token);
        ProjectUpdatePhoto ph = photos.findFull(photoId).filter(x -> x.getUpdate().getProject().getId().equals(p.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        ph.getContent();
        return ph;
    }

    private Project shared(String code, String token) {
        Project p = token == null || token.length() < 16 ? null : projects.findByShareToken(token).orElse(null);
        if (p == null || !p.getCode().equalsIgnoreCase(code)) {
            // the same answer for a wrong code, a wrong secret or a link switched off
            throw new ResourceNotFoundException("This status link is not valid any more; ask for a new one");
        }
        return p;
    }

    private static String sharePath(Project p) {
        return p.getShareToken() == null ? null : "/track/" + p.getCode() + "/" + p.getShareToken();
    }

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- helpers ----

    private void log(AuthUser me, Project p, String action, String details) {
        history.record(me, p.getBrand(), TradeSubject.PROJECT, p.getId(), "Project " + p.getCode() + " · " + p.getName(), action, details, p.getId());
    }

    private String word(String code) {
        return stages.name(code);
    }

    private static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
