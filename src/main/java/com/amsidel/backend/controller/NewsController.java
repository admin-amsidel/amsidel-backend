package com.amsidel.backend.controller;

import com.amsidel.backend.dto.NewsDtos.*;
import com.amsidel.backend.entity.NewsImage;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.service.NewsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

/**
 * News and announcements. /api/news: the dashboard (everyone signed in) and managing the news (the News menu,
 * checked in the service). /api/public/news: the website's news for customers, no sign-in.
 */
@RestController
@RequiredArgsConstructor
public class NewsController {

    private final NewsService news;

    /** Today's news and birthdays, for the dashboard. */
    @GetMapping("/api/news/feed")
    public Feed feed(@AuthenticationPrincipal AuthUser me) {
        return news.feed(me);
    }

    @GetMapping("/api/news")
    public List<NewsView> all(@AuthenticationPrincipal AuthUser me) {
        return news.all(me);
    }

    @PostMapping("/api/news")
    @ResponseStatus(HttpStatus.CREATED)
    public NewsView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody NewsBody body) {
        return news.create(me, body);
    }

    @PutMapping("/api/news/{id}")
    public NewsView update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody NewsBody body) {
        return news.update(me, id, body);
    }

    @DeleteMapping("/api/news/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        news.delete(me, id);
    }

    /** Multipart, one part named "file": JPEG, PNG or WebP, up to 3 MB. */
    @PostMapping("/api/news/{id}/image")
    public NewsView uploadImage(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return news.saveImage(me, id, file);
    }

    @DeleteMapping("/api/news/{id}/image")
    public NewsView deleteImage(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return news.deleteImage(me, id);
    }

    /** Signed in; the portal adds ?v=<imageUpdatedAt>. */
    @GetMapping("/api/news/{id}/image")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return picture(news.image(me, id), CacheControl.maxAge(Duration.ofDays(7)).cachePrivate());
    }

    // ---- the website ----

    @GetMapping("/api/public/news")
    public ResponseEntity<List<PublicNews>> forCustomers() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(news.forCustomers());
    }

    @GetMapping("/api/public/news/{id}/image")
    public ResponseEntity<byte[]> publicImage(@PathVariable Long id) {
        return picture(news.publicImage(id), CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }

    private static ResponseEntity<byte[]> picture(NewsImage image, CacheControl cache) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.getContentType())).cacheControl(cache).body(image.getContent());
    }
}
