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
 * How much the portal stores and the list of stored files, with deleting them to free space. The figures are measured by
 * a scheduled job three times a day (and by Refresh) and kept in storage_snapshots; the Storage page and the Overview read
 * the latest of them, so opening them measures nothing however big the data grows. The list of files is read as it is now.
 */
@Slf4j
@Service
public class StorageService {

    /** Snapshots kept to compare; older ones go. */
    private static final Duration KEEP = Duration.ofDays(30);
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
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

    /** One group of stored files in a snapshot. */
    record FileGroup(String category, String fileType, Long brandId, String brandCode, String location, long files, long bytes, long monthFiles, long monthBytes) {
    }

    /** A snapshot read back, kept while it is the latest. */
    private record Read(Long id, StorageSnapshot snapshot, List<TableSize> tables, List<FileGroup> files) {
    }

    private final AtomicReference<Read> latest = new AtomicReference<>();
    private StorageSnapshotRepository snapshots;
    private SchedulerSwitch schedulers;

    @org.springframework.beans.factory.annotation.Autowired
    void snapshotsFrom(StorageSnapshotRepository snapshots, SchedulerSwitch schedulers) {
        this.snapshots = snapshots;
        this.schedulers = schedulers;
    }

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

    /**
     * From the latest snapshot (made at once when there is none yet); Refresh measures again first. Those who see one brand
     * get that brand's files; the database, disk and tables only those who see every brand.
     */
    public Summary summary(AuthUser me, boolean refresh) {
        boolean all = access.allBrands(me);
        Long scope = access.scope(me, null);
        Read r = refresh ? read(snapshot()) : latestRead();
        StorageSnapshot snap = r.snapshot();

        List<FileGroup> mine = r.files().stream().filter(g -> scope == null || scope.equals(g.brandId())).toList();
        long inDatabase = r.files().stream().filter(g -> StoredFile.Location.DATABASE.name().equals(g.location())).mapToLong(FileGroup::bytes).sum();
        long diskFiles = r.files().stream().filter(g -> StoredFile.Location.DISK.name().equals(g.location())).mapToLong(FileGroup::files).sum();
        long diskBytes = r.files().stream().filter(g -> StoredFile.Location.DISK.name().equals(g.location())).mapToLong(FileGroup::bytes).sum();

        List<Group> byCategory = grouped(mine, FileGroup::category, key -> categoryLabel(key));
        List<Group> byType = grouped(mine, FileGroup::fileType, key -> key);
        List<Group> byBrand = grouped(mine, g -> g.brandCode() == null ? "" : g.brandCode(), key -> key.isEmpty() ? "Group level" : key);
        long count = mine.stream().mapToLong(FileGroup::files).sum();
        long bytes = mine.stream().mapToLong(FileGroup::bytes).sum();
        // added this month: as at the measuring, when it was this month
        boolean sameMonth = java.time.YearMonth.from(snap.getMeasuredAt().atZone(zone)).equals(java.time.YearMonth.now(zone));
        long monthFiles = sameMonth ? mine.stream().mapToLong(FileGroup::monthFiles).sum() : 0;
        long monthBytes = sameMonth ? mine.stream().mapToLong(FileGroup::monthBytes).sum() : 0;

        long max = snap.getDatabaseMaxBytes();
        int percent = !snap.isDatabaseAvailable() || max <= 0 ? 0 : (int) Math.min(100, snap.getDatabaseBytes() * 100 / max);
        Database database = all ? new Database(snap.isDatabaseAvailable(), snap.getDatabaseBytes(), max, percent, inDatabase,
                snap.isDatabaseAvailable() ? Math.max(0, snap.getDatabaseBytes() - inDatabase) : 0, snap.getMeasuredAt()) : null;
        return new Summary(database, all ? new Disk(diskBytes, diskFiles, snap.getDiskTotalBytes(), snap.getDiskUsableBytes()) : null, count, bytes,
                monthFiles, monthBytes, byCategory, byType, byBrand, all ? r.tables() : List.of(),
                access.brands(me).stream().map(b -> new BrandOption(b.getId(), b.getCode(), b.getName())).toList(),
                Arrays.stream(StoredFileCategory.values()).map(c -> new Option(c.name(), c.getLabel())).toList(),
                menuAccess.can(me, Menus.STORAGE_DELETE), snap.getMeasuredAt(), nextRun());
    }

