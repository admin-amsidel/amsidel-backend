/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailAccountRequest;
import com.amsidel.backend.dto.MailConfigRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.MailBrandAccount;
import com.amsidel.backend.entity.MailCount;
import com.amsidel.backend.entity.MailLimits;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.MailCountRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * One kind of mail's limits per brand (staff email; customer &amp; supplier email): how many people Choose may reach in
 * one send, how many emails may go in a day with the day's count, and the office address copied (CC) on every mail.
 * An email counts for the brand of the person it goes to, whoever sends it, so owners and group admins (who have no
 * brand) use up the same allowance. The daily limit binds everyone; only an email that went out stays counted.
 * WhatsApp opens in the sender's own WhatsApp and is not counted. The count is in the database, so a restart does not
 * reset it. Each kind of mail has its own limits and its own count. A brand's own Brevo account (BrandMailSenders) is
 * one per brand, for both kinds of mail, so it can be set up from either screen. The header and footer lines of the
 * mail are set per brand for each kind of mail.
 */
public abstract class MailLimitsService<C extends MailLimits, D extends MailCount> {

    static final int DEFAULT_PICK_LIMIT = 5;
    static final String DEFAULT_HEADER = "AMSIDEL";
    /** brandKey of the group-level people, who have no brand. */
    static final long NO_BRAND = 0L;
    /** How long one send counts as one: plenty for a handful of mails, short enough not to get in the way. */
    static final Duration ONE_SEND = Duration.ofMinutes(5);

    private final JpaRepository<C, Long> configs;
    private final MailCountRepository<D> counts;
    private final BrandRepository brands;
    private final ZoneId zone;
    private final String defaultCc;
    private final BrandMailSenders senders;
    /** Per sender and brand: who they mailed lately and when. */
    private final Map<String, Map<String, Instant>> recent = new ConcurrentHashMap<>();

    protected MailLimitsService(JpaRepository<C, Long> configs, MailCountRepository<D> counts, BrandRepository brands, ZoneId zone,
                                String defaultCc, BrandMailSenders senders) {
        this.configs = configs;
        this.counts = counts;
        this.brands = brands;
        this.zone = zone;
        this.defaultCc = defaultCc == null ? "" : defaultCc.trim();
        this.senders = senders;
    }

    /**
     * One brand's limits and today's count; brandId null = the group-level people; dailyLimit and leftToday null = no
     * limit. alwaysCc: the address copied on every mail; ccOverride: the brand's own, null when it uses the default.
     * ownAccount: the brand has its own Brevo account, with these From details; accountLogin only on the config screens.
     * The SMTP key never leaves the server. mailHeader, mailFooter: the brand's own lines, null when it uses the defaults.
     */
    public record BrandLimits(Long brandId, String brandCode, String brandName, int pickLimit, Integer dailyLimit,
                              int sentToday, Integer leftToday, String alwaysCc, String ccOverride, boolean ownAccount,
                              String accountLogin, String accountFrom, String accountFromName, String mailHeader, String mailFooter,
                              String defaultHeader, String defaultFooter) {
    }

    /** The lines at the top and the bottom of a mail. */
    public record MailLook(String header, String footer) {
    }

    /** The brands I may set limits for and mail. */
    protected abstract List<Brand> brandsFor(AuthUser me);

    /** May I see the people without a brand? (Customers and suppliers always have one.) */
    protected abstract boolean seesNoBrand(AuthUser me);

    /** For a brand with no row of its own. */
    protected abstract int defaultPickLimit();

    /** The line at the bottom of the mail when the brand has set none; brandName null = the group-level people. */
    protected abstract String defaultFooter(String brandName);

    protected abstract C newConfig(long brandKey);

    protected abstract D newCount(long brandKey, LocalDate day);

    /** Its page, for messages. */
    protected abstract String configPage();

    /** This kind of mail, for messages. */
    protected abstract String mailName();

