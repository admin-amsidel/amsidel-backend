/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.NewsDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.NewsImageRepository;
import com.amsidel.backend.repository.NewsPostRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Comparator;
import java.util.List;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * News and announcements. Everyone signed in sees today's news on the dashboard (the group's and their own brand's;
 * owners and people without a brand see every brand's), with today's birthdays from the employees' dates of birth.
 * The website shows the Public and All ones (Customers news is for staff to pass on). People with the News menu (owners and admins, unless given to
 * others) add, change, hide and delete them.
 */
@Service
@RequiredArgsConstructor
public class NewsService {

    /** Pictures up to 3 MB (the portal makes them smaller before uploading). */
    private static final int MAX_IMAGE = 3 * 1024 * 1024;

    private final NewsPostRepository repository;
    private final NewsImageRepository imageRepository;
    private final BrandRepository brandRepository;
    private final EmployeeRepository employeeRepository;
    private final MenuAccessService menuAccess;
    private final TradeAccess access;

    private boolean canManage(AuthUser me) {
        return menuAccess.can(me, Menus.NEWS);
    }

    private void requireManage(AuthUser me) {
        if (!canManage(me)) {
            throw ApiException.forbidden("Only people with the News menu can change the news");
        }
    }

    /** The dashboard: today's news for me, and today's birthdays. */
    @Transactional(readOnly = true)
    public Feed feed(AuthUser me) {
        LocalDate today = access.today();
        List<NewsView> news = repository.live(today).stream().filter(n -> mine(me, n)).map(n -> view(n, today)).toList();
        MonthDay day = MonthDay.from(today);
        List<Birthday> birthdays = employeeRepository.withBirthdays().stream()
                .filter(e -> birthday(e.getDob(), today, day))
                .filter(e -> access.allBrands(me) || e.getBrand() == null || e.getBrand().getId().equals(me.brandId()))
                .sorted(Comparator.comparing(Employee::getName))
                .map(e -> new Birthday(e.getId(), e.getName(), e.getDesignation() == null ? null : e.getDesignation().getName(),
                        e.getBrand() == null ? null : e.getBrand().getCode(), e.getPhotoUpdatedAt()))
                .toList();
        return new Feed(news, birthdays, canManage(me));
    }

    /** Born on this day of the year; 29 February birthdays are on 28 February in other years. */
    static boolean birthday(LocalDate dob, LocalDate today, MonthDay day) {
        MonthDay born = MonthDay.from(dob);
        if (born.getMonthValue() == 2 && born.getDayOfMonth() == 29 && !today.isLeapYear()) {
            born = MonthDay.of(2, 28);
        }
        return born.equals(day);
    }

    /** Group news, or my brand's (owners and people of no brand: every brand's). */
    private boolean mine(AuthUser me, NewsPost n) {
        return n.getBrand() == null || access.allBrands(me) || n.getBrand().getId().equals(me.brandId());
    }

    /** Every news item, also hidden, ended and coming ones: for managing them. */
    @Transactional(readOnly = true)
    public List<NewsView> all(AuthUser me) {
        requireManage(me);
        LocalDate today = access.today();
        return repository.all().stream().map(n -> view(n, today)).toList();
    }

    @Transactional
    public NewsView create(AuthUser me, NewsBody body) {
        requireManage(me);
        NewsPost n = new NewsPost();
        n.setCreatedBy(access.me(me));
        fill(n, body);
        repository.saveAndFlush(n);
        return view(n, access.today());
    }

    @Transactional
    public NewsView update(AuthUser me, Long id, NewsBody body) {
        requireManage(me);
        NewsPost n = find(id);
        fill(n, body);
        n.setUpdatedBy(access.me(me));
        repository.saveAndFlush(n);
        return view(n, access.today());
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        requireManage(me);
        NewsPost n = find(id);
        imageRepository.deleteById(id);
        repository.delete(n);
    }

    private void fill(NewsPost n, NewsBody body) {
        LocalDate start = body.getStartDate() == null ? access.today() : body.getStartDate();
        if (body.getEndDate() != null && body.getEndDate().isBefore(start)) {
            throw ApiException.badRequest("The last day is before the first day");
        }
        n.setTitle(body.getTitle().strip());
        n.setBody(blank(body.getBody()));
        n.setCategory(body.getCategory());
        n.setAudience(body.getAudience());
        n.setBrand(body.getBrandId() == null ? null
                : brandRepository.findById(body.getBrandId()).orElseThrow(() -> ApiException.badRequest("Brand not found")));
        n.setStartDate(start);
        n.setEndDate(body.getEndDate());
        n.setActive(body.isActive());
        n.setPinned(body.isPinned());
    }

