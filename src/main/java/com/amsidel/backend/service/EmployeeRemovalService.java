/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeePhotoRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.NotificationClearRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.SignedInCache;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Deleting employees for good, for someone with the tick. Only someone with nothing to their name goes: the database refuses
 * to delete anyone that attendance, a work log, leave, pay, a task or any other record points at, and that history must stay,
 * so those are left as they are and can be disabled instead. Each person is on their own: one refused does not stop the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeRemovalService {

    public record DeleteBody(@NotEmpty(message = "Choose the employees to delete") @Size(max = 500, message = "Delete at most 500 employees at once") List<Long> ids) {
    }

    public record NotDeleted(Long id, String name, String reason) {
    }

    public record DeleteResult(int deleted, List<NotDeleted> notDeleted) {
    }

    private static final String HAS_RECORDS = "They have records in the portal, such as attendance, work logs, leave, pay or tasks, which must stay. Disable the account instead";

    private final EmployeeRepository employees;
    private final EmployeePhotoRepository photos;
    private final NotificationClearRepository notificationClears;
    private final MenuAccessService menuAccess;
    private final StoredFiles storedFiles;
    private final SignedInCache signedIn;
    private final TransactionTemplate transaction;

    public DeleteResult delete(AuthUser me, List<Long> ids) {
        int deleted = 0;
        List<NotDeleted> refused = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            String[] name = new String[1];
            try {
                transaction.executeWithoutResult(s -> name[0] = remove(me, id));
                deleted++;
                log.info("Employee {} deleted by employee {}", name[0], me.id());
            } catch (ApiException | ResourceNotFoundException e) {
                refused.add(new NotDeleted(id, name[0], e.getMessage()));
            } catch (DataIntegrityViolationException e) {
                refused.add(new NotDeleted(id, name[0], HAS_RECORDS));
            } catch (RuntimeException e) {
                log.warn("Could not delete employee {}: {}", id, e.getMessage());
                refused.add(new NotDeleted(id, name[0], "They could not be deleted; please try again"));
            }
        }
        if (deleted > 0) {
            // whoever they reported to may no longer lead a team
            signedIn.evictAll();
        }
        return new DeleteResult(deleted, refused);
    }

    /** Says who went. What is theirs alone goes with them; anything else that points at them makes the database refuse. */
    private String remove(AuthUser me, Long id) {
        Employee e = employees.findWithBrandById(id).orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
        String label = e.getEmpNumber() + " " + e.getName();
        if (e.getId().equals(me.id())) {
            throw ApiException.forbidden(label + ": you cannot delete your own account");
        }
        if (e.getRole() == Role.OWNER) {
            throw ApiException.forbidden(label + ": an owner cannot be deleted");
        }
        checkScope(me, e, label);
        if (employees.existsByReportsTo_IdAndActiveTrue(id) || employees.existsByApprover_IdAndActiveTrue(id)) {
            throw ApiException.conflict(label + ": people report to them or have them as their approver. Change that first");
        }
        menuAccess.save(AccessSubject.EMPLOYEE, String.valueOf(id), List.of());
        notificationClears.deleteAllForEmployee(id);
        if (photos.existsById(id)) {
            photos.deleteById(id);
        }
        storedFiles.forget(StoredFileCategory.PROFILE_PHOTO, id);
        employees.delete(e);
        try {
            employees.flush();
        } catch (DataIntegrityViolationException refused) {
            throw ApiException.conflict(label + ": " + HAS_RECORDS);
        }
        return label;
    }

    private static void checkScope(AuthUser me, Employee e, String label) {
        if (me.isOwner()) {
            return;
        }
        boolean staff = e.getRole() == Role.MANAGER || e.getRole() == Role.EMPLOYEE;
        boolean inBrand = me.brandId() == null || (e.getBrand() != null && me.brandId().equals(e.getBrand().getId()));
        if (!staff || !inBrand) {
            throw ApiException.forbidden(label + ": you are not allowed to delete this employee");
        }
    }
}
