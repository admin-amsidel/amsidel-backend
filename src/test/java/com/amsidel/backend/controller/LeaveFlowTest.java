package com.amsidel.backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Leave rules end to end on an in-memory database. Dates are in 2027 so the tests do not depend on today:
 * 2027-01-04 is a Monday, 2027-01-10 a Sunday. Each test uses its own employee so balances do not mix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// own Spring context = own in-memory database, starting from the bootstrap owner like AuthFlowTest
@TestPropertySource(properties = "test.context=leave")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaveFlowTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private long dwBrandId;
    private String manager;
    private Map<String, Integer> typeIds;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dwBrandId = ids.get(0);
        manager = person("MANAGER")[1];

        String types = body(mockMvc.perform(get("/api/leave/types").header("Authorization", auth(owner))));
        List<String> codes = JsonPath.read(types, "$[*].code");
        List<Integer> typeIdList = JsonPath.read(types, "$[*].id");
        typeIds = new java.util.HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            typeIds.put(codes.get(i), typeIdList.get(i));
        }
    }

    @Test
    void standardLeaveTypesAreCreated() {
        assertEquals(List.of("CL", "SL", "EL", "CO", "PL", "RH", "LWP").stream().sorted().toList(),
                typeIds.keySet().stream().sorted().toList());
    }

    @Test
    void employeeAppliesAndManagerApproves() throws Exception {
        String[] emp = person("EMPLOYEE");
        balance(emp[1], "CL", "available", 10.0);

        String id = idOf(apply(emp[1], "CL", "2027-01-04", "2027-01-05", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(2.0))
                .andExpect(jsonPath("$.status").value("PENDING")));
        balance(emp[1], "CL", "pending", 2.0);
        balance(emp[1], "CL", "available", 8.0);

        mockMvc.perform(get("/api/leave/requests?status=PENDING").header("Authorization", auth(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(Integer.valueOf(id))));

        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        balance(emp[1], "CL", "used", 2.0);
        balance(emp[1], "CL", "available", 8.0);
    }

    @Test
    void sundaysAreNotCountedAndBalanceCannotBeExceeded() throws Exception {
        String[] emp = person("EMPLOYEE");
        // Saturday to Monday = 2 working days
        apply(emp[1], "CL", "2027-01-09", "2027-01-11", false).andExpect(jsonPath("$.days").value(2.0));
        apply(emp[1], "CL", "2027-01-10", "2027-01-10", false).andExpect(status().isBadRequest());
        // two weeks Monday to Saturday = 12 working days, more than the 10 a year
        apply(emp[1], "SL", "2027-02-01", "2027-02-13", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("Not enough Sick Leave")));
        // overlapping the first request
        apply(emp[1], "SL", "2027-01-11", "2027-01-12", false).andExpect(status().isConflict());
    }

    @Test
    void halfDaysOnlyWhereAllowed() throws Exception {
        String[] emp = person("EMPLOYEE");
        apply(emp[1], "CL", "2027-04-05", "2027-04-05", true).andExpect(jsonPath("$.days").value(0.5));
        apply(emp[1], "EL", "2027-04-06", "2027-04-06", true).andExpect(status().isBadRequest());
        apply(emp[1], "CL", "2027-04-07", "2027-04-08", true).andExpect(status().isBadRequest());
    }

    @Test
    void compOffNeedsAGrantFromTheManager() throws Exception {
        String[] emp = person("EMPLOYEE");
        apply(emp[1], "CO", "2027-03-08", "2027-03-08", false).andExpect(status().isBadRequest());

        String grant = """
                {"employeeId":%s,"leaveTypeId":%d,"days":1,"workedOn":"2027-01-10","reason":"Worked Sunday stock count"}
                """.formatted(emp[0], typeIds.get("CO"));
        String[] other = person("EMPLOYEE");
        mockMvc.perform(post("/api/leave/grants").header("Authorization", auth(other[1]))
                        .contentType(MediaType.APPLICATION_JSON).content(grant))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/leave/grants").header("Authorization", auth(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(grant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.year").value(2027));

        // the manager's team lists the employee, not other managers
        mockMvc.perform(get("/api/leave/team").header("Authorization", auth(manager)))
                .andExpect(jsonPath("$[*].id", hasItem(Integer.valueOf(emp[0]))))
                .andExpect(jsonPath("$[*].role", not(hasItem("MANAGER"))));
        balance(emp[1], "CO", "available", 1.0);
        apply(emp[1], "CO", "2027-03-08", "2027-03-08", false).andExpect(status().isCreated());
        mockMvc.perform(get("/api/leave/me/grants?year=2027").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$[0].reason").value("Worked Sunday stock count"));
    }

    @Test
    void restrictedHolidayOnlyOnListedDates() throws Exception {
        String[] emp = person("EMPLOYEE");
        apply(emp[1], "RH", "2027-03-22", "2027-03-22", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("2027-03-22 is not in the restricted holiday list"));

        String holiday = "{\"date\":\"2027-03-22\",\"name\":\"Holi\"}";
        mockMvc.perform(post("/api/leave/restricted-holidays").header("Authorization", auth(emp[1]))
                        .contentType(MediaType.APPLICATION_JSON).content(holiday))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/leave/restricted-holidays").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(holiday))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/leave/restricted-holidays?year=2027").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$[*].name", hasItem("Holi")));

        apply(emp[1], "RH", "2027-03-22", "2027-03-23", false).andExpect(status().isBadRequest());
        apply(emp[1], "RH", "2027-03-22", "2027-03-22", false).andExpect(status().isCreated());
        balance(emp[1], "RH", "available", 4.0);
    }

    @Test
    void officeHolidaysAreNotCountedAsLeave() throws Exception {
        String[] emp = person("EMPLOYEE");
        String christmas = "{\"date\":\"2027-12-25\",\"name\":\"Christmas\"}";
        mockMvc.perform(post("/api/leave/office-holidays").header("Authorization", auth(emp[1]))
                        .contentType(MediaType.APPLICATION_JSON).content(christmas))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/leave/office-holidays").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(christmas))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/leave/office-holidays").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(christmas))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/leave/office-holidays?year=2027").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Christmas")));

        // Fri 24 to Mon 27 Dec: Christmas (Sat 25) and Sunday 26 are not leave days
        apply(emp[1], "CL", "2027-12-24", "2027-12-27", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(2.0));
        apply(emp[1], "SL", "2027-12-25", "2027-12-25", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The chosen dates are all weekly offs or office holidays; no leave is needed"));
        apply(emp[1], "SL", "2027-12-25", "2027-12-25", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("2027-12-25 is an office holiday; no leave is needed"));

        // the work log calendar gets it too
        mockMvc.perform(get("/api/worklog?from=2027-12-01&to=2027-12-31").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidays[0].date").value("2027-12-25"))
                .andExpect(jsonPath("$.holidays[0].name").value("Christmas"));
    }

    @Test
    void leaveWithoutPayNeedsNoBalance() throws Exception {
        String[] emp = person("EMPLOYEE");
        apply(emp[1], "LWP", "2027-05-03", "2027-05-29", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(24.0));
    }

    @Test
    void nobodyDecidesTheirOwnLeave() throws Exception {
        String[] emp = person("EMPLOYEE");
        mockMvc.perform(get("/api/leave/requests").header("Authorization", auth(emp[1])))
                .andExpect(status().isForbidden());

        String[] mgr = person("MANAGER");
        String id = idOf(apply(mgr[1], "CL", "2027-06-07", "2027-06-07", false).andExpect(status().isCreated()));
        // managers decide for employees only, never for themselves or other managers
        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(mgr[1])))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leave/requests?status=PENDING").header("Authorization", auth(manager)))
                .andExpect(jsonPath("$[*].id", not(hasItem(Integer.valueOf(id)))));
        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(owner)))
                .andExpect(status().isOk());

        // an owner's own leave is approved straight away
        apply(owner, "CL", "2027-06-08", "2027-06-08", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void rejectNeedsAReasonAndPendingLeaveCanBeCancelled() throws Exception {
        String[] emp = person("EMPLOYEE");
        String first = idOf(apply(emp[1], "CL", "2027-07-05", "2027-07-05", false));
        mockMvc.perform(post("/api/leave/requests/" + first + "/reject").header("Authorization", auth(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/leave/requests/" + first + "/reject").header("Authorization", auth(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Stock audit that day\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        String second = idOf(apply(emp[1], "CL", "2027-07-06", "2027-07-06", false));
        mockMvc.perform(post("/api/leave/me/requests/" + second + "/cancel").header("Authorization", auth(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/leave/me/requests/" + second + "/cancel").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        balance(emp[1], "CL", "available", 10.0);
    }

    @Test
    void onlyOwnersAndAdminsChangeAllowances() throws Exception {
        String body = """
                {"code":"EL","name":"Earned Leave","allocation":"ANNUAL","annualQuota":12,"halfDayAllowed":false}
                """;
        mockMvc.perform(put("/api/leave/types/" + typeIds.get("EL")).header("Authorization", auth(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/leave/types/" + typeIds.get("EL")).header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualQuota").value(12));
        String[] emp = person("EMPLOYEE");
        balance(emp[1], "EL", "available", 12.0);
    }

    // ---- helpers ----

    /** A new person in brand DW with their own PIN set; returns {id, token}. */
    private String[] person(String role) throws Exception {
        String userId = "leave" + (++seq);
        String json = """
                {"name":"Leave Tester %d","userId":"%s","password":"ab@123","role":"%s","brandId":%d,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(seq, userId, role, dwBrandId);
        String id = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()));
        String token = token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
        return new String[]{id, token};
    }

    private ResultActions apply(String token, String code, String from, String to, boolean halfDay) throws Exception {
        return mockMvc.perform(post("/api/leave/me/requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":%s}"
                        .formatted(typeIds.get(code), from, to, halfDay)));
    }

    private void balance(String token, String code, String field, double expected) throws Exception {
        String json = body(mockMvc.perform(get("/api/leave/me/balances?year=2027").header("Authorization", auth(token)))
                .andExpect(status().isOk()));
        List<Double> values = JsonPath.read(json, "$[?(@.code == '" + code + "')]." + field);
        assertEquals(expected, values.get(0), code + " " + field);
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