    /**
     * The scheduled job (three times a day by default; its cron and on/off are on the Configuration page) and Refresh:
     * the register brought up to date, then everything measured and kept as a new snapshot. Snapshots older than 30 days go.
     */
    public StorageSnapshot snapshot() {
        sync.sync();
        Instant now = Instant.now();
        StorageSnapshot s = new StorageSnapshot();
        s.setMeasuredAt(now);
        s.setDatabaseMaxBytes(maxDatabase.toBytes());
        List<TableSize> tables = List.of();
        try {
            if (!postgres()) {
                throw new IllegalStateException("not PostgreSQL");
            }
            Long used = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
            s.setDatabaseBytes(used == null ? 0 : used);
            s.setDatabaseAvailable(true);
            tables = jdbc.query("select relname, pg_total_relation_size(relid) as bytes, n_live_tup from pg_stat_user_tables order by 2 desc limit 10",
                    (rs, i) -> new TableSize(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        } catch (RuntimeException e) {
            log.debug("The database's size is not available: {}", e.getMessage());
        }
        long[] disk = diskSpace();
        s.setDiskTotalBytes(disk[0]);
        s.setDiskUsableBytes(disk[1]);
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<FileGroup> groups = files.breakdown(monthStart).stream()
                .map(r -> new FileGroup(r[0].toString(), r[1].toString(), r[2] == null ? null : ((Number) r[2]).longValue(), r[3] == null ? null : r[3].toString(),
                        r[4].toString(), ((Number) r[5]).longValue(), ((Number) r[6]).longValue(), r[7] == null ? 0 : ((Number) r[7]).longValue(),
                        r[8] == null ? 0 : ((Number) r[8]).longValue()))
                .toList();
        s.setTables(JSON.writeValueAsString(tables));
        s.setFiles(JSON.writeValueAsString(groups));
        StorageSnapshot saved = transaction.execute(t -> {
            StorageSnapshot x = snapshots.save(s);
            int gone = snapshots.deleteOlderThan(now.minus(KEEP));
            if (gone > 0) {
                log.debug("Removed {} old storage snapshot(s)", gone);
            }
            return x;
        });
        latest.set(new Read(saved.getId(), saved, tables, groups));
        log.info("Storage measured: database {} bytes, {} file group(s)", s.getDatabaseBytes(), groups.size());
        return saved;
    }

    /** For the scheduled job: only while it is switched on; a failure is written to the log, the next run tries again. */
    public void scheduledSnapshot() {
        if (schedulers.allowed(SchedulerSwitch.Job.STORAGE)) {
            snapshot();
        }
    }

    private Read latestRead() {
        StorageSnapshot newest = snapshots.findTopByOrderByMeasuredAtDescIdDesc().orElse(null);
        if (newest == null) {
            return read(snapshot());
        }
        Read r = latest.get();
        return r != null && r.id().equals(newest.getId()) ? r : read(newest);
    }

    private Read read(StorageSnapshot s) {
        Read r = latest.get();
        if (r != null && r.id().equals(s.getId())) {
            return r;
        }
        List<TableSize> tables = List.of(JSON.readValue(s.getTables(), TableSize[].class));
        List<FileGroup> groups = List.of(JSON.readValue(s.getFiles(), FileGroup[].class));
        Read fresh = new Read(s.getId(), s, tables, groups);
        latest.set(fresh);
        return fresh;
    }

    /** When the job runs next, by its cron (null when it is stopped or its cron cannot be read). */
    private Instant nextRun() {
        try {
            if (!schedulers.allowed(SchedulerSwitch.Job.STORAGE)) {
                return null;
            }
            java.time.ZonedDateTime next = org.springframework.scheduling.support.CronExpression.parse(schedulers.cron(SchedulerSwitch.Job.STORAGE))
                    .next(java.time.ZonedDateTime.now(schedulers.zone()));
            return next == null ? null : next.toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Group> grouped(List<FileGroup> rows, java.util.function.Function<FileGroup, String> key,
                                       java.util.function.Function<String, String> label) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        for (FileGroup g : rows) {
            long[] v = sums.computeIfAbsent(key.apply(g), k -> new long[2]);
            v[0] += g.files();
            v[1] += g.bytes();
        }
        return sums.entrySet().stream().map(e -> new Group(e.getKey(), label.apply(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(Group::bytes).reversed()).toList();
    }

    private static String categoryLabel(String name) {
        try {
            return StoredFileCategory.valueOf(name).getLabel();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    /** The disk that holds the check-in photos and documents: [total, usable], 0 when it cannot be read. */
    private long[] diskSpace() {
        try {
            Path existing = checkInFolder;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                FileStore store = Files.getFileStore(existing);
                return new long[]{store.getTotalSpace(), store.getUsableSpace()};
            }
        } catch (IOException e) {
            log.debug("The disk's size is not available: {}", e.getMessage());
        }
        return new long[]{0, 0};
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
        if (deleted > 0) {
            try {
                snapshot();
            } catch (RuntimeException e) {
                log.warn("Storage could not be measured after deleting files: {}", e.getMessage());
            }
        }
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
