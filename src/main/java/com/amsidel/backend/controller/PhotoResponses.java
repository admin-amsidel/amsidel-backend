package com.amsidel.backend.controller;

import com.amsidel.backend.entity.EmployeePhoto;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

/** Sends a stored photo. The web app adds ?v=&lt;photoUpdatedAt&gt; to the URL, so a cached copy is never stale. */
final class PhotoResponses {

    private PhotoResponses() {
    }

    static ResponseEntity<byte[]> image(EmployeePhoto photo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(photo.getContent());
    }
}
