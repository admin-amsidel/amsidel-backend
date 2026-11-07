/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** A list as it is on the screen: its title, column headings, and rows of text or numbers (null = empty cell). */
public record TableExportRequest(
        @NotBlank @Size(max = 100) String title,
        @NotEmpty @Size(max = 100) List<String> columns,
        @NotNull @Size(max = 100_000, message = "At most 100000 rows can be exported at once") List<List<Object>> rows) {
}
