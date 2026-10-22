package com.amsidel.backend.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Who may call what. The API is stateless: every call carries "Authorization: asd &lt;token&gt;" (AsdTokenResolver)
 * from POST /api/auth/login, so there are no sessions or cookies (and CSRF protection is not needed).
 * Which menus (and their APIs) each person may use is set in the database: see MenuAccessService.
 */
@Configuration
public class SecurityConfig {

    private static final String[] EVERY_ROLE = {"OWNER", "ADMIN", "MANAGER", "EMPLOYEE"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, EmployeeTokenConverter tokenConverter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // reachable with a temporary PIN too, so the person can replace it
                        .requestMatchers("/api/auth/**").authenticated()
                        // public website reads products; only owners and admins change them
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers("/api/products/**").hasAnyRole("OWNER", "ADMIN")
                        // brands: everyone reads them; only owners change them
                        .requestMatchers(HttpMethod.GET, "/api/brands/**").hasAnyRole(EVERY_ROLE)
                        .requestMatchers("/api/brands/**").hasRole("OWNER")
                        // whatever the menu access says, only owners and admins change employee records (roles,
                        // PINs) and menu access, so nobody can give themselves more rights
                        .requestMatchers(HttpMethod.GET, "/api/employees/**").hasAnyRole(EVERY_ROLE)
                        .requestMatchers("/api/employees/**").hasAnyRole("OWNER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/menu-access/**").hasAnyRole(EVERY_ROLE)
                        .requestMatchers("/api/menu-access/**").hasAnyRole("OWNER", "ADMIN")
                        // everything else: signed in; each API's menu checkpoint (@Checkpoint, MenuAccessService)
                        // decides who may use it, and the services decide whose data they see
                        .anyRequest().hasAnyRole(EVERY_ROLE))
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(new AsdTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(tokenConverter)));
        return http.build();
    }

    @Bean
    public SecretKey jwtSigningKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (base64 encoded)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, @Value("${app.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