    @Transactional(readOnly = true)
    public List<BrandLimits> rows(AuthUser me) {
        Map<Long, C> config = configs.findAll().stream().collect(Collectors.toMap(MailLimits::getBrandKey, c -> c));
        Map<Long, Integer> sent = counts.findBySentOn(today()).stream().collect(Collectors.toMap(MailCount::getBrandKey, MailCount::getSentCount));
        Map<Long, MailBrandAccount> accounts = senders.all().stream().collect(Collectors.toMap(MailBrandAccount::getBrandKey, a -> a));
        int fallback = defaultPickLimit();
        List<BrandLimits> rows = new ArrayList<>();
        for (Brand b : brandsFor(me)) {
            rows.add(row(b, config.get(b.getId()), accounts.get(b.getId()), fallback, sent.getOrDefault(b.getId(), 0)));
        }
        if (seesNoBrand(me)) {
            rows.add(row(null, config.get(NO_BRAND), accounts.get(NO_BRAND), fallback, sent.getOrDefault(NO_BRAND, 0)));
        }
        return rows;
    }

    /** For those sending: the same, without the brand's Brevo login. */
    @Transactional(readOnly = true)
    public List<BrandLimits> rules(AuthUser me) {
        return rows(me).stream().map(r -> new BrandLimits(r.brandId(), r.brandCode(), r.brandName(), r.pickLimit(), r.dailyLimit(),
                r.sentToday(), r.leftToday(), r.alwaysCc(), r.ccOverride(), r.ownAccount(), null, r.accountFrom(), r.accountFromName(), r.mailHeader(), r.mailFooter(),
                r.defaultHeader(), r.defaultFooter())).toList();
    }

    /** The brand's own Brevo account; a blank key keeps the saved one. */
    @Transactional
    public List<BrandLimits> saveAccount(AuthUser me, MailAccountRequest body) {
        senders.save(requireBrand(me, body.getBrandId()), body);
        return rows(me);
    }

    /** The brand goes back to the default account. */
    @Transactional
    public List<BrandLimits> removeAccount(AuthUser me, Long brandId) {
        senders.remove(requireBrand(me, brandId));
        return rows(me);
    }

    public BrandMailSenders.TestResult testAccount(AuthUser me, Long brandId) {
        return senders.test(requireBrand(me, brandId));
    }

    /** Owners and group admins set any brand's limits; a brand's own people with the tick only theirs. */
    @Transactional
    public List<BrandLimits> save(AuthUser me, MailConfigRequest body) {
        long key = requireBrand(me, body.getBrandId());
        C c = configs.findById(key).orElseGet(() -> newConfig(key));
        c.setPickLimit(body.getPickLimit());
        c.setDailyLimit(body.getDailyLimit());
        c.setAlwaysCc(StringUtils.hasText(body.getAlwaysCc()) ? body.getAlwaysCc().trim() : null);
        c.setMailHeader(StringUtils.hasText(body.getMailHeader()) ? body.getMailHeader().trim() : null);
        c.setMailFooter(StringUtils.hasText(body.getMailFooter()) ? body.getMailFooter().trim() : null);
        configs.save(c);
        return rows(me);
    }

    /** The brand's key, when I may change it; brandId null = the group-level people. */
    private long requireBrand(AuthUser me, Long brandId) {
        boolean allowed = brandId == null ? seesNoBrand(me) : brandsFor(me).stream().anyMatch(b -> b.getId().equals(brandId));
        if (!allowed) {
            if (brandId != null && !brands.existsById(brandId) && seesNoBrand(me)) {
                throw ApiException.badRequest("Brand not found: " + brandId);
            }
            throw ApiException.forbidden("You can only change the settings of your own brand");
        }
        return brandId == null ? NO_BRAND : brandId;
    }

    /** People of this brand Choose may reach in one send. */
    @Transactional(readOnly = true)
    public int pickLimit(long brandKey) {
        return configs.findById(brandKey).map(MailLimits::getPickLimit).orElseGet(this::defaultPickLimit);
    }

