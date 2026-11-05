/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.DocumentType;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.StoredFile;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Keeps the register of stored files true to what is really there, running in the background at each start and when
 * someone looks at the figures. It adds files that were saved before uploads were recorded, and removes lines of files
 * gone another way (a project deleted with its photos, a check-in photo lost from the disk). It never touches a file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoredFileSync {

    private static final String NO_BY = "cast(null as bigint) by_id";
    private static final String NO_PATH = "cast(null as varchar(300)) disk_path";

    /** What is really there: the query's columns are read by name below; ids is every row that has a file. */
    private record Source(StoredFileCategory category, String select, String refColumn, String where, String ids) {
    }

    private static final List<Source> SOURCES = List.of(
            new Source(StoredFileCategory.EMPLOYEE_DOCUMENT,
                    "select d.id ref_id, e.brand_id, d.file_name, e.name owner_name, d.doc_type detail, d.content_type, d.size_bytes, d.uploaded_at at_time, d.uploaded_by by_id, d.stored_path disk_path"
                            + " from employee_documents d join employees e on e.id = d.employee_id", "d.id", null, "select id from employee_documents"),
            new Source(StoredFileCategory.PROFILE_PHOTO,
                    "select p.employee_id ref_id, e.brand_id, 'Profile photo' file_name, e.name owner_name, cast(null as varchar(30)) detail, p.content_type, p.size_bytes, p.updated_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from employee_photos p join employees e on e.id = p.employee_id", "p.employee_id", null, "select employee_id from employee_photos"),
            new Source(StoredFileCategory.AGENT_PHOTO,
                    "select p.agent_id ref_id, a.brand_id, 'Agent photo' file_name, a.name owner_name, cast(null as varchar(30)) detail, p.content_type, octet_length(p.content) size_bytes, a.photo_updated_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from agent_photos p join agents a on a.id = p.agent_id", "p.agent_id", null, "select agent_id from agent_photos"),
            new Source(StoredFileCategory.CHECK_IN_PHOTO,
                    "select a.id ref_id, e.brand_id, 'Check-in photo' file_name, e.name owner_name, cast(a.work_date as varchar(30)) detail, 'image/jpeg' content_type, a.photo_bytes size_bytes, a.check_in_at at_time, a.employee_id by_id, a.photo_path disk_path"
                            + " from attendance a join employees e on e.id = a.employee_id", "a.id", "a.photo_path is not null", "select id from attendance where photo_path is not null"),
            new Source(StoredFileCategory.PROJECT_PHOTO,
                    "select p.id ref_id, pr.brand_id, 'Project status photo' file_name, pr.code owner_name, u.title detail, p.content_type, octet_length(p.content) size_bytes, p.created_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from project_update_photos p join project_updates u on u.id = p.update_id join projects pr on pr.id = u.project_id", "p.id", null, "select id from project_update_photos"),
            new Source(StoredFileCategory.NEWS_PICTURE,
                    "select i.news_id ref_id, n.brand_id, 'News picture' file_name, n.title owner_name, cast(null as varchar(30)) detail, i.content_type, octet_length(i.content) size_bytes, i.updated_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from news_images i join news_posts n on n.id = i.news_id", "i.news_id", null, "select news_id from news_images"),
            new Source(StoredFileCategory.BRAND_LOGO,
                    "select l.brand_id ref_id, l.brand_id, 'Brand logo' file_name, b.name owner_name, cast(null as varchar(30)) detail, l.content_type, octet_length(l.content) size_bytes, l.updated_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from brand_logos l join brands b on b.id = l.brand_id", "l.brand_id", null, "select brand_id from brand_logos"),
            new Source(StoredFileCategory.UPI_QR,
                    "select q.brand_id ref_id, q.brand_id, 'UPI QR code' file_name, b.name owner_name, cast(null as varchar(30)) detail, q.content_type, octet_length(q.content) size_bytes, q.updated_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from brand_upi_qrs q join brands b on b.id = q.brand_id", "q.brand_id", null, "select brand_id from brand_upi_qrs"),
            new Source(StoredFileCategory.OFFER_LETTER,
                    "select f.offer_letter_id ref_id, o.brand_id, coalesce(o.letter_number, 'Offer letter') file_name, o.candidate_name owner_name, cast(null as varchar(30)) detail, 'application/pdf' content_type, octet_length(f.content) size_bytes, o.issued_at at_time, " + NO_BY + ", " + NO_PATH
                            + " from offer_letter_files f join offer_letters o on o.id = f.offer_letter_id", "f.offer_letter_id", null, "select offer_letter_id from offer_letter_files"));

    private final JdbcTemplate jdbc;
    private final StoredFileRepository files;
    private final BrandRepository brands;
    private final EmployeeRepository employees;
    private final AttendancePhotoStore checkInPhotos;
    private final TransactionTemplate transaction;

    public record Result(int added, int removed) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        Thread worker = new Thread(() -> {
            try {
                Result r = sync();
                if (r.added() > 0 || r.removed() > 0) {
                    log.info("Register of stored files: {} added, {} removed", r.added(), r.removed());
                }
            } catch (RuntimeException e) {
                log.warn("Could not bring the register of stored files up to date; will try again later: {}", e.getMessage());
            }
        }, "stored-file-sync");
        worker.setDaemon(true);
        worker.start();
    }

    public Result sync() {
        int added = 0;
        int removed = 0;
        for (Source s : SOURCES) {
            try {
                removed += jdbc.update("delete from stored_files where category = ? and ref_id not in (" + s.ids() + ")", s.category().name());
                added += addMissing(s);
            } catch (RuntimeException e) {
                log.warn("Could not bring {} up to date in the register of stored files: {}", s.category(), e.getMessage());
            }
        }
        removed += forgetLostCheckInPhotos();
        // documents that moved from the disk to the database since
        jdbc.update("update stored_files set location = 'DATABASE' where category = 'EMPLOYEE_DOCUMENT' and location = 'DISK'"
                + " and ref_id in (select id from employee_documents where stored_path is null)");
        return new Result(added, removed);
    }

    private int addMissing(Source s) {
        String sql = s.select() + " where " + (s.where() == null ? "" : s.where() + " and ")
                + "not exists (select 1 from stored_files s where s.category = ? and s.ref_id = " + s.refColumn() + ")";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, s.category().name());
        if (rows.isEmpty()) {
            return 0;
        }
        Integer added = transaction.execute(t -> {
            int n = 0;
            for (Map<String, Object> row : rows) {
                String path = (String) row.get("disk_path");
                // a check-in photo no longer on the disk takes no space
                if (s.category() == StoredFileCategory.CHECK_IN_PHOTO && (path == null || !checkInPhotos.exists(path))) {
                    continue;
                }
                files.save(line(s.category(), row, path));
                n++;
            }
            return n;
        });
        return added == null ? 0 : added;
    }

    private StoredFile line(StoredFileCategory category, Map<String, Object> row, String path) {
        StoredFile f = new StoredFile();
        f.setCategory(category);
        f.setRefId(((Number) row.get("ref_id")).longValue());
        Number brand = (Number) row.get("brand_id");
        f.setBrand(brand == null ? null : brands.getReferenceById(brand.longValue()));
        String contentType = (String) row.get("content_type");
        f.setName(StoredFiles.cleanName((String) row.get("file_name"), category.getLabel()));
        f.setOwner(owner(category, (String) row.get("owner_name"), (String) row.get("detail")));
        f.setContentType(contentType);
        f.setFileType(StoredFiles.fileType(contentType));
        Number size = (Number) row.get("size_bytes");
        f.setSizeBytes(size == null ? 0 : size.longValue());
        f.setLocation(path != null ? StoredFile.Location.DISK : StoredFile.Location.DATABASE);
        Number by = (Number) row.get("by_id");
        Employee uploader = by == null ? null : employees.getReferenceById(by.longValue());
        f.setUploadedBy(uploader);
        Object at = row.get("at_time");
        f.setUploadedAt(at instanceof Timestamp t ? t.toInstant() : at instanceof java.time.OffsetDateTime o ? o.toInstant() : null);
        return f;
    }

    private static String owner(StoredFileCategory category, String name, String detail) {
        String who = name == null ? "—" : name;
        if (detail == null) {
            return who;
        }
        if (category == StoredFileCategory.EMPLOYEE_DOCUMENT) {
            try {
                return who + " · " + DocumentType.valueOf(detail).label();
            } catch (IllegalArgumentException e) {
                return who;
            }
        }
        String text = who + " · " + detail;
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** A disk that was wiped: the attendance rows remain, the files do not. */
    private int forgetLostCheckInPhotos() {
        int removed = 0;
        List<Map<String, Object>> rows = jdbc.queryForList("select s.id, a.photo_path from stored_files s join attendance a on a.id = s.ref_id where s.category = 'CHECK_IN_PHOTO'");
        for (Map<String, Object> row : rows) {
            if (!checkInPhotos.exists((String) row.get("photo_path"))) {
                removed += jdbc.update("delete from stored_files where id = ?", ((Number) row.get("id")).longValue());
            }
        }
        return removed;
    }
}
