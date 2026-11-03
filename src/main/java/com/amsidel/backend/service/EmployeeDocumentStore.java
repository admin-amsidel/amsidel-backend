/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Employee documents on the server's disk, under app.documents.dir, at documents/{employeeId}/{TYPE}-{millis}.{ext}.
 * Like the check-in photos they are only ever served through the authenticated API, never as static files, and a
 * stored path can never point outside the folder.
 */
@Slf4j
@Component
public class EmployeeDocumentStore {

    private final Path root;

    public EmployeeDocumentStore(@Value("${app.documents.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public void save(String relative, byte[] bytes) {
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save document " + relative, e);
        }
    }

    public byte[] read(String relative) {
        try {
            return Files.readAllBytes(resolve(relative));
        } catch (NoSuchFileException e) {
            throw new ResourceNotFoundException("The file of this document is missing; please upload it again");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document " + relative, e);
        }
    }

    public void deleteQuietly(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException e) {
            log.warn("Could not delete document {}: {}", relative, e.getMessage());
        }
    }

    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Document path outside the documents folder: " + relative);
        }
        return path;
    }
}