    /** The address copied on every mail to this brand's people: the brand's own, or the default office address. */
    @Transactional(readOnly = true)
    public String alwaysCc(long brandKey) {
        return configs.findById(brandKey).map(MailLimits::getAlwaysCc).filter(StringUtils::hasText).orElse(defaultCc);
    }

    /** The header and footer lines of a mail to this brand's people: the brand's own, or the defaults. */
    @Transactional(readOnly = true)
    public MailLook look(long brandKey) {
        C c = configs.findById(brandKey).orElse(null);
        String header = c != null && StringUtils.hasText(c.getMailHeader()) ? c.getMailHeader() : DEFAULT_HEADER;
        String footer = c != null && StringUtils.hasText(c.getMailFooter()) ? c.getMailFooter()
                : defaultFooter(brandKey == NO_BRAND ? null : brands.findById(brandKey).map(Brand::getName).orElse(null));
        return new MailLook(header, footer);
    }

    /**
     * Choose on the server: at most the brand's limit of different people within a few minutes (sending the failed
     * ones again is the same people, so it is not held back).
     */
    public void checkPick(AuthUser me, String person, long brandKey) {
        int limit = pickLimit(brandKey);
        Instant now = Instant.now();
        Map<String, Instant> mine = recent.computeIfAbsent(me.id() + ":" + brandKey, k -> new ConcurrentHashMap<>());
        synchronized (mine) {
            mine.values().removeIf(at -> at.isBefore(now.minus(ONE_SEND)));
            if (!mine.containsKey(person) && mine.size() >= limit) {
                throw ApiException.conflict("You can send to at most " + limit + (limit == 1 ? " person" : " people")
                        + " of this brand at a time. Wait a few minutes before the next send.");
            }
            mine.put(person, now);
        }
    }

    /** Counts one email for this brand today, or refuses it when the day's limit is reached. */
    public void take(long brandKey) {
        Integer daily = configs.findById(brandKey).map(MailLimits::getDailyLimit).orElse(null);
        LocalDate today = today();
        ensureCounter(brandKey, today);
        if (counts.take(brandKey, today, daily == null ? Integer.MAX_VALUE : daily) == 0) {
            throw ApiException.conflict("Today's " + mailName() + " limit for " + label(brandKey) + " is reached (" + daily
                    + " a day). More can go tomorrow, or the limit can be raised under " + configPage() + ".");
        }
    }

    /** The email did not go out: it does not count. */
    public void giveBack(long brandKey) {
        counts.giveBack(brandKey, today());
    }

    /** Today's row; two sends may both try to make it, and the second simply finds it there. */
    private void ensureCounter(long brandKey, LocalDate day) {
        if (counts.existsByBrandKeyAndSentOn(brandKey, day)) {
            return;
        }
        try {
            counts.saveAndFlush(newCount(brandKey, day));
        } catch (DataIntegrityViolationException alreadyMade) {
            // made by the other send a moment ago
        }
    }

    private BrandLimits row(Brand b, C c, MailBrandAccount a, int fallback, int sent) {
        Integer daily = c == null ? null : c.getDailyLimit();
        String own = c == null || !StringUtils.hasText(c.getAlwaysCc()) ? null : c.getAlwaysCc();
        return new BrandLimits(b == null ? null : b.getId(), b == null ? null : b.getCode(), b == null ? "Group (no brand)" : b.getName(),
                c == null ? fallback : c.getPickLimit(), daily, sent, daily == null ? null : Math.max(0, daily - sent),
                own != null ? own : defaultCc, own, a != null, a == null ? null : a.getSmtpLogin(),
                a == null ? null : a.getFromEmail(), a == null ? null : a.getFromName(),
                c == null ? null : c.getMailHeader(), c == null ? null : c.getMailFooter(), DEFAULT_HEADER, defaultFooter(b == null ? null : b.getName()));
    }

    private String label(long brandKey) {
        return brandKey == NO_BRAND ? "the group" : brands.findById(brandKey).map(Brand::getCode).orElse("this brand");
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }
}
