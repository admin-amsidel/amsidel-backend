/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.DocumentType;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.EmployeeDocument;
import com.amsidel.backend.repository.EmployeeDocumentFileRepository;
import com.amsidel.backend.repository.EmployeeDocumentRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.service.EmployeeDocumentMigration;
import com.amsidel.backend.service.EmployeeDocumentStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Employee documents are kept in the database; those still on the old disk move there, and are served until they do. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=document-storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentStorageTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private EmployeeDocumentRepository documents;
    @Autowired
    private EmployeeDocumentFileRepository files;
    @Autowired
    private EmployeeDocumentStore disk;
    @Autowired
    private EmployeeDocumentMigration migration;
    @Value("${app.documents.dir}")
    private String dir;

    private String owner;
    private int brand;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        brand = ids.getFirst();
    }

    /** {userId, token}. */
    private String[] person() throws Exception {
        String userId = "st." + (++seq);
        mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"%s\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"9847000000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(userId, userId, brand))).andExpect(status().isCreated());
        return new String[]{userId, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private Employee employee(String userId) {
        return employees.findByUserId(userId).orElseThrow();
    }

    private EmployeeDocument document(String userId, DocumentType type) {
        return documents.findByEmployeeIdAndType(employee(userId).getId(), type).orElseThrow();
    }

    /** A document as the old version left it: a row pointing at a file on the disk. */
    private EmployeeDocument onDisk(String userId, DocumentType type, boolean fileExists) {
        Employee e = employee(userId);
        String path = "documents/%d/%s-1.pdf".formatted(e.getId(), type.name());
        if (fileExists) {
            disk.save(path, PDF);
        }
        EmployeeDocument d = new EmployeeDocument();
        d.setEmployee(e);
        d.setType(type);
        d.setFileName("old.pdf");
        d.setContentType("application/pdf");
        d.setSizeBytes(PDF.length);
        d.setStoredPath(path);
        d.setUploadedAt(Instant.now());
        return documents.save(d);
    }

    private boolean onDisk(EmployeeDocument d, String path) {
        return Files.exists(Path.of(dir).toAbsolutePath().normalize().resolve(path));
    }

    private long filesUnder(String userId) throws IOException {
        Path folder = Path.of(dir).toAbsolutePath().normalize().resolve("documents/" + employee(userId).getId());
        if (!Files.exists(folder)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.count();
        }
    }

    @Test
    void anUploadGoesToTheDatabaseAndNothingToTheDisk() throws Exception {
        String[] p = person();
        upload(p[1], "PAN_CARD", PDF).andExpect(status().isOk()).andExpect(jsonPath("$.uploaded").value(true));
        EmployeeDocument d = document(p[0], DocumentType.PAN_CARD);
        assertThat(d.getStoredPath()).isNull();
        assertThat(files.findById(d.getId()).orElseThrow().getContent()).isEqualTo(PDF);
        assertThat(filesUnder(p[0])).isZero();
        open(p[1], "PAN_CARD").andExpect(status().isOk()).andExpect(content().bytes(PDF)).andExpect(content().contentType("application/pdf"));

        // replaced: the same row and one file, the new one
        upload(p[1], "PAN_CARD", PNG).andExpect(status().isOk());
        assertThat(document(p[0], DocumentType.PAN_CARD).getId()).isEqualTo(d.getId());
        assertThat(files.findById(d.getId()).orElseThrow().getContent()).isEqualTo(PNG);
        open(p[1], "PAN_CARD").andExpect(content().bytes(PNG)).andExpect(content().contentType("image/png"));

        // deleted: the file goes with it
        mockMvc.perform(delete("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(p[1]))).andExpect(status().isNoContent());
        assertThat(files.findById(d.getId())).isEmpty();
        open(p[1], "PAN_CARD").andExpect(status().isNotFound());
    }

    @Test
    void aDocumentStillOnTheDiskOpensThenMovesAndLeavesTheDisk() throws Exception {
        String[] p = person();
        EmployeeDocument d = onDisk(p[0], DocumentType.AADHAAR_CARD, true);
        String path = d.getStoredPath();
        open(p[1], "AADHAAR_CARD").andExpect(status().isOk()).andExpect(content().bytes(PDF));

        assertThat(migration.moveAll().moved()).isGreaterThanOrEqualTo(1);
        EmployeeDocument after = documents.findById(d.getId()).orElseThrow();
        assertThat(after.getStoredPath()).isNull();
        assertThat(files.findById(d.getId()).orElseThrow().getContent()).isEqualTo(PDF);
        assertThat(onDisk(after, path)).isFalse();
        open(p[1], "AADHAAR_CARD").andExpect(status().isOk()).andExpect(content().bytes(PDF)).andExpect(content().contentType("application/pdf"));

        // moving again changes nothing
        migration.moveAll();
        assertThat(files.findById(d.getId()).orElseThrow().getContent()).isEqualTo(PDF);
    }

    @Test
    void theMoveAlsoRunsByItselfAtStart() throws Exception {
        String[] p = person();
        EmployeeDocument d = onDisk(p[0], DocumentType.BANK_PROOF, true);
        migration.atStart();
        for (int i = 0; i < 100 && documents.findById(d.getId()).orElseThrow().getStoredPath() != null; i++) {
            Thread.sleep(100);
        }
        assertThat(documents.findById(d.getId()).orElseThrow().getStoredPath()).isNull();
        open(p[1], "BANK_PROOF").andExpect(status().isOk()).andExpect(content().bytes(PDF));
    }

    @Test
    void aFileAlreadyGoneFromTheDiskIsReportedAndCanBeUploadedAgain() throws Exception {
        String[] p = person();
        EmployeeDocument d = onDisk(p[0], DocumentType.OFFER_LETTER, false);
        assertThat(migration.moveAll().missing()).isGreaterThanOrEqualTo(1);
        assertThat(documents.findById(d.getId()).orElseThrow().getStoredPath()).isNotNull();
        open(p[1], "OFFER_LETTER").andExpect(status().isNotFound()).andExpect(jsonPath("$.error", containsString("upload it again")));

        upload(p[1], "OFFER_LETTER", PDF).andExpect(status().isOk());
        assertThat(documents.findById(d.getId()).orElseThrow().getStoredPath()).isNull();
        open(p[1], "OFFER_LETTER").andExpect(status().isOk()).andExpect(content().bytes(PDF));
    }

    @Test
    void replacingOrDeletingADocumentStillOnTheDiskClearsItFromTheDisk() throws Exception {
        String[] p = person();
        String replaced = onDisk(p[0], DocumentType.PAN_CARD, true).getStoredPath();
        upload(p[1], "PAN_CARD", PNG).andExpect(status().isOk());
        assertThat(onDisk(null, replaced)).isFalse();
        open(p[1], "PAN_CARD").andExpect(content().bytes(PNG));

        String deleted = onDisk(p[0], DocumentType.PASSPORT_PHOTO, true).getStoredPath();
        mockMvc.perform(delete("/api/auth/me/documents/PASSPORT_PHOTO").header("Authorization", auth(p[1]))).andExpect(status().isNoContent());
        assertThat(onDisk(null, deleted)).isFalse();
    }

    private ResultActions upload(String token, String type, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/auth/me/documents/" + type).file(new MockMultipartFile("file", "doc.pdf", "application/octet-stream", bytes)).header("Authorization", auth(token)));
    }

    private ResultActions open(String token, String type) throws Exception {
        return mockMvc.perform(get("/api/auth/me/documents/" + type).header("Authorization", auth(token)));
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
