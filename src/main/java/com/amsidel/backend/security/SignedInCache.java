package com.amsidel.backend.security;

import com.amsidel.backend.entity.Employee;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The few facts every request checks about the signed-in person (role, brand, active, temporary PIN, PIN change
 * time), kept for a short while so each portal call does not wait on a database round trip first.
 * Any change to an employee row clears that person's entry at once ({@link EmployeeCacheListener}), so disabling an
 * account, resetting a PIN or changing a role still applies on the very next call. The time limit only covers
 * changes made outside this backend (e.g. straight in the database, or by another copy of the server).
 */
@Component
public class SignedInCache {

    /** What the token check needs; no PIN hash or personal details. */
    public record Entry(AuthUser user, boolean active, boolean mustChangePassword, Instant passwordChangedAt,
                        Instant expiresAt) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SignedInCache(@Value("${app.auth.cache-ttl:PT60S}") Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * The cached entry, or one built from {@code loader} (nothing is kept when the employee does not exist);
     * {@code leadsTeam} says whether anyone reports to them.
     */
    public Optional<Entry> get(Long id, Supplier<Optional<Employee>> loader, java.util.function.Predicate<Long> leadsTeam) {
        Instant now = Instant.now();
        Entry entry = entries.get(id);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return Optional.of(entry);
        }
        Optional<Entry> fresh = loader.get().map(e -> new Entry(AuthUser.of(e, leadsTeam.test(id)), e.isActive(), e.isMustChangePassword(),
                e.getPasswordChangedAt(), now.plus(ttl)));
        fresh.ifPresentOrElse(f -> entries.put(id, f), () -> entries.remove(id));
        return fresh;
    }

    public void evict(Long id) {
        if (id != null) {
            entries.remove(id);
        }
    }

    /** Everyone: a change to one person (e.g. who they report to) can change their manager's entry too. */
    public void evictAll() {
        entries.clear();
    }
}
