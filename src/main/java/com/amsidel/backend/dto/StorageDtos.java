/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** How much is stored, and the list of stored files. */
public final class StorageDtos {

    private StorageDtos() {
    }

    /** available false: this database cannot say how large it is. otherBytes: everything that is not a stored file. */
    public record Database(boolean available, long usedBytes, long maxBytes, int percent, long fileBytes, long otherBytes, Instant measuredAt) {
    }

    /** The folder of check-in photos; total 0 when it cannot be read. */
    public record Disk(long fileBytes, long files, long totalBytes, long usableBytes) {
    }

    /** One line of a breakdown: a category, a file type or a brand. */
    public record Group(String key, String label, long files, long bytes) {
    }

    public record TableSize(String name, long bytes, long rows) {
    }

    public record BrandOption(Long id, String code, String name) {
    }

    public record Option(String value, String label) {
    }

    /** database, disk and tables are for those who see every brand; the rest see their brand's files. */
    public record Summary(Database database, Disk disk, long files, long bytes, long filesThisMonth, long bytesThisMonth, List<Group> byCategory, List<Group> byFileType,
                          List<Group> byBrand, List<TableSize> tables, List<BrandOption> brands, List<Option> categories, boolean canDelete,
                          /** When these figures were measured (the scheduled job, or Refresh); and when the job runs next. */
                          Instant updatedAt, Instant nextUpdateAt) {
    }

    public record FileRow(Long id, String category, String categoryLabel, String fileType, String name, String owner, Long brandId, String brandCode, long sizeBytes,
                          String location, String uploadedByName, Instant uploadedAt, boolean deletable) {
    }

    /** total, totalBytes: everything the filters match, over all pages. */
    public record FilePage(List<FileRow> rows, long total, long totalBytes) {
    }

    public record FileContent(byte[] bytes, String contentType, String name) {
    }

    public record DeleteBody(@NotEmpty(message = "Choose the files to delete") @Size(max = 200, message = "Delete at most 200 files at once") List<Long> ids) {
    }

    public record NotDeleted(Long id, String name, String reason) {
    }

    public record DeleteResult(int deleted, long freedBytes, List<NotDeleted> notDeleted) {
    }
}
