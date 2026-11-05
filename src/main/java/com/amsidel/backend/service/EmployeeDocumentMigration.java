/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.EmployeeDocument;
import com.amsidel.backend.entity.EmployeeDocumentFile;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeDocumentFileRepository;
import com.amsidel.backend.repository.EmployeeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Documents used to be files on the server's disk. Each start moves those still there into the database, one at a time
 * and in the background, so the portal is usable meanwhile and a large move cannot delay the start. A file leaves the
 * disk only once it is safely saved, and one that cannot be found is left as it is and tried again at the next start.
 * Nothing happens once all have moved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeDocumentMigration {

    private final EmployeeDocumentRepository documents;
    private final EmployeeDocumentFileRepository files;
    private final EmployeeDocumentStore disk;
    private final TransactionTemplate transaction;

    public record Result(int moved, int missing, int failed) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        Thread mover = new Thread(() -> {
            try {
                moveAll();
            } catch (RuntimeException e) {
                log.warn("Could not move employee documents from the disk to the database; will try again at the next start: {}", e.getMessage());
            }
        }, "document-move");
        mover.setDaemon(true);
        mover.start();
    }

    public Result moveAll() {
        List<Long> ids = documents.stillOnDisk();
        if (ids.isEmpty()) {
            return new Result(0, 0, 0);
        }
        log.info("Moving {} employee document(s) from the disk to the database", ids.size());
        int moved = 0;
        int missing = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                String path = transaction.execute(s -> moveOne(id));
                if (path != null) {
                    disk.deleteQuietly(path);
                }
                moved++;
            } catch (ResourceNotFoundException e) {
                missing++;
                log.warn("The file of employee document {} is not on the disk, so it cannot be moved; it must be uploaded again", id);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not move employee document {}; will try again at the next start: {}", id, e.getMessage());
            }
        }
        log.info("Employee documents to the database: {} moved, {} missing from the disk, {} failed", moved, missing, failed);
        return new Result(moved, missing, failed);
    }

    /** The path the file was at, to remove once this is committed; null if it has moved already. */
    private String moveOne(Long id) {
        EmployeeDocument d = documents.findById(id).orElse(null);
        if (d == null || d.getStoredPath() == null) {
            return null;
        }
        String path = d.getStoredPath();
        files.save(new EmployeeDocumentFile(id, disk.read(path)));
        d.setStoredPath(null);
        return path;
    }
}
