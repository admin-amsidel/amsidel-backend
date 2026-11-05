/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.StorageDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How much the portal stores and the list of stored files, with deleting them to free space. The file sizes come from
 * the register (StoredFile), kept up to date at each upload; the database's own size is asked of the database and
 * remembered for a while, as no one needs it to the minute.
 */
@Slf4j
@Service
public class StorageService {

    private static final Duration REMEMBER = Duration.ofMinutes(10);
    private static final Set<String> SORTABLE = Set.of("uploadedAt", "sizeBytes", "name", "owner", "fileType");

    private final StoredFileRepository files;
    private final StoredFileSync sync;
    private final TradeAccess access;
    private final MenuAccessService menuAccess;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final TransactionTemplate transaction;
    private final EmployeeDocumentRepository documents;
    private final EmployeeDocumentFileRepository documentFiles;
    private final EmployeeDocumentStore documentDisk;
    private final EmployeePhotoRepository employeePhotos;
    private final EmployeeRepository employees;
    private final AgentPhotoRepository agentPhotos;
    private final AgentRepository agents;
    private final AttendanceRepository attendance;
    private final AttendancePhotoStore checkInPhotos;
    private final ProjectUpdatePhotoRepository projectPhotos;
    private final NewsImageRepository newsImages;
    private final NewsPostRepository news;
    private final BrandService brands;
    private final OfferLetterFileRepository offerFiles;
    private final OfferLetterService offerLetters;
    private final DataSize maxDatabase;
    private final Path checkInFolder;
    private final ZoneId zone;

    private record Measured(Database database, List<TableSize> tables, Instant at) {
    }

    private final AtomicReference<Measured> remembered = new AtomicReference<>();

    public StorageService(StoredFileRepository files, StoredFileSync sync, TradeAccess access, MenuAccessService menuAccess, JdbcTemplate jdbc, EntityManager em,
                          TransactionTemplate transaction, EmployeeDocumentRepository documents, EmployeeDocumentFileRepository documentFiles, EmployeeDocumentStore documentDisk,
                          EmployeePhotoRepository employeePhotos, EmployeeRepository employees, AgentPhotoRepository agentPhotos, AgentRepository agents,
                          AttendanceRepository attendance, AttendancePhotoStore checkInPhotos, ProjectUpdatePhotoRepository projectPhotos, NewsImageRepository newsImages,
                          NewsPostRepository news, BrandService brands, OfferLetterFileRepository offerFiles, OfferLetterService offerLetters,
                          @Value("${app.database.max-size}") DataSize maxDatabase, @Value("${app.attendance.photo-dir}") String checkInFolder,
                          @Value("${app.leave.zone}") ZoneId zone) {
        this.files = files;
        this.sync = sync;
        this.access = access;
        this.menuAccess = menuAccess;
        this.jdbc = jdbc;
        this.em = em;
        this.transaction = transaction;
        this.documents = documents;
        this.documentFiles = documentFiles;
        this.documentDisk = documentDisk;
        this.employeePhotos = employeePhotos;
        this.employees = employees;
        this.agentPhotos = agentPhotos;
        this.agents = agents;
        this.attendance = attendance;
        this.checkInPhotos = checkInPhotos;
        this.projectPhotos = projectPhotos;
        this.newsImages = newsImages;
        this.news = news;
        this.brands = brands;
        this.offerFiles = offerFiles;
        this.offerLetters = offerLetters;
        this.maxDatabase = maxDatabase;
        this.checkInFolder = Path.of(checkInFolder).toAbsolutePath().normalize();
        this.zone = zone;
    }

    // ---- the figures ----

    /** Not in one transaction: the sync below runs its own, and takes its time only when the figures are old. */
    public Summary summary(AuthUser me, boolean refresh) {
        boolean all = access.allBrands(me);
        Long scope = access.scope(me, null);
        Measured m = measured(refresh);

        Map<String, long[]> byLocation = new HashMap<>();
        files.byLocation().forEach(r -> byLocation.put(r[0].toString(), new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()}));
        long[] inDatabase = byLocation.getOrDefault(StoredFile.Location.DATABASE.name(), new long[2]);
        long[] onDisk = byLocation.getOrDefault(StoredFile.Location.DISK.name(), new long[2]);

