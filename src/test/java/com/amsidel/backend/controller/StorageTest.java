/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.EmployeePhoto;
import com.amsidel.backend.entity.StoredFile;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.repository.EmployeePhotoRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.StoredFileRepository;
import com.amsidel.backend.service.StoredFileSync;
import com.amsidel.backend.service.StoredFiles;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The register of stored files: kept at each upload, filtered, opened and cleaned up, each behind its tick. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 1, 2};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private StoredFileSync sync;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private EmployeePhotoRepository photos;
    @Autowired
    private com.amsidel.backend.repository.OfferLetterFileRepository offerFiles;

    private String owner;
    private int dw;
    private int other;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
        other = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"ST\",\"name\":\"Storage Other\"}")), "$.id");
    }

    /** {id, userId, token}: someone of this brand with exactly these ticks. */
    private String[] person(int brand, String... checkpoints) throws Exception {
        String userId = "sto." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand)).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}").andExpect(status().isOk());
        }
        return new String[]{id, userId, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private ResultActions listed(String token, String... params) throws Exception {
        var request = get("/api/storage/files").header("Authorization", auth(token));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions upload(String url, String token, String name, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(url).file(new MockMultipartFile("file", name, "application/octet-stream", bytes)).header("Authorization", auth(token)));
    }

    private StoredFile line(StoredFileCategory category, String id) {
        return files.findByCategoryAndRefId(category, Long.valueOf(id)).orElseThrow();
    }

    @Test
    void anUploadIsRecordedWithItsSizeTypeNameBrandAndWhoUploadedIt() throws Exception {
        String[] p = person(dw);
        upload("/api/auth/me/documents/PAN_CARD", p[2], "my pan.pdf", PDF).andExpect(status().isOk());
        listed(owner, "q", "Person " + seq, "category", "EMPLOYEE_DOCUMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.totalBytes").value(PDF.length))
                .andExpect(jsonPath("$.rows[0].name").value("my pan.pdf")).andExpect(jsonPath("$.rows[0].fileType").value("PDF"))
                .andExpect(jsonPath("$.rows[0].sizeBytes").value(PDF.length)).andExpect(jsonPath("$.rows[0].brandCode").value("DW"))
                .andExpect(jsonPath("$.rows[0].owner", containsString("PAN card"))).andExpect(jsonPath("$.rows[0].uploadedByName").value("Person " + seq))
                .andExpect(jsonPath("$.rows[0].location").value("DATABASE")).andExpect(jsonPath("$.rows[0].deletable").value(true));

        // replacing updates the same line
        upload("/api/auth/me/documents/PAN_CARD", p[2], "pan.png", PNG).andExpect(status().isOk());
        listed(owner, "q", "Person " + seq, "category", "EMPLOYEE_DOCUMENT").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].fileType").value("PNG")).andExpect(jsonPath("$.rows[0].sizeBytes").value(PNG.length));

        // deleting it the normal way removes the line
        mockMvc.perform(delete("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(p[2]))).andExpect(status().isNoContent());
        listed(owner, "q", "Person " + seq, "category", "EMPLOYEE_DOCUMENT").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aFailedUploadLeavesNoLine() throws Exception {
        String[] p = person(dw);
        upload("/api/auth/me/documents/AADHAAR_CARD", p[2], "virus.exe", "MZ not a document".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isBadRequest());
        listed(owner, "q", "Person " + seq).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theFiltersAndTheFigures() throws Exception {
        String[] a = person(dw);
        String nameA = "Person " + seq;
        upload("/api/auth/me/documents/PAN_CARD", a[2], "a.pdf", PDF).andExpect(status().isOk());
        upload("/api/auth/me/photo", a[2], "me.png", PNG).andExpect(status().isOk());
        String[] b = person(other);
        String nameB = "Person " + seq;
        upload("/api/auth/me/documents/PAN_CARD", b[2], "b.pdf", PDF).andExpect(status().isOk());

        listed(owner, "q", nameA).andExpect(jsonPath("$.total").value(2));
        listed(owner, "q", nameA, "category", "PROFILE_PHOTO").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].name").value("me.png"));
        listed(owner, "q", nameA, "fileType", "pdf").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].fileType").value("PDF"));
        listed(owner, "q", nameB, "brandId", String.valueOf(dw)).andExpect(jsonPath("$.total").value(0));
        listed(owner, "q", nameB, "brandId", String.valueOf(other)).andExpect(jsonPath("$.total").value(1));
        // sorted, and in pages
        listed(owner, "q", nameA, "sort", "sizeBytes", "descending", "false", "size", "1").andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.rows[0].sizeBytes").value(PNG.length));

        mockMvc.perform(get("/api/storage/summary").param("refresh", "true").header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.files", greaterThanOrEqualTo(3))).andExpect(jsonPath("$.filesThisMonth", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.byCategory[?(@.key == 'EMPLOYEE_DOCUMENT')].label", hasItem("Employee documents")))
                .andExpect(jsonPath("$.byFileType[?(@.key == 'PDF')].files", hasItem(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.byBrand[?(@.key == 'ST')].files", hasItem(greaterThanOrEqualTo(1))))
                // this test database cannot say how large it is; the limit is still the configured one
                .andExpect(jsonPath("$.database.available").value(false))
                .andExpect(jsonPath("$.database.maxBytes").value(500L * 1024 * 1024))
                .andExpect(jsonPath("$.canDelete").value(true));
    }

    @Test
    void eachActionHasItsTickAndABrandSeesOnlyItsOwn() throws Exception {
        String[] mine = person(dw);
        upload("/api/auth/me/documents/PAN_CARD", mine[2], "mine.pdf", PDF).andExpect(status().isOk());
        String fileId = String.valueOf(line(StoredFileCategory.EMPLOYEE_DOCUMENT, docId(mine[1])).getId());
        String[] theirs = person(other);
        upload("/api/auth/me/documents/PAN_CARD", theirs[2], "theirs.pdf", PDF).andExpect(status().isOk());
        String theirFile = String.valueOf(line(StoredFileCategory.EMPLOYEE_DOCUMENT, docId(theirs[1])).getId());

        // nothing given
        mockMvc.perform(get("/api/storage/summary").header("Authorization", auth(mine[2]))).andExpect(status().isForbidden());
        listed(mine[2]).andExpect(status().isForbidden());
        json(post("/api/storage/files/delete"), mine[2], "{\"ids\":[" + fileId + "]}").andExpect(status().isForbidden());

        // Storage alone: sees its own brand's files and their figures, no database figures, cannot delete
        String reader = person(dw, "9010")[2];
        mockMvc.perform(get("/api/storage/summary").header("Authorization", auth(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false)).andExpect(jsonPath("$.database").doesNotExist()).andExpect(jsonPath("$.byBrand[?(@.key == 'ST')]", hasSize(0)));
        listed(reader, "category", "EMPLOYEE_DOCUMENT").andExpect(jsonPath("$.rows[*].brandCode", everyItem(is("DW"))));
        listed(reader, "brandId", String.valueOf(other)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/storage/files/" + fileId + "/content").header("Authorization", auth(reader))).andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(content().contentType("application/pdf"));
        mockMvc.perform(get("/api/storage/files/" + theirFile + "/content").header("Authorization", auth(reader))).andExpect(status().isForbidden());
        json(post("/api/storage/files/delete"), reader, "{\"ids\":[" + fileId + "]}").andExpect(status().isForbidden());

        // with the delete tick: another brand's is refused, its own goes
        String cleaner = person(dw, "9010", "9011")[2];
        json(post("/api/storage/files/delete"), cleaner, "{\"ids\":[" + theirFile + "," + fileId + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1)).andExpect(jsonPath("$.freedBytes").value(PDF.length)).andExpect(jsonPath("$.notDeleted", hasSize(1)));
        mockMvc.perform(get("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(mine[2]))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/me/documents").header("Authorization", auth(mine[2])))
                .andExpect(jsonPath("$[?(@.type == 'PAN_CARD')].uploaded", contains(false)));
        // and the other brand's is still there
        mockMvc.perform(get("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(theirs[2]))).andExpect(status().isOk());
    }

    /** The employee document's id, read from the list of lines: the line's ref. */
    private String docId(String userId) {
        Long employee = employees.findByUserId(userId).orElseThrow().getId();
        return String.valueOf(files.findAll().stream()
                .filter(f -> f.getCategory() == StoredFileCategory.EMPLOYEE_DOCUMENT && f.getOwner().startsWith(employees.findById(employee).orElseThrow().getName() + " "))
                .findFirst().orElseThrow().getRefId());
    }

    @Test
    void deletingAPhotoClearsItAndTheRecordStays() throws Exception {
        String[] p = person(dw);
        upload("/api/auth/me/photo", p[2], "me.png", PNG).andExpect(status().isOk());
        assertThat(employees.findByUserId(p[1]).orElseThrow().getPhotoUpdatedAt()).isNotNull();
        String id = String.valueOf(line(StoredFileCategory.PROFILE_PHOTO, p[0]).getId());
        json(post("/api/storage/files/delete"), owner, "{\"ids\":[" + id + "]}").andExpect(jsonPath("$.deleted").value(1));
        assertThat(employees.findByUserId(p[1]).orElseThrow().getPhotoUpdatedAt()).isNull();
        assertThat(photos.findById(Long.valueOf(p[0]))).isEmpty();
        assertThat(files.findByCategoryAndRefId(StoredFileCategory.PROFILE_PHOTO, Long.valueOf(p[0]))).isEmpty();
    }

    @Test
    void deletingACheckInPhotoKeepsTheAttendanceEntry() throws Exception {
        String[] p = person(dw);
        byte[] photo = realJpeg();
        String attendanceId = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(multipart("/api/attendance/check-in")
                .file(new MockMultipartFile("photo", "me.jpg", "image/jpeg", photo)).param("workMode", "WFH").param("platform", "web").header("Authorization", auth(p[2])))), "$.id"));
        if (!attendanceId.matches("\\d+")) {
            throw new AssertionError("no check-in: " + attendanceId);
        }
        StoredFile f = line(StoredFileCategory.CHECK_IN_PHOTO, attendanceId);
        assertThat(f.getLocation()).isEqualTo(StoredFile.Location.DISK);
        assertThat(f.getFileType()).isEqualTo("JPG");
        mockMvc.perform(get("/api/storage/files/" + f.getId() + "/content").header("Authorization", auth(owner))).andExpect(status().isOk());

        json(post("/api/storage/files/delete"), owner, "{\"ids\":[" + f.getId() + "]}").andExpect(jsonPath("$.deleted").value(1));
        assertThat(files.findById(f.getId())).isEmpty();
        // still checked in, without the photo
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(p[2]))).andExpect(status().isOk());
    }

    private static byte[] realJpeg() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void anIssuedOfferLetterGoesWholeOnlyWithItsOwnTickAndInItsBrand() throws Exception {
        String candidate = "Olive Offer";
        String letter = """
                {"brandId":%d,"issueDate":"2026-11-04","companyName":"Amsidel","companyAddress":"Kochi","companyIntro":"We make doors.","candidateName":"%s",
                 "candidateAddress":"Kochi","designation":"Engineer","joiningDate":"2026-11-16","startDate":"2026-11-16","weeklyHours":48,"annualPackage":600000,
                 "lines":[{"name":"Basic","annualAmount":600000}],"contactNumber":"9847012345","contactEmail":"hr@amsidel.com","clauses":"Report on time.",
                 "issuedByName":"Hema","issuedByDesignation":"HR"}
                """.formatted(dw, candidate);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/offer-letters"), owner, letter).andExpect(status().isCreated())), "$.id"));
        json(post("/api/offer-letters/" + id + "/issue"), owner, "{}").andExpect(status().isOk());
        StoredFile f = line(StoredFileCategory.OFFER_LETTER, id);
        String ids = "{\"ids\":[" + f.getId() + "]}";

        // Delete stored files alone: shown as locked, and refused if tried anyway
        String cleaner = person(dw, "9010", "9011")[2];
        listed(cleaner, "q", candidate).andExpect(jsonPath("$.rows[0].deletable").value(false));
        json(post("/api/storage/files/delete"), cleaner, ids).andExpect(jsonPath("$.deleted").value(0)).andExpect(jsonPath("$.notDeleted", hasSize(1)));
        mockMvc.perform(get("/api/offer-letters/" + id).header("Authorization", auth(owner))).andExpect(status().isOk());
        assertThat(files.findById(f.getId())).isPresent();

        // both ticks, but another brand
        json(post("/api/storage/files/delete"), person(other, "9010", "9011", "4006")[2], ids).andExpect(jsonPath("$.deleted").value(0));
        assertThat(files.findById(f.getId())).isPresent();

        // both ticks, its own brand: the letter, its PDF and its line all go
        String full = person(dw, "9010", "9011", "4006")[2];
        listed(full, "q", candidate).andExpect(jsonPath("$.rows[0].deletable").value(true));
        json(post("/api/storage/files/delete"), full, ids).andExpect(jsonPath("$.deleted").value(1)).andExpect(jsonPath("$.freedBytes").value((int) f.getSizeBytes()));
        mockMvc.perform(get("/api/offer-letters/" + id).header("Authorization", auth(owner))).andExpect(status().isNotFound());
        assertThat(offerFiles.findById(Long.valueOf(id))).isEmpty();
        assertThat(files.findById(f.getId())).isEmpty();
    }

    @Test
    void theSyncAddsFilesFromBeforeAndRemovesLinesOfFilesGoneAnotherWay() throws Exception {
        String[] p = person(dw);
        // a photo saved before uploads were recorded
        EmployeePhoto photo = new EmployeePhoto();
        photo.setEmployeeId(Long.valueOf(p[0]));
        photo.setContent(JPEG);
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(JPEG.length);
        photo.setUpdatedAt(Instant.now());
        photos.save(photo);
        assertThat(files.findByCategoryAndRefId(StoredFileCategory.PROFILE_PHOTO, Long.valueOf(p[0]))).isEmpty();

        assertThat(sync.sync().added()).isGreaterThanOrEqualTo(1);
        StoredFile added = line(StoredFileCategory.PROFILE_PHOTO, p[0]);
        assertThat(added.getSizeBytes()).isEqualTo(JPEG.length);
        assertThat(added.getFileType()).isEqualTo("JPG");
        assertThat(added.getOwner()).contains("Person");
        assertThat(added.getBrand().getId()).isEqualTo((long) dw);
        // nothing more to do the second time
        assertThat(sync.sync().added()).isZero();

        // removed without telling the register
        photos.deleteById(Long.valueOf(p[0]));
        assertThat(sync.sync().removed()).isGreaterThanOrEqualTo(1);
        assertThat(files.findByCategoryAndRefId(StoredFileCategory.PROFILE_PHOTO, Long.valueOf(p[0]))).isEmpty();
    }

    @Test
    void theFileTypeComesFromTheContentType() {
        assertThat(StoredFiles.fileType("image/jpeg")).isEqualTo("JPG");
        assertThat(StoredFiles.fileType("image/png")).isEqualTo("PNG");
        assertThat(StoredFiles.fileType("image/webp")).isEqualTo("WEBP");
        assertThat(StoredFiles.fileType("application/pdf")).isEqualTo("PDF");
        assertThat(StoredFiles.fileType("application/vnd.ms-excel")).isEqualTo("EXCEL");
        assertThat(StoredFiles.fileType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isEqualTo("EXCEL");
        assertThat(StoredFiles.fileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isEqualTo("WORD");
        assertThat(StoredFiles.fileType(null)).isEqualTo("OTHER");
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(content));
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
