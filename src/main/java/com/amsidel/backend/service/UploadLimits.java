/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * How large each kind of picture or document may be: app.uploads.max-size.* in application.properties. The web app asks
 * for the same figures, so its checks and hints always agree with these.
 */
@Slf4j
@Getter
@Component
public class UploadLimits {

    private final DataSize profilePhoto;
    private final DataSize agentPhoto;
    private final DataSize checkInPhoto;
    private final DataSize brandLogo;
    private final DataSize upiQr;
    private final DataSize newsPicture;
    private final DataSize projectPhoto;
    private final DataSize document;

    /** In bytes, for the web app. */
    public record View(long profilePhoto, long agentPhoto, long checkInPhoto, long brandLogo, long upiQr, long newsPicture, long projectPhoto, long document) {
    }

    public UploadLimits(@Value("${app.uploads.max-size.profile-photo}") DataSize profilePhoto,
                        @Value("${app.uploads.max-size.agent-photo}") DataSize agentPhoto,
                        @Value("${app.uploads.max-size.check-in-photo}") DataSize checkInPhoto,
                        @Value("${app.uploads.max-size.brand-logo}") DataSize brandLogo,
                        @Value("${app.uploads.max-size.upi-qr}") DataSize upiQr,
                        @Value("${app.uploads.max-size.news-picture}") DataSize newsPicture,
                        @Value("${app.uploads.max-size.project-photo}") DataSize projectPhoto,
                        @Value("${app.uploads.max-size.document}") DataSize document,
                        @Value("${spring.servlet.multipart.max-file-size}") DataSize serverLimit) {
        this.profilePhoto = profilePhoto;
        this.agentPhoto = agentPhoto;
        this.checkInPhoto = checkInPhoto;
        this.brandLogo = brandLogo;
        this.upiQr = upiQr;
        this.newsPicture = newsPicture;
        this.projectPhoto = projectPhoto;
        this.document = document;
        if (Stream.of(profilePhoto, agentPhoto, checkInPhoto, brandLogo, upiQr, newsPicture, projectPhoto, document).anyMatch(s -> s.compareTo(serverLimit) > 0)) {
            log.warn("An app.uploads.max-size.* limit is above spring.servlet.multipart.max-file-size ({}): the server refuses anything over that first", text(serverLimit));
        }
    }

    public View view() {
        return new View(profilePhoto.toBytes(), agentPhoto.toBytes(), checkInPhoto.toBytes(), brandLogo.toBytes(), upiQr.toBytes(), newsPicture.toBytes(),
                projectPhoto.toBytes(), document.toBytes());
    }

    /** Refuses a file over the limit: "{what} must be 2 MB or smaller". */
    public static void check(long bytes, DataSize limit, String what) {
        if (bytes > limit.toBytes()) {
            throw ApiException.badRequest(what + " must be " + text(limit) + " or smaller");
        }
    }

    /** 2 MB, 1.5 MB or 500 KB. */
    static String text(DataSize size) {
        long bytes = size.toBytes();
        long mb = DataSize.ofMegabytes(1).toBytes();
        if (bytes >= mb) {
            return bytes % mb == 0 ? bytes / mb + " MB" : String.format(Locale.ENGLISH, "%.1f MB", (double) bytes / mb);
        }
        return Math.max(1, bytes / 1024) + " KB";
    }
}