        List<Group> byCategory = files.byCategory(scope).stream()
                .map(r -> { StoredFileCategory c = (StoredFileCategory) r[0]; return new Group(c.name(), c.getLabel(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue()); })
                .sorted(Comparator.comparingLong(Group::bytes).reversed()).toList();
        List<Group> byType = groups(files.byFileType(scope));
        List<Group> byBrand = files.byBrand(scope).stream()
                .map(r -> new Group(r[0] == null ? "" : r[0].toString(), r[0] == null ? "Group level" : r[0].toString(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                .sorted(Comparator.comparingLong(Group::bytes).reversed()).toList();
        long count = byCategory.stream().mapToLong(Group::files).sum();
        long bytes = byCategory.stream().mapToLong(Group::bytes).sum();

        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Object[] month = files.since(monthStart, scope).getFirst();

        Database database = all ? withFiles(m.database(), inDatabase[1]) : null;
        return new Summary(database, all ? disk(onDisk) : null, count, bytes, ((Number) month[0]).longValue(), ((Number) month[1]).longValue(),
                byCategory, byType, byBrand, all ? m.tables() : List.of(),
                access.brands(me).stream().map(b -> new BrandOption(b.getId(), b.getCode(), b.getName())).toList(),
                Arrays.stream(StoredFileCategory.values()).map(c -> new Option(c.name(), c.getLabel())).toList(),
                menuAccess.can(me, Menus.STORAGE_DELETE));
    }

    private static List<Group> groups(List<Object[]> rows) {
        return rows.stream().map(r -> new Group(r[0].toString(), r[0].toString(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                .sorted(Comparator.comparingLong(Group::bytes).reversed()).toList();
    }

    private Database withFiles(Database d, long fileBytes) {
        return new Database(d.available(), d.usedBytes(), d.maxBytes(), d.percent(), fileBytes, d.available() ? Math.max(0, d.usedBytes() - fileBytes) : 0, d.measuredAt());
    }

    private Measured measured(boolean refresh) {
        Measured m = remembered.get();
        if (!refresh && m != null && m.at().plus(REMEMBER).isAfter(Instant.now())) {
            return m;
        }
        sync.sync();
        long max = maxDatabase.toBytes();
        Instant now = Instant.now();
        Measured fresh;
        try {
            if (!postgres()) {
                throw new IllegalStateException("not PostgreSQL");
            }
            Long used = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
            long size = used == null ? 0 : used;
            List<TableSize> tables = jdbc.query("select relname, pg_total_relation_size(relid) as bytes, n_live_tup from pg_stat_user_tables order by 2 desc limit 10",
                    (rs, i) -> new TableSize(rs.getString(1), rs.getLong(2), rs.getLong(3)));
            int percent = max <= 0 ? 0 : (int) Math.min(100, size * 100 / max);
            fresh = new Measured(new Database(true, size, max, percent, 0, 0, now), tables, now);
        } catch (RuntimeException e) {
            log.debug("The database's size is not available: {}", e.getMessage());
            fresh = new Measured(new Database(false, 0, max, 0, 0, 0, now), List.of(), now);
        }
        remembered.set(fresh);
        return fresh;
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    private Disk disk(long[] onDisk) {
        long total = 0;
        long usable = 0;
        try {
            Path existing = checkInFolder;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                FileStore store = Files.getFileStore(existing);
                total = store.getTotalSpace();
                usable = store.getUsableSpace();
            }
        } catch (IOException e) {
            log.debug("The disk's size is not available: {}", e.getMessage());
        }
        return new Disk(onDisk[1], onDisk[0], total, usable);
    }

    // ---- the list ----

    @Transactional(readOnly = true)
    public FilePage list(AuthUser me, StoredFileCategory category, String fileType, Long brandId, String query, int page, int size, String sort, boolean descending) {
        Long scope = access.scope(me, brandId);
        Specification<StoredFile> spec = (root, q, cb) -> filter(root, cb, category, fileType, scope, query);
        String by = SORTABLE.contains(sort) ? sort : "uploadedAt";
        PageRequest request = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200), Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, by).and(Sort.by(Sort.Direction.DESC, "id")));
        Page<StoredFile> found = files.findAll(spec, request);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> sum = cb.createQuery(Long.class);
        Root<StoredFile> root = sum.from(StoredFile.class);
        sum.select(cb.coalesce(cb.sum(root.<Long>get("sizeBytes")), 0L)).where(filter(root, cb, category, fileType, scope, query));
        long totalBytes = em.createQuery(sum).getSingleResult();

        boolean offerLettersToo = menuAccess.can(me, Menus.OFFER_DELETE);
        return new FilePage(found.getContent().stream().map(f -> row(f, offerLettersToo)).toList(), found.getTotalElements(), totalBytes);
    }

    private static Predicate filter(Root<StoredFile> root, CriteriaBuilder cb, StoredFileCategory category, String fileType, Long brandId, String query) {
        List<Predicate> all = new ArrayList<>();
        if (category != null) {
            all.add(cb.equal(root.get("category"), category));
        }
        if (StringUtils.hasText(fileType)) {
            all.add(cb.equal(root.get("fileType"), fileType.strip().toUpperCase(Locale.ROOT)));
        }
        if (brandId != null) {
            all.add(cb.equal(root.join("brand").get("id"), brandId));
        }
        if (StringUtils.hasText(query)) {
            String like = "%" + query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            all.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("owner")), like, '\\')));
        }
        return cb.and(all.toArray(new Predicate[0]));
    }

    private static FileRow row(StoredFile f, boolean offerLettersToo) {
        return new FileRow(f.getId(), f.getCategory().name(), f.getCategory().getLabel(), f.getFileType(), f.getName(), f.getOwner(),
                f.getBrand() == null ? null : f.getBrand().getId(), f.getBrand() == null ? null : f.getBrand().getCode(), f.getSizeBytes(), f.getLocation().name(),
                f.getUploadedBy() == null ? null : f.getUploadedBy().getName(), f.getUploadedAt(), f.getCategory() != StoredFileCategory.OFFER_LETTER || offerLettersToo);
    }

    // ---- opening one ----

    @Transactional(readOnly = true)
    public FileContent content(AuthUser me, Long id) {
        StoredFile f = allowed(me, id);
        Long ref = f.getRefId();
        byte[] bytes = switch (f.getCategory()) {
            case EMPLOYEE_DOCUMENT -> {
                EmployeeDocument d = documents.findById(ref).orElseThrow(StorageService::gone);
                yield d.getStoredPath() != null ? documentDisk.read(d.getStoredPath())
                        : documentFiles.findById(ref).map(EmployeeDocumentFile::getContent).orElseThrow(StorageService::gone);
            }
            case PROFILE_PHOTO -> employeePhotos.findById(ref).map(EmployeePhoto::getContent).orElseThrow(StorageService::gone);
            case AGENT_PHOTO -> agentPhotos.findById(ref).map(AgentPhoto::getContent).orElseThrow(StorageService::gone);
            case CHECK_IN_PHOTO -> {
                String path = attendance.findById(ref).map(Attendance::getPhotoPath).orElseThrow(StorageService::gone);
                yield checkInPhotos.read(path);
            }
            case PROJECT_PHOTO -> projectPhotos.findById(ref).map(ProjectUpdatePhoto::getContent).orElseThrow(StorageService::gone);
            case NEWS_PICTURE -> newsImages.findById(ref).map(NewsImage::getContent).orElseThrow(StorageService::gone);
            case BRAND_LOGO -> brands.logo(ref).getContent();
            case UPI_QR -> brands.upiQr(ref).getContent();
            case OFFER_LETTER -> offerFiles.findById(ref).map(OfferLetterFile::getContent).orElseThrow(StorageService::gone);
        };
        return new FileContent(bytes, f.getContentType() == null ? "application/octet-stream" : f.getContentType(), f.getName());
    }

    private static ResourceNotFoundException gone() {
        return new ResourceNotFoundException("This file is no longer there");
    }

    private StoredFile allowed(AuthUser me, Long id) {
        StoredFile f = files.findById(id).orElseThrow(() -> new ResourceNotFoundException("Stored file not found: " + id));
        if (f.getBrand() != null) {
            access.check(me, f.getBrand());
        } else if (!access.allBrands(me)) {
            throw ApiException.forbidden("This file is not of your brand");
        }
        return f;
    }

    // ---- deleting ----

    /** Each file on its own: one that cannot go does not stop the others. A file on the disk is removed once the change is saved. */
    public DeleteResult delete(AuthUser me, List<Long> ids) {
        int deleted = 0;
        long freed = 0;
        List<NotDeleted> notDeleted = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            String name = null;
            try {
                StoredFile f = transaction.execute(s -> allowed(me, id));
                name = f.getName();
                String diskFile = transaction.execute(s -> remove(me, f));
                if (diskFile != null) {
                    if (f.getCategory() == StoredFileCategory.CHECK_IN_PHOTO) {
                        checkInPhotos.deleteQuietly(diskFile);
                    } else {
                        documentDisk.deleteQuietly(diskFile);
                    }
                }
                deleted++;
                freed += f.getSizeBytes();
                log.info("Stored file deleted by employee {}: {} {} \"{}\" of {} ({} bytes)", me.id(), f.getCategory(), f.getRefId(), f.getName(), f.getOwner(), f.getSizeBytes());
            } catch (ApiException | ResourceNotFoundException e) {
                notDeleted.add(new NotDeleted(id, name, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Could not delete stored file {}: {}", id, e.getMessage());
                notDeleted.add(new NotDeleted(id, name, "It could not be deleted; please try again"));
            }
        }
        remembered.set(null);
        return new DeleteResult(deleted, freed, notDeleted);
    }

    /**
     * Removes the file and what says it is there; the record it belongs to stays, except an offer letter, which goes
     * whole and only with its own tick. Says which disk file to remove afterwards, if any.
     */
    private String remove(AuthUser me, StoredFile f) {
        Long ref = f.getRefId();
        String diskFile = null;
        switch (f.getCategory()) {
            case EMPLOYEE_DOCUMENT -> {
                EmployeeDocument d = documents.findById(ref).orElse(null);
                documentFiles.remove(ref);
                if (d != null) {
                    diskFile = d.getStoredPath();
                    documents.delete(d);
                }
            }
            case PROFILE_PHOTO -> {
                employeePhotos.deleteById(ref);
                employees.findById(ref).ifPresent(e -> e.setPhotoUpdatedAt(null));
            }
            case AGENT_PHOTO -> {
                agentPhotos.deleteById(ref);
                agents.findById(ref).ifPresent(a -> a.setPhotoUpdatedAt(null));
            }
            case CHECK_IN_PHOTO -> {
                Attendance a = attendance.findById(ref).orElse(null);
                if (a != null) {
                    diskFile = a.getPhotoPath();
                    a.setPhotoPath(null);
                    a.setPhotoBytes(0);
                }
            }
            case PROJECT_PHOTO -> projectPhotos.findFull(ref).ifPresent(ph -> ph.getUpdate().getPhotos().removeIf(x -> x.getId().equals(ref)));
            case NEWS_PICTURE -> {
                newsImages.deleteById(ref);
                news.findById(ref).ifPresent(n -> n.setImageUpdatedAt(null));
            }
            case BRAND_LOGO -> brands.deleteLogo(ref);
            case UPI_QR -> brands.deleteUpiQr(ref);
            case OFFER_LETTER -> offerLetters.delete(me, ref);
        }
        files.forget(f.getCategory(), ref);
        return diskFile;
    }
}
