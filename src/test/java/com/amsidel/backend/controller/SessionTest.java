/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AppConfiguration;
import com.amsidel.backend.repository.AppConfigurationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.service.ConfigValues;
import com.amsidel.backend.service.SessionService;
import com.amsidel.backend.service.SessionSettings;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The configurations table over the properties file; a sign-in's time limit, checked on each call and marked by the scheduler; who is signed in. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=session", "app.session.max-minutes=360", "app.session.check-initial-delay=PT24H"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionTest {

    private static final String EXPIRED = "Session expired. Please log in again.";
    private static final String ACTIVE_USERS_TICK = "403";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppConfigurationRepository configurations;
    @Autowired
    private ConfigValues config;
    @Autowired
    private SessionSettings settings;
    @Autowired
    private SessionService sessions;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Value("${app.jwt.issuer}")
    private String issuer;

    private String owner;
    private int dw;
    private int other;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
        other = JsonPath.read(body(mockMvc.perform(post("/api/brands").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"SE\",\"name\":\"Session Other\"}"))), "$.id");
    }

    @AfterEach
    void backToNormal() {
        set(SessionSettings.MAX_MINUTES, "360", true);
    }

    private void set(String code, String value, boolean active) {
        AppConfiguration c = configurations.findById(code).orElseGet(AppConfiguration::new);
        c.setCode(code);
        c.setValue(value);
        c.setActive(active);
        configurations.save(c);
        config.reload();
    }

    /** {id, userId, token}. */
    private String[] person(int brand, String... checkpoints) throws Exception {
        String userId = "se." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand))).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            mockMvc.perform(put("/api/menu-access/EMPLOYEE/" + id).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}")).andExpect(status().isOk());
        }
        return new String[]{id, userId, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private void setZone(int brand, String zone) throws Exception {
        mockMvc.perform(put("/api/brands/" + brand).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SE\",\"name\":\"Session Other\",\"timeZone\":\"" + zone + "\"}")).andExpect(status().isOk());
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", auth(token)));
    }

    private void backdateSignIn(String id, Duration ago) {
        var e = employees.findById(Long.valueOf(id)).orElseThrow();
        e.setLastLoginAt(Instant.now().minus(ago));
        employees.save(e);
    }

    @Test
    void anActiveRowWinsOverThePropertiesFileAndAnythingElseFallsBack() {
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        assertThat(configurations.findById(SessionSettings.MAX_MINUTES)).get().satisfies(c -> {
            assertThat(c.getValue()).isEqualTo("360");
            assertThat(c.isActive()).isTrue();
            assertThat(c.getDescription()).isNotBlank();
        });

        set(SessionSettings.MAX_MINUTES, "90", true);
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(90));
        // inactive, empty, nonsense, or not above zero: the properties file's value
        set(SessionSettings.MAX_MINUTES, "90", false);
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        set(SessionSettings.MAX_MINUTES, " ", true);
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        set(SessionSettings.MAX_MINUTES, "soon", true);
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        set(SessionSettings.MAX_MINUTES, "-5", true);
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        // missing
        configurations.deleteById(SessionSettings.MAX_MINUTES);
        config.reload();
        assertThat(settings.maxValidity()).isEqualTo(Duration.ofMinutes(360));
    }

    @Test
    void theTokenLastsTheConfiguredTime() throws Exception {
        String[] p = person(dw);
        set(SessionSettings.MAX_MINUTES, "90", true);
        String res = body(login(p[1], "cd@456").andExpect(status().isOk()));
        Instant expires = Instant.parse(JsonPath.read(res, "$.expiresAt"));
        assertThat(Duration.between(Instant.now(), expires).toMinutes()).isBetween(88L, 90L);
    }

    @Test
    void theSchedulerMarksAnOverdueSessionAndItStaysDeadAfterANewSignIn() throws Exception {
        String[] p = person(dw);
        String old = token(login(p[1], "cd@456"));
        me(old).andExpect(status().isOk());
        Thread.sleep(1100);

        // ended by the scheduler: refused with the message, marked on the employee
        backdateSignIn(p[0], Duration.ofMinutes(361));
        assertThat(sessions.expireOverdue()).isGreaterThanOrEqualTo(1);
        me(old).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(EXPIRED)).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getSessionExpired()).isTrue();
        // once is enough
        assertThat(sessions.expireOverdue()).isZero();

        // signing in again starts a new session, and the old token stays dead
        Thread.sleep(1100);
        String fresh = body(login(p[1], "cd@456").andExpect(status().isOk()));
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getSessionExpired()).isFalse();
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getLastLoginAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
        me(JsonPath.read(fresh, "$.token")).andExpect(status().isOk()).andExpect(jsonPath("$.sessionExpired").value(false)).andExpect(jsonPath("$.lastLoginAt").exists());
        me(old).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(EXPIRED));
    }

    @Test
    void aCallPastTheConfiguredTimeIsRefusedEvenBeforeTheSchedulerRuns() throws Exception {
        String[] p = person(dw);
        // from before their first PIN, which would refuse it for another reason
        var e = employees.findById(Long.valueOf(p[0])).orElseThrow();
        e.setPasswordChangedAt(null);
        employees.save(e);
        // two hours old, and by its own expiry still good for hours
        String aged = tokenIssued(p, Duration.ofHours(2));
        me(aged).andExpect(status().isOk());

        // shortening the time ends tokens already given, at once; nothing has been marked on the employee
        set(SessionSettings.MAX_MINUTES, "60", true);
        me(aged).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(EXPIRED));
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getSessionExpired()).isNotEqualTo(Boolean.TRUE);

        // and lengthening it brings it back, as the token itself has not run out
        set(SessionSettings.MAX_MINUTES, "180", true);
        me(aged).andExpect(status().isOk());
    }

    private String tokenIssued(String[] person, Duration ago) {
        Instant at = Instant.now().minus(ago).truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(person[0]).issuedAt(at).expiresAt(at.plus(Duration.ofHours(12)))
                .claim("userId", person[1]).claim("role", "EMPLOYEE").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void signingOutEndsTheSessionAndSigningInStartsAgainAtOnce() throws Exception {
        String[] p = person(dw);
        Thread.sleep(1100);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", auth(p[2]))).andExpect(status().isNoContent());
        me(p[2]).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(EXPIRED));
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getSessionExpired()).isTrue();
        me(token(login(p[1], "cd@456"))).andExpect(status().isOk());
    }

    @Test
    void aBrokenTokenSaysTheSameAndNoTokenStaysAPlain401() throws Exception {
        me("not.a.token").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(EXPIRED));
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        // a wrong PIN is not an expired session
        login("owner", "Wrong#99").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Wrong user ID or PIN"));
    }

    @Test
    void activeUsersAreForThoseGivenTheTickAndOnlyTheirBrand() throws Exception {
        String[] plain = person(dw);
        mockMvc.perform(get("/api/sessions/active").header("Authorization", auth(plain[2]))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sessions/active/count").header("Authorization", auth(plain[2]))).andExpect(status().isForbidden());

        String[] mine = person(dw, ACTIVE_USERS_TICK);
        String[] elsewhere = person(other);
        String[] gone = person(dw);
        String[] out = person(dw);
        backdateSignIn(gone[0], Duration.ofMinutes(361));
        mockMvc.perform(post("/api/auth/logout").header("Authorization", auth(out[2]))).andExpect(status().isNoContent());

        // each person's times go by their own brand's zone: India by default
        setZone(other, "Asia/Dubai");
        mockMvc.perform(get("/api/sessions/active").header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[?(@.id == " + plain[0] + ")].timeZone", hasItem("Asia/Kolkata")))
                .andExpect(jsonPath("$.people[?(@.id == " + elsewhere[0] + ")].timeZone", hasItem("Asia/Dubai")))
                .andExpect(jsonPath("$.maxMinutes").value(360))
                .andExpect(jsonPath("$.people[*].id", hasItem(Integer.valueOf(plain[0])))).andExpect(jsonPath("$.people[*].id", hasItem(Integer.valueOf(elsewhere[0]))))
                .andExpect(jsonPath("$.people[*].id", not(hasItem(Integer.valueOf(gone[0]))))).andExpect(jsonPath("$.people[*].id", not(hasItem(Integer.valueOf(out[0])))));
        // the count alone sends nobody's details, and agrees with the list
        int listed = JsonPath.read(body(mockMvc.perform(get("/api/sessions/active").header("Authorization", auth(mine[2])))), "$.count");
        mockMvc.perform(get("/api/sessions/active/count").header("Authorization", auth(mine[2]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(listed)).andExpect(jsonPath("$.people").isEmpty());
        mockMvc.perform(get("/api/sessions/active").header("Authorization", auth(mine[2]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].id", hasItem(Integer.valueOf(plain[0])))).andExpect(jsonPath("$.people[*].id", not(hasItem(Integer.valueOf(elsewhere[0])))));
    }

    @Test
    void changingThePinKeepsTheNewSessionAlive() throws Exception {
        String[] p = person(dw);
        backdateSignIn(p[0], Duration.ofMinutes(300));
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String fresh = token(changePin(p[2], "cd@456", "ef@789"));
        assertThat(employees.findById(Long.valueOf(p[0])).orElseThrow().getLastLoginAt()).isAfterOrEqualTo(before);
        sessions.expireOverdue();
        me(fresh).andExpect(status().isOk());
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
