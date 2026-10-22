package com.amsidel.backend.security;

import com.amsidel.backend.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Turns a valid token into the signed-in {@link AuthUser}. The person's role and account state come from
 * {@link SignedInCache}, which any change to the employee clears, so disabling an account or resetting a PIN
 * still ends that person's sessions on their next call.
 */
@Component
@RequiredArgsConstructor
public class EmployeeTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final EmployeeRepository employeeRepository;
    private final SignedInCache cache;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long id = Long.valueOf(jwt.getSubject());
        SignedInCache.Entry employee = cache.get(id, () -> employeeRepository.findWithBrandById(id),
                        employeeRepository::existsByReportsTo_IdAndActiveTrue)
                .filter(SignedInCache.Entry::active)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));

        Instant changedAt = employee.passwordChangedAt();
        if (changedAt != null && jwt.getIssuedAt() != null
                && jwt.getIssuedAt().isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS))) {
            throw new InvalidBearerTokenException("PIN was changed, please sign in again");
        }

        // Until a temporary PIN is replaced the person gets no role, which only lets them reach /api/auth/**
        List<GrantedAuthority> authorities = employee.mustChangePassword()
                ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + employee.user().role().name()));
        return UsernamePasswordAuthenticationToken.authenticated(employee.user(), jwt, authorities);
    }
}
