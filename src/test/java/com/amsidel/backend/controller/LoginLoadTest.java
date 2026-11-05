/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.PinChecks;
import com.amsidel.backend.security.SignedInCache;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-in when many people do it together: the PIN check holds no connection, and what changes meanwhile still counts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.login.max-concurrent-checks=8")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginLoadTest {

    private static final String LOCKED = "Too many wrong attempts: your account is locked. Ask your admin to set a temporary PIN to release it.";
    private static final String WRONG = "Wrong user ID or PIN";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private TransactionTemplate tx;
    @MockitoSpyBean
    private PasswordEncoder encoder;
    @MockitoSpyBean
    private SignedInCache cache;

    private String owner;
    private long brand;
    private int seq;
    private final AtomicBoolean heldTransaction = new AtomicBoolean();
    /** Runs while a PIN is being checked. */
    private volatile Runnable duringCheck = () -> { };

    @BeforeAll
    void setUp() throws Exception {
        String first = token(login("owner", "Ams@2026"));
        owner = token(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Ams@2026\",\"newPassword\":\"Own#2026\"}")));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", "asd " + owner))), "$[?(@.code == 'DW')].id");
        brand = ids.getFirst();
    }

    @BeforeEach
    void watchChecks() {
        heldTransaction.set(false);
        duringCheck = () -> { };
        doAnswer(call -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                heldTransaction.set(true);
            }
            duringCheck.run();
            return call.callRealMethod();
        }).when(encoder).matches(any(), anyString());
    }

    private String person() throws Exception {
        String userId = "ll." + (++seq);
        mockMvc.perform(post("/api/employees").header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(seq, userId, brand))).andExpect(status().isCreated());
        return userId;
    }

    private void change(String userId, Consumer<Employee> what) {
        tx.executeWithoutResult(s -> what.accept(employees.findByUserId(userId).orElseThrow()));
    }

    @Test
    void thePinIsCheckedOutsideAnyTransactionAndTheLoginStillRecords() throws Exception {
        String userId = person();
        login(userId, "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(WRONG));
        assertThat(employees.findByUserId(userId).orElseThrow().getFailedAttempts()).isEqualTo(1);

        Instant before = Instant.now().minusSeconds(1);
        login(userId, "ab@123").andExpect(status().isOk()).andExpect(jsonPath("$.user.userId").value(userId)).andExpect(jsonPath("$.token").isNotEmpty());
        Employee saved = employees.findByUserId(userId).orElseThrow();
        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getLastLoginAt()).isAfter(before);
        assertThat(heldTransaction).isFalse();

        login("nobody-here", "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(WRONG));
        assertThat(heldTransaction).isFalse();
    }

    @Test
    void theAnswerCarriesTheApproverAndUserIsReadyToUse() throws Exception {
        String bossId = person();
        Long boss = employees.findByUserId(bossId).orElseThrow().getId();
        String userId = person();
        change(userId, e -> e.setApprover(employees.findById(boss).orElseThrow()));
        String token = token(login(userId, "ab@123").andExpect(status().isOk()).andExpect(jsonPath("$.user.approverId").value(boss)));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "asd " + token)).andExpect(status().isOk());
    }

    @Test
    void disablingDuringTheCheckIsRespected() throws Exception {
        String userId = person();
        duringCheck = () -> change(userId, e -> e.setActive(false));
        login(userId, "ab@123").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("This account is disabled. Please contact your admin."))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void lockingDuringTheCheckIsRespected() throws Exception {
        String userId = person();
        duringCheck = () -> change(userId, e -> e.setLockedAt(Instant.now()));
        login(userId, "ab@123").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(LOCKED));
    }

    @Test
    void aNewPinDuringTheCheckMeansTheOldOneNoLongerSignsIn() throws Exception {
        String userId = person();
        String other = employees.findByUserId("owner").orElseThrow().getPasswordHash();
        duringCheck = () -> change(userId, e -> e.setPasswordHash(other));
        login(userId, "ab@123").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(WRONG));
        assertThat(employees.findByUserId(userId).orElseThrow().getLastLoginAt()).isNull();

        // and a wrong guess at a PIN replaced meanwhile is not counted against the new one
        String another = person();
        duringCheck = () -> change(another, e -> e.setPasswordHash(other));
        login(another, "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(WRONG));
        assertThat(employees.findByUserId(another).orElseThrow().getFailedAttempts()).isZero();
    }

    @Test
    void wrongPinsTogetherAreAllCountedAndLockTheAccount() throws Exception {
        String userId = person();
        CountDownLatch together = new CountDownLatch(5);
        duringCheck = () -> {
            together.countDown();
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        List<Thread> guesses = new ArrayList<>();
        List<Integer> answers = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            String guess = "wrong" + i;
            Thread t = new Thread(() -> {
                try {
                    answers.add(login(userId, guess).andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    answers.add(-1);
                }
            });
            guesses.add(t);
            t.start();
        }
        for (Thread t : guesses) {
            t.join(30000);
        }
        assertThat(answers).containsOnly(401).hasSize(5);
        assertThat(employees.findByUserId(userId).orElseThrow().getLockedAt()).isNotNull();

        duringCheck = () -> { };
        login(userId, "ab@123").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(LOCKED));
    }

    @Test
    void whenTheDatabaseIsBusyNobodyIsToldTheirPinIsWrong() throws Exception {
        String userId = person();
        doThrow(new CannotCreateTransactionException("Connection is not available")).when(encoder).matches(any(), anyString());
        login(userId, "ab@123").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void whenTheTokenCheckCannotReachTheDatabaseThePersonStaysSignedIn() throws Exception {
        String token = token(login(person(), "ab@123"));
        doThrow(new CannotCreateTransactionException("Connection is not available")).when(cache).get(any(), any(), any(), any());
        // 503, never the 401 that makes the portal sign them out
        mockMvc.perform(get("/api/auth/me").header("Authorization", "asd " + token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void onlySoManyChecksRunAtOnceAndTheRestAreToldToTryAgain() throws Exception {
        PasswordEncoder slow = mock(PasswordEncoder.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(slow.matches(any(), any())).thenAnswer(call -> {
            started.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return true;
        });
        PinChecks checks = new PinChecks(slow, 1, Duration.ofMillis(100));
        Thread first = new Thread(() -> checks.matches("a", "b"));
        first.start();
        started.await(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> checks.matches("c", "d")).isInstanceOfSatisfying(com.amsidel.backend.exception.ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        finish.countDown();
        first.join(5000);
        // the turn comes back afterwards
        assertThat(checks.matches("e", "f")).isTrue();
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }
}
