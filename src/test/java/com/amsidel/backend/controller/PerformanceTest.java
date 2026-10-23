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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Performance reviews: a Team Lead with people below her, and an unrelated colleague. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String lead;
    private String leadId;
    private String other;
    private long dw;
    private String cycle;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        leadId = create("Lakshmi Lead", "pf.lead", "MANAGER", null);
        lead = signIn("pf.lead");
        create("Olivia Other", "pf.other", "EMPLOYEE", null);
        other = signIn("pf.other");
        cycle = idOf(json(post("/api/performance/cycles"), owner, """
                {"name":"FY test","startDate":"%s","endDate":"%s"}""".formatted(TODAY.minusMonths(6), TODAY.plusMonths(6)))
                .andExpect(status().isCreated()));
    }

    @Test
    void goalsAddUpTo100AndProgressIsWeighted() throws Exception {
        String[] emp = employee();
        // goal A: 60% of the review, two sub-tasks 60/40, ended yesterday
        String a = idOf(goal(lead, emp[0], "Close 10 projects", 60, TODAY.minusMonths(1), TODAY.minusDays(1),
                task("Kumar residence", 60, TODAY.minusMonths(1), TODAY.minusDays(10)),
                task("Lulu site", 40, TODAY.minusDays(9), TODAY.minusDays(1))).andExpect(status().isCreated()));
        goal(lead, emp[0], "Too much", 50, TODAY, TODAY.plusDays(10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Goals would add up to 110%; this person has 40% left"));
        goal(lead, emp[0], "Bad tasks", 40, TODAY, TODAY.plusDays(10),
                task("x", 50, TODAY, TODAY.plusDays(1)), task("y", 40, TODAY, TODAY.plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Sub-task weights add up to 90%; they must make 100%"));
        goal(lead, emp[0], "Outside", 40, TODAY.minusYears(2), TODAY).andExpect(status().isBadRequest());
        // goal B: 40%, no sub-tasks, ends later
        String b = idOf(goal(lead, emp[0], "Learn uPVC fabrication", 40, TODAY, TODAY.plusMonths(2)).andExpect(status().isCreated()));
        // only managers set goals
        goal(emp[1], emp[0], "Mine", 10, TODAY, TODAY.plusDays(1)).andExpect(status().isForbidden());

        // the person updates progress: A = 60x100% + 40x50% = 80; B = 25
        String review = body(mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1]))));
        List<Integer> tasks = JsonPath.read(review, "$.goals[?(@.id == " + a + ")].tasks[*].id");
        json(put("/api/performance/goals/" + a + "/tasks/" + tasks.get(0) + "/progress"), emp[1], "{\"progress\":100}").andExpect(status().isOk());
        json(put("/api/performance/goals/" + a + "/tasks/" + tasks.get(1) + "/progress"), emp[1], "{\"progress\":50}")
                .andExpect(jsonPath("$.progress").value(80.0));
        json(put("/api/performance/goals/" + a + "/progress"), emp[1], "{\"progress\":10}").andExpect(status().isBadRequest());
        json(put("/api/performance/goals/" + b + "/progress"), emp[1], "{\"progress\":25}").andExpect(jsonPath("$.progress").value(25.0));

        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.totals.weightTotal").value(100))
                .andExpect(jsonPath("$.totals.progress").value(58.0))   // 0.6 x 80 + 0.4 x 25
                .andExpect(jsonPath("$.rights.self").value(true))
                .andExpect(jsonPath("$.rights.manager").value(false));
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/employees/" + emp[0]).header("Authorization", auth(lead)))
                .andExpect(jsonPath("$.rights.manager").value(true))
                .andExpect(jsonPath("$.goals", hasSize(2)));
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/employees/" + emp[0]).header("Authorization", auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void selfRatingAfterTheEndDateThenTheManagersWithFeedback() throws Exception {
        String[] emp = employee();
        String ended = idOf(goal(lead, emp[0], "Ended goal", 70, TODAY.minusMonths(1), TODAY.minusDays(1)));
        String later = idOf(goal(lead, emp[0], "Later goal", 30, TODAY, TODAY.plusMonths(1)));

        rate(lead, ended, "manager", 5, "Great").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The person rates the goal first"));
        rate(emp[1], later, "self", 4, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("You can rate this goal from")));
        rate(emp[1], ended, "self", 4, "Delivered on time").andExpect(status().isOk())
                .andExpect(jsonPath("$.selfRating").value(4))
                .andExpect(jsonPath("$.canManagerRate").value(false));
        rate(lead, ended, "manager", 5, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Write your feedback with the rating"));
        rate(lead, ended, "manager", 5, "Excellent client handling").andExpect(status().isOk())
                .andExpect(jsonPath("$.managerRating").value(5))
                .andExpect(jsonPath("$.managerRatedBy").value("Lakshmi Lead"));
        // rated goals are final
        rate(emp[1], ended, "self", 3, "").andExpect(status().isConflict());
        json(put("/api/performance/goals/" + ended), lead, goalJson("Changed", 70, TODAY.minusMonths(1), TODAY.minusDays(1)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/employees/" + emp[0]).header("Authorization", auth(lead)))
                .andExpect(jsonPath("$.goals[?(@.id == " + ended + ")].canEdit").value(hasItem(false)));
        // ... except for an owner or admin, who keeps the ratings as they are
        json(put("/api/performance/goals/" + ended), owner, goalJson("Changed", 60, TODAY.minusMonths(1), TODAY.minusDays(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Changed"))
                .andExpect(jsonPath("$.weightage").value(60))
                .andExpect(jsonPath("$.selfRating").value(4))
                .andExpect(jsonPath("$.managerRating").value(5))
                .andExpect(jsonPath("$.managerComment").value("Excellent client handling"))
                .andExpect(jsonPath("$.canEdit").value(true));
        json(put("/api/performance/goals/" + ended), owner, goalJson("Changed", 70, TODAY.minusMonths(1), TODAY.minusDays(1)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.totals.selfRating").value(4.0))
                .andExpect(jsonPath("$.totals.managerRating").value(5.0))
                .andExpect(jsonPath("$.totals.managerRated").value(1));
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/team").header("Authorization", auth(lead)))
                .andExpect(jsonPath("$[?(@.employee.id == " + emp[0] + ")].totals.goals").value(hasItem(2)));
    }

    @Test
    void ratingsGoInHalfStars() throws Exception {
        String[] emp = employee();
        String a = idOf(goal(lead, emp[0], "Half A", 50, TODAY.minusMonths(1), TODAY.minusDays(1)));
        String b = idOf(goal(lead, emp[0], "Half B", 50, TODAY.minusMonths(1), TODAY.minusDays(1)));
        rate(emp[1], a, "self", 4.3, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ratings go in half stars: 0.5, 1, 1.5 ... 5"));
        rate(emp[1], a, "self", 5.5, "").andExpect(status().isBadRequest());
        rate(emp[1], a, "self", 4.5, "").andExpect(jsonPath("$.selfRating").value(4.5));
        rate(emp[1], b, "self", 3.5, "").andExpect(status().isOk());
        rate(lead, a, "manager", 3.5, "Good, not great").andExpect(jsonPath("$.managerRating").value(3.5));
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.totals.selfRating").value(4.0))      // (4.5 + 3.5) / 2
                .andExpect(jsonPath("$.totals.managerRating").value(3.5));
        String skill = idOf(json(post("/api/performance/cycles/" + cycle + "/me/skills"), emp[1],
                "{\"name\":\"Excel\",\"category\":\"TECHNICAL\",\"selfLevel\":2.5}").andExpect(status().isCreated()));
        json(put("/api/performance/skills/" + skill + "/validation"), lead, "{\"managerLevel\":3.5}")
                .andExpect(jsonPath("$.selfLevel").value(2.5))
                .andExpect(jsonPath("$.managerLevel").value(3.5));
    }

    @Test
    void skillsIntroAndValidation() throws Exception {
        String[] emp = employee();
        json(put("/api/performance/cycles/" + cycle + "/me/intro"), emp[1], "{\"selfIntro\":\"Site engineer, 4 years in uPVC\"}")
                .andExpect(jsonPath("$.selfIntro").value("Site engineer, 4 years in uPVC"));
        String skill = idOf(json(post("/api/performance/cycles/" + cycle + "/me/skills"), emp[1],
                "{\"name\":\"AutoCAD\",\"category\":\"TECHNICAL\",\"selfLevel\":4}").andExpect(status().isCreated()));
        json(post("/api/performance/cycles/" + cycle + "/me/skills"), emp[1],
                "{\"name\":\"Client handling\",\"category\":\"NON_TECHNICAL\",\"selfLevel\":6}").andExpect(status().isBadRequest());

        json(put("/api/performance/skills/" + skill + "/validation"), other, "{\"managerLevel\":3}").andExpect(status().isForbidden());
        json(put("/api/performance/skills/" + skill + "/validation"), lead, "{\"managerLevel\":3,\"note\":\"Good with 2D\"}")
                .andExpect(jsonPath("$.managerLevel").value(3))
                .andExpect(jsonPath("$.validatedBy").value("Lakshmi Lead"));
        // a new own level keeps the manager's; renaming the skill asks for validation again
        json(put("/api/performance/skills/" + skill), emp[1], "{\"name\":\"AutoCAD\",\"category\":\"TECHNICAL\",\"selfLevel\":5}")
                .andExpect(jsonPath("$.managerLevel").value(3));
        json(put("/api/performance/skills/" + skill), emp[1], "{\"name\":\"AutoCAD 3D\",\"category\":\"TECHNICAL\",\"selfLevel\":5}")
                .andExpect(jsonPath("$.managerLevel").doesNotExist());
        mockMvc.perform(delete("/api/performance/skills/" + skill).header("Authorization", auth(lead))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/performance/skills/" + skill).header("Authorization", auth(emp[1]))).andExpect(status().isNoContent());
    }

    @Test
    void anyoneWithPeopleBelowThemSetsGoalsAndAddsSkills() throws Exception {
        // an Employee-type person: no team menus until someone reports to them
        String sibinId = create("Sibin James", "pf.sibin", "EMPLOYEE", null);
        String sibin = signIn("pf.sibin");
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(sibin)))
                .andExpect(jsonPath("$.codes", not(hasItem("PERF_TEAM"))));
        String amalId = create("Amal Antony", "pf.amal", "EMPLOYEE", sibinId);
        String amal = signIn("pf.amal");
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(sibin)))
                .andExpect(jsonPath("$.codes", hasItem("PERF_TEAM")));
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/team").header("Authorization", auth(sibin)))
                .andExpect(jsonPath("$[*].employee.name", contains("Amal Antony")));

        goal(sibin, amalId, "Install 20 windows", 50, TODAY, TODAY.plusMonths(1)).andExpect(status().isCreated());
        String other2 = create("Not Mine", "pf.notmine", "EMPLOYEE", null);
        goal(sibin, other2, "Not my team", 50, TODAY, TODAY.plusMonths(1)).andExpect(status().isForbidden());

        // the manager adds a skill with their level; the person gives theirs later
        String skill = idOf(json(post("/api/performance/cycles/" + cycle + "/employees/" + amalId + "/skills"), sibin,
                "{\"name\":\"Site measurement\",\"category\":\"TECHNICAL\",\"managerLevel\":4,\"note\":\"Accurate\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addedBy").value("Sibin James"))
                .andExpect(jsonPath("$.selfLevel").value(0))
                .andExpect(jsonPath("$.managerLevel").value(4)));
        json(put("/api/performance/skills/" + skill), amal, "{\"name\":\"Site measurement\",\"category\":\"TECHNICAL\",\"selfLevel\":3}")
                .andExpect(jsonPath("$.selfLevel").value(3))
                .andExpect(jsonPath("$.managerLevel").value(4));
        mockMvc.perform(delete("/api/performance/skills/" + skill).header("Authorization", auth(amal))).andExpect(status().isForbidden());
        json(post("/api/performance/cycles/" + cycle + "/employees/" + amalId + "/skills"), amal,
                "{\"name\":\"x\",\"category\":\"TECHNICAL\",\"managerLevel\":4}").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/performance/skills/" + skill).header("Authorization", auth(sibin))).andExpect(status().isNoContent());
    }

    @Test
    void achievementsAreGivenByTheManager() throws Exception {
        String[] emp = employee();
        String issued = idOf(json(post("/api/performance/achievements"), lead, """
                {"employeeId":%s,"title":"Team Champ","category":"Team Champ","achievedOn":"%s","details":"Most sites closed"}"""
                .formatted(emp[0], TODAY)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issuedBy").value("Lakshmi Lead"))
                .andExpect(jsonPath("$.selfReported").value(false)));
        // people do not add their own
        json(post("/api/performance/achievements"), emp[1], """
                {"title":"Certified installer","achievedOn":"%s"}""".formatted(TODAY.minusDays(3)))
                .andExpect(status().isForbidden());
        json(post("/api/performance/achievements"), other, """
                {"employeeId":%s,"title":"Fake","achievedOn":"%s"}""".formatted(emp[0], TODAY)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.achievements[*].title", contains("Team Champ")));
        mockMvc.perform(delete("/api/performance/achievements/" + issued).header("Authorization", auth(emp[1]))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/performance/achievements/" + issued).header("Authorization", auth(lead))).andExpect(status().isNoContent());
    }

    @Test
    void aClosedCycleIsReadOnlyAndCyclesNeedTheirMenu() throws Exception {
        String closed = idOf(json(post("/api/performance/cycles"), owner, """
                {"name":"Old year","startDate":"%s","endDate":"%s","closed":true}""".formatted(TODAY.minusYears(2), TODAY.minusYears(1))));
        String[] emp = employee();
        json(post("/api/performance/cycles/" + closed + "/me/skills"), emp[1],
                "{\"name\":\"Excel\",\"category\":\"TECHNICAL\",\"selfLevel\":3}")
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/performance/cycles/" + closed + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.rights.self").value(false));
        json(post("/api/performance/cycles"), lead, "{\"name\":\"x\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\"}")
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/performance/cycles").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$[*].name", hasItems("FY test", "Old year")));
    }

    // ---- helpers ----

    /** A new employee below the lead: {id, token}. */
    private String[] employee() throws Exception {
        String userId = "pf.emp" + (++seq);
        String id = create("Emp " + seq, userId, "EMPLOYEE", leadId);
        return new String[]{id, signIn(userId)};
    }

    private ResultActions goal(String token, String employeeId, String title, int weight, LocalDate from, LocalDate to,
                               String... tasks) throws Exception {
        return json(post("/api/performance/cycles/" + cycle + "/employees/" + employeeId + "/goals"), token,
                goalJson(title, weight, from, to, tasks));
    }

    private static String goalJson(String title, int weight, LocalDate from, LocalDate to, String... tasks) {
        return """
                {"title":"%s","weightage":%d,"startDate":"%s","endDate":"%s","tasks":[%s]}"""
                .formatted(title, weight, from, to, String.join(",", tasks));
    }

    private static String task(String title, int weight, LocalDate from, LocalDate to) {
        return "{\"title\":\"%s\",\"weightage\":%d,\"startDate\":\"%s\",\"endDate\":\"%s\"}".formatted(title, weight, from, to);
    }

    private ResultActions rate(String token, String goalId, String who, double rating, String comment) throws Exception {
        return json(put("/api/performance/goals/" + goalId + "/" + who + "-rating"), token,
                "{\"rating\":%s,\"comment\":\"%s\"}".formatted(rating, comment));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token,
                               String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String create(String name, String userId, String role, String reportsTo) throws Exception {
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"reportsToId":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, dw, reportsTo);
        return idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()));
    }

    private String signIn(String userId) throws Exception {
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
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
