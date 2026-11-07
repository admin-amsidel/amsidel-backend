/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This API gives an Excel file: it also needs the Export to Excel tick (Menus.EXPORT_EXCEL), on top of any
 * {@link Checkpoint} for the page. Checked by CheckpointInterceptor after sign-in.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportCheck {
}
