/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.EmployeeDocumentDtos.*;
import com.amsidel.backend.entity.DocumentType;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.EmployeeDocument;
import com.amsidel.backend.entity.EmployeeDocumentFile;
import com.amsidel.backend.entity.StoredFile;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeDocumentFileRepository;
import com.amsidel.backend.repository.EmployeeDocumentRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An employee's documents (PAN card, Aadhaar card, bank statement / passbook, offer letter, passport size photo) and
 * how complete their profile is. Everyone looks after their own; owners and admins who manage a person (the same
 * rule as changing their record) see and change theirs. Nobody else sees them, not even with the employee list.
 * Each file is a PDF, JPEG or PNG (the passport size photo an image), judged by its first bytes, up to app.uploads.max-size.document.
 * <p>
 * A person has a monthly number of uploads: their own limit if their record has one, otherwise the company default.
 * Every file taken counts for the person it belongs to, whoever uploads it. Replacing counts, as it is a new file;
 * deleting gives nothing back, or delete and upload again would get round the limit. A refused file does not count.
 */
@Service
public class EmployeeDocumentService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeDocumentRepository documentRepository;
    private final EmployeeDocumentFileRepository fileRepository;
    private final EmployeeDocumentStore store;
    private final EmployeeService employeeService;
    private final UploadLimits limits;
    private final StoredFiles storedFiles;
    private final int defaultMonthlyLimit;
    private final ZoneId zone;

    public EmployeeDocumentService(EmployeeRepository employeeRepository, EmployeeDocumentRepository documentRepository,
                                   EmployeeDocumentFileRepository fileRepository, EmployeeDocumentStore store,
                                   EmployeeService employeeService, UploadLimits limits, StoredFiles storedFiles, @Value("${app.documents.monthly-upload-limit}") int defaultMonthlyLimit,
                                   @Value("${app.leave.zone}") ZoneId zone) {
        this.employeeRepository = employeeRepository;
        this.documentRepository = documentRepository;
        this.fileRepository = fileRepository;
        this.store = store;
        this.employeeService = employeeService;
        this.limits = limits;
        this.storedFiles = storedFiles;
        this.defaultMonthlyLimit = defaultMonthlyLimit;
        this.zone = zone;
    }

    /** employeeId null = my own. */
    @Transactional(readOnly = true)
    public List<DocumentView> list(AuthUser me, Long employeeId) {
        Employee e = target(me, employeeId);
        Map<DocumentType, EmployeeDocument> have = byType(e.getId());
        return Arrays.stream(DocumentType.values()).map(t -> view(t, have.get(t))).toList();
    }

    @Transactional
    public DocumentView upload(AuthUser me, Long employeeId, DocumentType type, MultipartFile file) {
        Employee e = target(me, employeeId);
        byte[] bytes = read(file);
        String contentType = detectType(bytes);
        if (contentType == null || (type.imageOnly() && !contentType.startsWith("image/"))) {
            throw ApiException.badRequest(type.imageOnly()
                    ? "The " + type.label().toLowerCase() + " must be a JPEG or PNG image"
                    : "The " + type.label().toLowerCase() + " must be a PDF, JPEG or PNG file");
        }
        countUpload(me, e);
        EmployeeDocument d = documentRepository.findByEmployeeIdAndType(e.getId(), type).orElseGet(EmployeeDocument::new);
        String onDisk = d.getStoredPath();
        d.setEmployee(e);
        d.setType(type);
        d.setFileName(fileName(file.getOriginalFilename(), type, contentType));
        d.setContentType(contentType);
        d.setSizeBytes(bytes.length);
        d.setStoredPath(null);
        d.setUploadedAt(Instant.now());
        d.setUploadedBy(employeeRepository.getReferenceById(me.id()));
        documentRepository.saveAndFlush(d);
        fileRepository.saveAndFlush(new EmployeeDocumentFile(d.getId(), bytes));
        storedFiles.record(StoredFileCategory.EMPLOYEE_DOCUMENT, d.getId(), e.getBrand(), d.getFileName(), type.label(), e.getName() + " · " + type.label(),
                contentType, bytes.length, StoredFile.Location.DATABASE);
        if (onDisk != null) {
            store.deleteQuietly(onDisk);
        }
        return view(type, documentRepository.findByEmployeeIdAndType(e.getId(), type).orElse(d));
    }

    /** Uploads this month: employeeId null = my own. */
    @Transactional(readOnly = true)
    public UploadQuota quota(AuthUser me, Long employeeId) {
        Employee e = target(me, employeeId);
        YearMonth month = YearMonth.now(zone);
        int limit = limitOf(e);
        int used = month.toString().equals(e.getDocumentUploadsMonth()) && e.getDocumentUploadsUsed() != null ? e.getDocumentUploadsUsed() : 0;
        return new UploadQuota(limit, used, Math.max(0, limit - used), month.plusMonths(1).atDay(1));
    }

    private void countUpload(AuthUser me, Employee e) {
        YearMonth month = YearMonth.now(zone);
        int limit = limitOf(e);
        if (limit > 0 && employeeRepository.countDocumentUpload(e.getId(), month.toString(), limit) > 0) {
            return;
        }
        String from = month.plusMonths(1).atDay(1).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH));
        boolean mine = e.getId().equals(me.id());
        String message = limit == 0
                ? (mine ? "Your account cannot upload documents. Ask your admin to change your monthly limit."
                        : e.getName() + " (" + e.getUserId() + ") has a monthly document limit of 0. Change it on their record to upload.")
                : mine
                ? "You have used all " + limit + " document uploads for this month. You can upload again from " + from + ", or ask your admin to raise your limit."
                : e.getName() + " (" + e.getUserId() + ") has used all " + limit + " document uploads for this month. Raise the limit on their record, or upload from " + from + ".";
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    private int limitOf(Employee e) {
        return e.getDocumentUploadLimit() != null ? e.getDocumentUploadLimit() : defaultMonthlyLimit;
    }

    @Transactional(readOnly = true)
    public DocumentFile file(AuthUser me, Long employeeId, DocumentType type) {
        EmployeeDocument d = find(target(me, employeeId), type);
        byte[] bytes = d.getStoredPath() != null ? store.read(d.getStoredPath()) : fileRepository.findById(d.getId())
                .map(EmployeeDocumentFile::getContent)
                .orElseThrow(() -> new ResourceNotFoundException("The file of this document is missing; please upload it again"));
        return new DocumentFile(bytes, d.getContentType(), d.getFileName());
    }

    @Transactional
    public void delete(AuthUser me, Long employeeId, DocumentType type) {
        EmployeeDocument d = find(target(me, employeeId), type);
        fileRepository.remove(d.getId());
        storedFiles.forget(StoredFileCategory.EMPLOYEE_DOCUMENT, d.getId());
        documentRepository.delete(d);
        documentRepository.flush();
        if (d.getStoredPath() != null) {
            store.deleteQuietly(d.getStoredPath());
        }
    }

    /** What a complete profile has: personal details, bank &amp; tax details and the documents. */
    @Transactional(readOnly = true)
    public ProfileStatus status(AuthUser me, Long employeeId) {
        Employee e = target(me, employeeId);
        List<ProfileItem> items = items(e, byType(e.getId()).keySet());
        int done = (int) items.stream().filter(ProfileItem::done).count();
        return new ProfileStatus(done * 100 / items.size(), done, items.size(), items);
    }

    /** How complete each person's profile is, read for the whole team at once (the dashboard). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> percents(List<Employee> team) {
        if (team.isEmpty()) {
            return Map.of();
        }
        Map<Long, java.util.Set<DocumentType>> have = new java.util.HashMap<>();
        for (Object[] r : documentRepository.typesOfPeople(team.stream().map(Employee::getId).toList())) {
            have.computeIfAbsent((Long) r[0], k -> java.util.EnumSet.noneOf(DocumentType.class)).add((DocumentType) r[1]);
        }
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (Employee e : team) {
            List<ProfileItem> items = items(e, have.getOrDefault(e.getId(), java.util.Set.of()));
            out.put(e.getId(), (int) items.stream().filter(ProfileItem::done).count() * 100 / items.size());
        }
        return out;
    }

    private static List<ProfileItem> items(Employee e, java.util.Set<DocumentType> have) {
        List<ProfileItem> items = new ArrayList<>();
        String personal = "Personal details";
        items.add(new ProfileItem("photo", "Photo", personal, e.getPhotoUpdatedAt() != null || filled(e.getPhotoPath())));
        items.add(new ProfileItem("phone", "Phone", personal, filled(e.getPhone())));
        items.add(new ProfileItem("dob", "Date of birth", personal, e.getDob() != null));
        items.add(new ProfileItem("bloodGroup", "Blood group", personal, filled(e.getBloodGroup())));
        items.add(new ProfileItem("personalEmail", "Personal email", personal, filled(e.getPersonalEmail())));
        items.add(new ProfileItem("emergencyContact", "Emergency contact", personal, filled(e.getEmergencyContact())));
        items.add(new ProfileItem("address", "Address", personal, filled(e.getAddress())));
        String bank = "Bank & tax";
        items.add(new ProfileItem("pan", "PAN", bank, filled(e.getPanNumber())));
        items.add(new ProfileItem("bankAccount", "Bank account and IFSC", bank, filled(e.getBankAccountNumber()) && filled(e.getIfscCode())));
        for (DocumentType t : DocumentType.values()) {
            items.add(new ProfileItem("doc:" + t.name(), t.label(), "Documents", have.contains(t)));
        }
        return items;
    }

    /** Me, or someone I manage (owners and admins); 403 / 404 otherwise. */
    private Employee target(AuthUser me, Long employeeId) {
        if (employeeId == null || employeeId.equals(me.id())) {
            return employeeRepository.findWithBrandById(me.id())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id()));
        }
        return employeeService.findManageable(me, employeeId);
    }

    private EmployeeDocument find(Employee e, DocumentType type) {
        return documentRepository.findByEmployeeIdAndType(e.getId(), type)
                .orElseThrow(() -> new ResourceNotFoundException("No " + type.label().toLowerCase() + " uploaded"));
    }

    private Map<DocumentType, EmployeeDocument> byType(Long employeeId) {
        return documentRepository.forEmployee(employeeId).stream()
                .collect(Collectors.toMap(EmployeeDocument::getType, Function.identity()));
    }

    private static DocumentView view(DocumentType t, EmployeeDocument d) {
        if (d == null) {
            return new DocumentView(t.name(), t.label(), t.imageOnly(), false, null, null, null, null, null);
        }
        return new DocumentView(t.name(), t.label(), t.imageOnly(), true, d.getFileName(), d.getContentType(), d.getSizeBytes(),
                d.getUploadedAt(), d.getUploadedBy() == null ? null : d.getUploadedBy().getName());
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload");
        }
        UploadLimits.check(file.getSize(), limits.getDocument(), "Each file");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read; please try again");
        }
    }

    /** Judged by the file's first bytes, not by its name or the type the browser claims. */
    static String detectType(byte[] b) {
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (AttendancePhotoStore.isJpeg(b)) {
            return "image/jpeg";
        }
        if (AttendancePhotoStore.isPng(b)) {
            return "image/png";
        }
        return null;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            default -> "jpg";
        };
    }

    /** The uploaded name without folders or odd characters, with the right extension; a plain name when there is none. */
    private static String fileName(String original, DocumentType type, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9 ._()-]", "_").strip();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isBlank()) {
            base = type.label().replaceAll("[^A-Za-z0-9]+", "-");
        }
        if (base.length() > 150) {
            base = base.substring(0, 150);
        }
        return base + "." + extension(contentType);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