    // ---- the picture ----

    @Transactional
    public NewsView saveImage(AuthUser me, Long id, MultipartFile file) {
        requireManage(me);
        NewsPost n = find(id);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a picture to upload");
        }
        if (file.getSize() > MAX_IMAGE) {
            throw ApiException.badRequest("The picture must be 3 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The picture could not be read; please try again");
        }
        String type = imageType(bytes);
        if (type == null) {
            throw ApiException.badRequest("The picture must be a JPEG, PNG or WebP image");
        }
        NewsImage image = imageRepository.findById(id).orElseGet(NewsImage::new);
        image.setNewsId(id);
        image.setContent(bytes);
        image.setContentType(type);
        image.setUpdatedAt(Instant.now());
        imageRepository.save(image);
        n.setImageUpdatedAt(image.getUpdatedAt());
        n.setUpdatedBy(access.me(me));
        repository.saveAndFlush(n);
        return view(n, access.today());
    }

    @Transactional
    public NewsView deleteImage(AuthUser me, Long id) {
        requireManage(me);
        NewsPost n = find(id);
        imageRepository.deleteById(id);
        n.setImageUpdatedAt(null);
        n.setUpdatedBy(access.me(me));
        repository.saveAndFlush(n);
        return view(n, access.today());
    }

    /** For someone signed in: a picture of news they can see (managers: of any). */
    @Transactional(readOnly = true)
    public NewsImage image(AuthUser me, Long id) {
        NewsPost n = find(id);
        if (!canManage(me) && !(n.liveOn(access.today()) && mine(me, n))) {
            throw new ResourceNotFoundException("No picture for news " + id);
        }
        return imageRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No picture for news " + id));
    }

    // ---- the website ----

    @Transactional(readOnly = true)
    public List<PublicNews> forCustomers() {
        return repository.live(access.today()).stream().filter(n -> n.getAudience().onWebsite())
                .map(n -> new PublicNews(n.getId(), n.getTitle(), n.getBody(), n.getCategory(),
                        n.getBrand() == null ? null : n.getBrand().getCode(), n.getBrand() == null ? null : n.getBrand().getName(),
                        n.getStartDate(), n.getEndDate(), n.getImageUpdatedAt()))
                .toList();
    }

    /** Only the picture of news on the website (Public or All) and showing today. */
    @Transactional(readOnly = true)
    public NewsImage publicImage(Long id) {
        NewsPost n = repository.findById(id).filter(x -> x.getAudience().onWebsite() && x.liveOn(access.today()))
                .orElseThrow(() -> new ResourceNotFoundException("No picture for news " + id));
        return imageRepository.findById(n.getId()).orElseThrow(() -> new ResourceNotFoundException("No picture for news " + id));
    }

    // ---- helpers ----

    private NewsPost find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("News not found: " + id));
    }

    /** By the first bytes: JPEG, PNG or WebP. */
    static String imageType(byte[] b) {
        String t = BrandService.imageType(b);
        if (t != null) {
            return t;
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    static NewsView view(NewsPost n, LocalDate today) {
        String status = !n.isActive() ? "HIDDEN" : n.getStartDate().isAfter(today) ? "SCHEDULED"
                : n.getEndDate() != null && n.getEndDate().isBefore(today) ? "ENDED" : "LIVE";
        Brand b = n.getBrand();
        return new NewsView(n.getId(), n.getTitle(), n.getBody(), n.getCategory(), n.getAudience(), b == null ? null : b.getId(),
                b == null ? null : b.getCode(), b == null ? null : b.getName(), n.getStartDate(), n.getEndDate(), n.isActive(),
                n.isPinned(), n.getImageUpdatedAt(), status, n.getCreatedBy() == null ? null : n.getCreatedBy().getName(),
                n.getCreatedAt(), n.getUpdatedBy() == null ? null : n.getUpdatedBy().getName(), n.getUpdatedAt());
    }
}
