package com.amsidel.backend.controller;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Check-in / check-out: a Team Lead with two people below her, and an unrelated colleague. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=attendance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    @Value("${app.attendance.photo-dir}")
    private String photoDir;

    private String owner;
    private String lead;
    private String ravi;
    private String anu;
    private String other;
    private String raviId;
    private String anuId;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        // photos from an earlier run must not make the file checks below pass
        org.springframework.util.FileSystemUtils.deleteRecursively(Path.of(photoDir));
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        String leadId = create("Lakshmi Lead", "at.lead", "MANAGER", null);
        raviId = create("Ravi Field", "at.ravi", "EMPLOYEE", leadId);
        anuId = create("Anu Home", "at.anu", "EMPLOYEE", leadId);
        create("Olivia Other", "at.other", "EMPLOYEE", null);
        lead = signIn("at.lead");
        ravi = signIn("at.ravi");
        anu = signIn("at.anu");
        other = signIn("at.other");
    }

    @Test
    void checkInOncePerDayWithServerTimeAndACleanPhoto() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        String json = body(checkIn(ravi, photoWithExif(), "FIELD", "10.0159", "76.3419", "18")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.workMode").value("FIELD"))
                .andExpect(jsonPath("$.checkIn.latitude").value(10.0159))
                .andExpect(jsonPath("$.checkIn.flags", empty()))
                .andExpect(jsonPath("$.checkIn.ip").value("203.0.113.9"))
                .andExpect(jsonPath("$.device.platform").value("web"))
                .andExpect(jsonPath("$.checkOut").doesNotExist()));
        Instant at = Instant.parse(JsonPath.read(json, "$.checkIn.at"));
        assertThat(at).isBetween(before, Instant.now().plusSeconds(1));

        // saved at attendance/{yyyy}/{MM}/{employeeId}/{dd}.jpg as a JPEG without the camera's metadata
        Path file = Path.of(photoDir, "attendance", "%04d".formatted(TODAY.getYear()), "%02d".formatted(TODAY.getMonthValue()),
                raviId, "%02d.jpg".formatted(TODAY.getDayOfMonth()));
        byte[] saved = Files.readAllBytes(file);
        assertThat(saved[0] & 0xFF).isEqualTo(0xFF);
        assertThat(saved[1] & 0xFF).isEqualTo(0xD8);
        String text = new String(saved, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif").doesNotContain("GPS-SECRET");

        // the visit is still open: no second entry until it is closed
        checkIn(ravi, photo("png"), "FIELD", "10.0159", "76.3419", "18")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Close your visit to Kumar residence (since")));
    }

    @Test
    void aFieldDayHasOneEntryPerVisit() throws Exception {
        create("Fiona Field", "at.fiona", "EMPLOYEE", null);
        String fiona = signIn("at.fiona");
        checkIn(fiona, photo("png"), "FIELD", "10.01", "76.34", "15").andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryNo").value(1))
                .andExpect(jsonPath("$.place").value("Kumar residence"));
        postJson(fiona, "/api/attendance/check-out", "{\"latitude\":10.01,\"longitude\":76.34,\"accuracy\":15,\"note\":\"Measured 14 windows\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Measured 14 windows"));
        // a field visit needs the place
        checkIn(fiona, photo("png"), "FIELD", "10.05", "76.31", "20", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter the place or client you are visiting"));
        String second = idOf(checkIn(fiona, photo("png"), "FIELD", "10.05", "76.31", "20", "Lulu Mall site")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryNo").value(2)));
        // office / work from home only as the day's first entry
        checkIn(fiona, photo("png"), "OFFICE", "10", "76", "10").andExpect(status().isConflict());
        postJson(fiona, "/api/attendance/check-out", "{\"note\":\"Order confirmed\"}").andExpect(status().isOk());
        checkIn(fiona, photo("png"), "OFFICE", "10", "76", "10").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.endsWith("Only field visits can be added after that.")));

        // the second visit's photo is {dd}-2.jpg
        String fionaId = JsonPath.read(body(mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(fiona)))),
                "$.day.employeeId").toString();
        assertThat(Path.of(photoDir, "attendance", "%04d".formatted(TODAY.getYear()), "%02d".formatted(TODAY.getMonthValue()),
                fionaId, "%02d-2.jpg".formatted(TODAY.getDayOfMonth()))).exists();
        mockMvc.perform(get("/api/attendance/" + second + "/photo").header("Authorization", auth(fiona))).andExpect(status().isOk());

        // one day, two entries, consolidated
        mockMvc.perform(get("/api/attendance/me").header("Authorization", auth(fiona)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].entries.length()").value(2))
                .andExpect(jsonPath("$[0].entries[*].place", contains("Kumar residence", "Lulu Mall site")))
                .andExpect(jsonPath("$[0].open").value(false))
                .andExpect(jsonPath("$[0].lastOut").exists())
                .andExpect(jsonPath("$[0].modes", contains("FIELD")));
        // nothing open any more
        postJson(fiona, "/api/attendance/check-out", "{}").andExpect(status().isConflict());
    }

    @Test
    void anOfficeDayCanBeFollowedByFieldVisits() throws Exception {
        create("Omar Office", "at.omar", "EMPLOYEE", null);
        String omar = signIn("at.omar");
        checkIn(omar, photo("png"), "OFFICE", "10", "76", "10").andExpect(status().isCreated());
        checkIn(omar, photo("png"), "FIELD", "10", "76", "10").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("You have already checked in today at")));
        postJson(omar, "/api/attendance/check-out", "{}").andExpect(status().isOk());
        checkIn(omar, photo("png"), "FIELD", "10", "76", "10").andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryNo").value(2));
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(omar)))
                .andExpect(jsonPath("$.day.open").value(true))
                .andExpect(jsonPath("$.day.lastOut").doesNotExist())
                .andExpect(jsonPath("$.day.modes", contains("OFFICE", "FIELD")));
    }

    @Test
    void locationProblemsAreFlaggedNotRefused() throws Exception {
        checkIn(anu, photo("png"), "WFH", null, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkIn.latitude").doesNotExist())
                .andExpect(jsonPath("$.checkIn.flags", contains("LOCATION_MISSING")));
        // checking out far from a good fix
        postJson(anu, "/api/attendance/check-out", "{\"latitude\":9.98,\"longitude\":76.28,\"accuracy\":850}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkOut.flags", contains("LOW_ACCURACY")))
                .andExpect(jsonPath("$.workedMinutes").value(0));
        postJson(anu, "/api/attendance/check-out", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("You already ended work today at")));
    }

    @Test
    void onlyRealPhotosAreAccepted() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("photo", "selfie.jpg", "image/jpeg", "not a picture".getBytes());
        checkIn(other, fake, "OFFICE", "10", "76", "10").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The photo must be a JPEG or PNG image"));
        checkIn(other, photo("gif"), "OFFICE", "10", "76", "10").andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/attendance/check-in").param("workMode", "OFFICE").header("Authorization", auth(other)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Take a photo to check in"));
        checkIn(other, photo("png"), null, "10", "76", "10").andExpect(status().isBadRequest());
        // nothing was saved by the refused attempts
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(other)))
                .andExpect(jsonPath("$.day").doesNotExist());
        postJson(other, "/api/attendance/check-out", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You have not checked in today"));
    }

    @Test
    void managersSeeTheirTeamsDaysAndPhotos() throws Exception {
        String lead2Id = create("Lead Two", "at.lead2", "MANAGER", null);
        String lead2 = signIn("at.lead2");
        create("Team Mate", "at.mate", "EMPLOYEE", lead2Id);
        String mate = signIn("at.mate");
        String id = idOf(checkIn(mate, photo("png"), "OFFICE", "10", "76", "15").andExpect(status().isCreated()));

        // the person and their lead see the photo; an unrelated colleague does not
        mockMvc.perform(get("/api/attendance/" + id + "/photo").header("Authorization", auth(mate)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attendance/" + id + "/photo").header("Authorization", auth(lead2)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(get("/api/attendance/" + id + "/photo").header("Authorization", auth(other)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/attendance/" + id + "/photo").header("Authorization", auth(owner)))
                .andExpect(status().isOk());

        // day view: everyone below the lead, checked in or not
        mockMvc.perform(get("/api/attendance/day").param("date", TODAY.toString()).header("Authorization", auth(lead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].name", hasItem("Ravi Field")))
                .andExpect(jsonPath("$.people[*].name", hasItem("Anu Home")))
                .andExpect(jsonPath("$.people[*].name", not(hasItem("Olivia Other"))));
        // employee-days view
        mockMvc.perform(get("/api/attendance/employees/" + raviId).header("Authorization", auth(lead)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attendance/employees/" + raviId).header("Authorization", auth(other)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/attendance/employees/" + anuId)
                        .param("from", TODAY.minusDays(100).toString()).param("to", TODAY.toString())
                        .header("Authorization", auth(lead)))
                .andExpect(status().isBadRequest());
        // employees do not have the Team attendance menu (default menu access)
        mockMvc.perform(get("/api/attendance/day").header("Authorization", auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void attendanceExportsToExcel() throws Exception {
        String leadId = create("Export Lead", "at.xlead", "MANAGER", null);
        String xlead = signIn("at.xlead");
        create("Esha Export", "at.esha", "EMPLOYEE", leadId);
        String esha = signIn("at.esha");
        checkIn(esha, photo("png"), "OFFICE", "10", "76", "15").andExpect(status().isCreated());

        // the lead: themselves and their team, the last week
        byte[] file = mockMvc.perform(get("/api/attendance/export").header("Authorization", auth(xlead))
                        .param("all", "true").param("from", TODAY.minusDays(6).toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Attendance_Team_")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxRead.sheets(file)).containsExactly("Summary", "Daily", "Entries");
        List<String> summary = XlsxRead.rowWith(file, "Summary", "Esha Export");
        assertThat(summary.get(5)).isEqualTo("1"); // present days
        assertThat(XlsxRead.rowWith(file, "Summary", "Export Lead").get(5)).isEqualTo("0");
        String today = TODAY.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH));
        assertThat(XlsxRead.rowWith(file, "Daily", "Esha Export", today)).contains("Present (not checked out)", "Office");
        assertThat(XlsxRead.rows(file, "Daily").stream().filter(r -> r.contains("Esha Export")).count()).isEqualTo(7);
        assertThat(XlsxRead.rowWith(file, "Entries", "Esha Export", today)).contains("Office", "10.00000, 76.00000");

        // one person, by someone above them; not by anyone else, and at most a year
        mockMvc.perform(get("/api/attendance/export").header("Authorization", auth(other)).param("employeeId", raviId)
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/attendance/export").header("Authorization", auth(lead)).param("employeeId", raviId)
                        .param("from", TODAY.minusDays(400).toString()).param("to", TODAY.toString()))
                .andExpect(status().isBadRequest());
        byte[] mine = mockMvc.perform(get("/api/attendance/export").header("Authorization", auth(esha))
                        .param("from", TODAY.minusDays(300).toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxRead.rows(mine, "Summary").stream().filter(r -> r.contains("Esha Export")).count()).isEqualTo(1);
    }

    // ---- helpers ----

    /** Field visits go to "Kumar residence". */
    private ResultActions checkIn(String token, MockMultipartFile photo, String mode, String lat, String lng, String accuracy)
            throws Exception {
        return checkIn(token, photo, mode, lat, lng, accuracy, "FIELD".equals(mode) ? "Kumar residence" : null);
    }

    private ResultActions checkIn(String token, MockMultipartFile photo, String mode, String lat, String lng, String accuracy,
                                  String place) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/attendance/check-in");
        request.file(photo);
        if (mode != null) request.param("workMode", mode);
        if (place != null) request.param("place", place);
        if (lat != null) request.param("latitude", lat);
        if (lng != null) request.param("longitude", lng);
        if (accuracy != null) request.param("accuracy", accuracy);
        request.param("platform", "web").param("osVersion", "Android 14").param("appVersion", "1.0.0");
        return mockMvc.perform(request.header("Authorization", auth(token)).header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"));
    }

    private ResultActions postJson(String token, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static MockMultipartFile photo(String format) throws Exception {
        return new MockMultipartFile("photo", "capture." + format, "image/" + format, image(format));
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xB08D57));
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** A JPEG carrying an EXIF block (as phone cameras write, with the place it was taken). */
    private static MockMultipartFile photoWithExif() throws Exception {
        byte[] jpeg = image("jpg");
        byte[] exif = "Exif\0\0GPS-SECRET 10.0159N 76.3419E".getBytes(StandardCharsets.ISO_8859_1);
        int len = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return new MockMultipartFile("photo", "capture.jpg", "image/jpeg", out.toByteArray());
    }

    private String create(String name, String userId, String role, String reportsTo) throws Exception {
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"reportsToId":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, dw, reportsTo);
        return idOf(mockMvc.perform(post("/api/employees")
                .header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()));
    }

    private String signIn(String userId) throws Exception {
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password")
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
