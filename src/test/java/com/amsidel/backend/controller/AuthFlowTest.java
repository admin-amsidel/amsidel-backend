package com.amsidel.backend.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end login flows against an in-memory database. Starts from the owner account that
 * DataBootstrap creates (owner / Ams@2026, must change PIN).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFlowTest {

    /** Enough of each format's header for the type check; the server never decodes the image. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private MockMvc mockMvc;

    private String ownerToken;
    private long dwBrandId;

    @BeforeAll
    void ownerReplacesFirstPin() throws Exception {
        String firstToken = token(login("owner", "Ams@2026")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("asd"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true)));
        ownerToken = token(changePin(firstToken, "Ams@2026", "Own#2026").andExpect(status().isOk()));

        String brands = mockMvc.perform(get("/api/brands").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(brands, "$[?(@.code == 'DW')].id");
        dwBrandId = ids.get(0);
    }

    @Test
    void wrongPinAndUnknownUserGiveTheSameAnswer() throws Exception {
        login("owner", "Bad@999").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Wrong user ID or PIN"));
        login("nobody", "Bad@999").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Wrong user ID or PIN"));
    }

    @Test
    void apiNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/employees").header("Authorization", "asd not-a-token"))
                .andExpect(status().isUnauthorized());
        // a valid token under the standard "Bearer" scheme is not accepted
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk());
    }

    @Test
    void productsArePublicToReadButOnlyAdminsChangeThem() throws Exception {
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
        String product = "{\"name\":\"Test window\"}";
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/products").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isCreated());
    }

    @Test
    void newEmployeeMustReplaceTemporaryPinBeforeUsingThePortal() throws Exception {
        createEmployee(ownerToken, "Anu Thomas", "anu", "ab@123", "EMPLOYEE", dwBrandId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empNumber").value(startsWith("DW")))
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // user IDs are not case sensitive
        String temp = token(login("ANU", "ab@123").andExpect(status().isOk()));
        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(temp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Anu Thomas"))
                .andExpect(jsonPath("$.brandCode").value("DW"));
        mockMvc.perform(get("/api/brands").header("Authorization", auth(temp)))
                .andExpect(status().isForbidden());

        changePin(temp, "ab@123", "abc123").andExpect(status().isBadRequest());
        changePin(temp, "wrong@1", "xy#789").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Current PIN is wrong"));

        Thread.sleep(1100); // tokens have one-second precision
        String fresh = token(changePin(temp, "ab@123", "xy#789").andExpect(status().isOk()));
        mockMvc.perform(get("/api/brands").header("Authorization", auth(fresh))).andExpect(status().isOk());
        // the token from before the change no longer works
        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(temp)))
                .andExpect(status().isUnauthorized());
        // an employee cannot manage other employees
        mockMvc.perform(get("/api/employees").header("Authorization", auth(fresh)))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountLocksAfterFiveWrongPinsUntilAnAdminResetsIt() throws Exception {
        String id = idOf(createEmployee(ownerToken, "Biju K", "biju", "cd@456", "EMPLOYEE", dwBrandId)
                .andExpect(status().isCreated()));

        for (int i = 0; i < 4; i++) {
            login("biju", "zz@000").andExpect(jsonPath("$.error").value("Wrong user ID or PIN"));
        }
        login("biju", "zz@000").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(startsWith("Too many wrong attempts")));
        // even the right PIN is refused while locked
        login("biju", "cd@456").andExpect(jsonPath("$.error").value(startsWith("Too many wrong attempts")));

        mockMvc.perform(post("/api/employees/" + id + "/reset-password").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"ef@789\"}"))
                .andExpect(status().isNoContent());
        login("biju", "ef@789").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    }

    @Test
    void disablingAnAccountEndsItsSession() throws Exception {
        String id = idOf(createEmployee(ownerToken, "Celin M", "celin", "gh@123", "EMPLOYEE", dwBrandId)
                .andExpect(status().isCreated()));
        String token = token(login("celin", "gh@123"));
        // in use (so remembered by SignedInCache) before the account is disabled
        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(token))).andExpect(status().isOk());

        mockMvc.perform(put("/api/employees/" + id).header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("Celin M", "celin", null, "EMPLOYEE", dwBrandId, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(token)))
                .andExpect(status().isUnauthorized());
        login("celin", "gh@123").andExpect(status().isUnauthorized());
    }

    @Test
    void brandAdminOnlyManagesStaffOfTheirOwnBrand() throws Exception {
        String other = mockMvc.perform(post("/api/brands").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"hx\",\"name\":\"Amsidel Homes\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("HX"))
                .andReturn().getResponse().getContentAsString();
        long otherBrandId = ((Number) JsonPath.read(other, "$.id")).longValue();

        createEmployee(ownerToken, "Deepa Admin", "deepa", "ij@123", "ADMIN", dwBrandId)
                .andExpect(status().isCreated());
        String admin = token(changePin(token(login("deepa", "ij@123")), "ij@123", "kl@456"));

        createEmployee(admin, "Hari", "hari", "mn@123", "EMPLOYEE", otherBrandId).andExpect(status().isForbidden());
        createEmployee(admin, "Second Admin", "admin2", "mn@123", "ADMIN", dwBrandId).andExpect(status().isForbidden());
        createEmployee(admin, "Jose", "jose", "mn@123", "EMPLOYEE", dwBrandId).andExpect(status().isCreated());
        createEmployee(admin, "Jose Again", "JOSE", "mn@123", "EMPLOYEE", dwBrandId).andExpect(status().isConflict());

        mockMvc.perform(get("/api/employees").header("Authorization", auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].brandCode", everyItem(is("DW"))));
        mockMvc.perform(get("/api/employees?brandId=" + otherBrandId).header("Authorization", auth(admin)))
                .andExpect(status().isForbidden());
        // only owners add brands
        mockMvc.perform(post("/api/brands").header("Authorization", auth(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ZZ\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyoneChangesTheirOwnPhotoAndAdminsChangeTheirStaffs() throws Exception {
        String id = idOf(createEmployee(ownerToken, "Esha P", "esha", "op@123", "EMPLOYEE", dwBrandId)
                .andExpect(status().isCreated()));
        String esha = token(changePin(token(login("esha", "op@123")), "op@123", "qr@456"));

        // no photo yet
        mockMvc.perform(get("/api/auth/me/photo").header("Authorization", auth(esha)))
                .andExpect(status().isNotFound());

        MockMultipartFile png = new MockMultipartFile("file", "me.png", "image/png", PNG);
        mockMvc.perform(multipart("/api/auth/me/photo").file(png).header("Authorization", auth(esha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUpdatedAt").isNotEmpty());
        mockMvc.perform(get("/api/auth/me/photo").header("Authorization", auth(esha)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));

        // a text file renamed to .jpg is refused
        MockMultipartFile fake = new MockMultipartFile("file", "me.jpg", "image/jpeg", "not a photo".getBytes());
        mockMvc.perform(multipart("/api/auth/me/photo").file(fake).header("Authorization", auth(esha)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Photo must be a JPEG, PNG or WebP image"));

        // an employee cannot reach other people's photos; the owner can see and replace them
        mockMvc.perform(get("/api/employees/" + id + "/photo").header("Authorization", auth(esha)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/" + id + "/photo").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk());
        MockMultipartFile jpeg = new MockMultipartFile("file", "esha.jpg", "image/jpeg", JPEG);
        mockMvc.perform(multipart("/api/employees/" + id + "/photo").file(jpeg)
                        .header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me/photo").header("Authorization", auth(esha)))
                .andExpect(content().contentType("image/jpeg"));

        mockMvc.perform(delete("/api/auth/me/photo").header("Authorization", auth(esha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUpdatedAt").isEmpty());
        mockMvc.perform(get("/api/auth/me/photo").header("Authorization", auth(esha)))
                .andExpect(status().isNotFound());
    }

    @Test
    void designationsAndReportingLines() throws Exception {
        String list = mockMvc.perform(get("/api/designations").header("Authorization", auth(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].name").value("Trainee"))
                .andExpect(jsonPath("$[5].levelCode").value("6.1"))
                .andExpect(jsonPath("$[6].levelCode").value("6.2"))
                .andExpect(jsonPath("$[8].name").value("Admin"))
                .andExpect(jsonPath("$[9].levelCode").value("8.2"))
                .andExpect(jsonPath("$[11].name").value("CEO"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> lead = JsonPath.read(list, "$[?(@.name == 'Team Lead')].id");
        List<Integer> member = JsonPath.read(list, "$[?(@.name == 'Team Member')].id");

        String leadId = idOf(createEmployee(ownerToken, "Gopal Lead", "gopal", "st@123", "EMPLOYEE", dwBrandId)
                .andExpect(status().isCreated()));
        String gopal = """
                {"name":"Gopal Lead","userId":"gopal","role":"EMPLOYEE","brandId":%d,"designationId":%d,
                 "phone":"+91 98470 00000","joinedDate":"2026-10-01"}
                """.formatted(dwBrandId, lead.get(0));
        mockMvc.perform(put("/api/employees/" + leadId).header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(gopal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.designationName").value("Team Lead"))
                .andExpect(jsonPath("$.levelCode").value("4"));

        String hema = """
                {"name":"Hema Member","userId":"hema","password":"uv@123","role":"EMPLOYEE","brandId":%d,
                 "designationId":%d,"reportsToId":%s,"phone":"+91 98470 00000","joinedDate":"2026-10-01"}
                """.formatted(dwBrandId, member.get(0), leadId);
        String hemaId = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(hema))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportsToName").value("Gopal Lead"))
                .andExpect(jsonPath("$.levelNumber").value(3)));

        // Gopal cannot report to Hema, who reports to Gopal
        String loop = gopal.replace("\"designationId\"", "\"reportsToId\":" + hemaId + ",\"designationId\"");
        mockMvc.perform(put("/api/employees/" + leadId).header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(loop))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("loop")));

        // only owners change the ladder
        String newLevel = "{\"name\":\"Senior Associate\",\"levelNumber\":2,\"levelCode\":\"2.1\"}";
        String hemaToken = token(changePin(token(login("hema", "uv@123")), "uv@123", "wx@456"));
        mockMvc.perform(post("/api/designations").header("Authorization", auth(hemaToken))
                        .contentType(MediaType.APPLICATION_JSON).content(newLevel))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/designations").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(newLevel))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/designations").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Odd\",\"levelNumber\":3,\"levelCode\":\"4.5\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEmployeeDetailsAreRejectedWithFieldMessages() throws Exception {
        String body = """
                {"name":"","userId":"bad id","password":"weak","role":"EMPLOYEE","brandId":%d,
                 "phone":"12","joinedDate":"2026-10-01","bloodGroup":"C+","photoPath":"../../etc/passwd",
                 "personalEmail":"not-an-email"}
                """.formatted(dwBrandId);
        mockMvc.perform(post("/api/employees").header("Authorization", auth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.userId").exists())
                .andExpect(jsonPath("$.details.password").exists())
                .andExpect(jsonPath("$.details.phone").exists())
                .andExpect(jsonPath("$.details.bloodGroup").exists())
                .andExpect(jsonPath("$.details.photoPath").exists())
                .andExpect(jsonPath("$.details.personalEmail").exists());
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private ResultActions createEmployee(String token, String name, String userId, String pin, String role,
                                         long brandId) throws Exception {
        return mockMvc.perform(post("/api/employees").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(employeeJson(name, userId, pin, role, brandId, true)));
    }

    private static String employeeJson(String name, String userId, String pin, String role, long brandId,
                                       boolean active) {
        return """
                {"name":"%s","userId":"%s",%s"role":"%s","brandId":%d,"phone":"+91 98470 00000",
                 "joinedDate":"2026-10-01","dob":"1995-05-20","bloodGroup":"O+",
                 "photoPath":"users/%s.jpg","companyEmail":"%s@amsidel.com","active":%s}
                """.formatted(name, userId, pin == null ? "" : "\"password\":\"" + pin + "\",", role, brandId,
                userId, userId, active);
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.token");
    }

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
