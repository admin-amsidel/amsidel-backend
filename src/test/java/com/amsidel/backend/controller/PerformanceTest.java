/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
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

    @Test
    void salesOrderGoalsCountTheOrdersOfTheProjectsThePersonIsAllocatedTo() throws Exception {
        String[] emp = employee();
        String[] other = employee();
        json(put("/api/menu-access/EMPLOYEE/" + emp[0]), owner, "{\"checkpoints\":[\"11304\",\"11301\",\"6001\"]}").andExpect(status().isOk());
        String customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Goal Villa\",\"phone\":\"9847099999\"}".formatted(dw)));
        // her project (she is an allocated employee), and someone else's
        String mine = idOf(json(post("/api/trade/projects"), owner,
                "{\"brandId\":%d,\"name\":\"Goal villa windows\",\"customerId\":%s,\"startDate\":\"%s\",\"memberIds\":[%s]}".formatted(dw, customer, TODAY, emp[0]))
                .andExpect(status().isCreated()));
        String theirs = idOf(json(post("/api/trade/projects"), owner,
                "{\"brandId\":%d,\"name\":\"Goal villa doors\",\"customerId\":%s,\"startDate\":\"%s\",\"memberIds\":[%s]}".formatted(dw, customer, TODAY, other[0]))
                .andExpect(status().isCreated()));
        String goal = idOf(json(post("/api/performance/cycles/" + cycle + "/employees/" + emp[0] + "/goals"), lead, """
                {"title":"Orders FY","type":"SALES_ORDER","targetAmount":100000,"weightage":50,"startDate":"%s","endDate":"%s","tasks":[]}
                """.formatted(TODAY.minusMonths(1), TODAY.plusMonths(1))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SALES_ORDER")).andExpect(jsonPath("$.achievedAmount").value(0)));
        // sub-tasks are not for this kind
        json(post("/api/performance/cycles/" + cycle + "/employees/" + emp[0] + "/goals"), lead, """
                {"title":"x","type":"SALES_ORDER","targetAmount":1000,"weightage":10,"startDate":"%s","endDate":"%s",
                 "tasks":[{"title":"t","startDate":"%s","endDate":"%s"}]}
                """.formatted(TODAY, TODAY, TODAY, TODAY)).andExpect(status().isBadRequest());

        String order = "{\"kind\":\"SALES_ORDER\",\"brandId\":%d,\"customerId\":%s,\"projectId\":%s,\"docDate\":\"%s\",\"gstRate\":18,\"lines\":[{\"name\":\"Windows\",\"quantity\":1,\"rate\":%s}]}";
        // on her project, made and confirmed by the owner: hers in full (30,000 and 50,000 before GST)
        String first = idOf(json(post("/api/trade/sales"), owner, order.formatted(dw, customer, mine, TODAY, "30000")).andExpect(status().isCreated()));
        String second = idOf(json(post("/api/trade/sales"), owner, order.formatted(dw, customer, mine, TODAY, "50000")).andExpect(status().isCreated()));
        // a draft on her project; one she makes on someone else's project; one with no project: none of them count
        json(post("/api/trade/sales"), owner, order.formatted(dw, customer, mine, TODAY, "99999")).andExpect(status().isCreated());
        String elsewhere = idOf(json(post("/api/trade/sales"), emp[1], order.formatted(dw, customer, theirs, TODAY, "70000")).andExpect(status().isCreated()));
        String loose = idOf(json(post("/api/trade/sales"), emp[1], order.formatted(dw, customer, "null", TODAY, "60000")).andExpect(status().isCreated()));
        // on her project but dated before the goal starts (confirmed today): the order date counts, so it is left out
        String early = idOf(json(post("/api/trade/sales"), owner, order.formatted(dw, customer, mine, TODAY.minusMonths(2), "40000")).andExpect(status().isCreated()));
        for (String id : List.of(first, second, elsewhere, loose, early)) {
            mockMvc.perform(post("/api/trade/sales/" + id + "/issue").header("Authorization", auth(owner))).andExpect(status().isOk());
        }
        // the project is Confirmed
        mockMvc.perform(get("/api/trade/projects/" + mine).header("Authorization", auth(owner))).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 80,000 of 1,00,000: 80%, 4 stars, each order a line with its month
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].achievedAmount").value(hasItem(80000.0)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].achievedPercent").value(hasItem(80.0)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].selfRating").value(hasItem(4.0)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].orders.length()").value(hasItem(2)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].orders[0].month").value(hasItem(java.time.YearMonth.from(TODAY).toString())))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].orders[0].total").value(hasItem(35400.0)));
        rate(emp[1], goal, "self", 4, "").andExpect(status().isBadRequest());
        // a cancelled order drops out
        json(post("/api/trade/sales/" + second + "/cancel"), owner, "{\"reason\":\"Customer called it off\"}").andExpect(status().isOk());
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/employees/" + emp[0]).header("Authorization", auth(lead)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].achievedAmount").value(hasItem(30000.0)))
                .andExpect(jsonPath("$.goals[?(@.id == " + goal + ")].canManagerRate").value(hasItem(true)));
        rate(lead, goal, "manager", 3, "Good start").andExpect(status().isOk());
    }

    @Test
    void salesGoalsFollowTheAmountsAchieved() throws Exception {
        String[] emp = employee();
        LocalDate from = TODAY.minusMonths(2);
        LocalDate to = TODAY.minusDays(1);
        String q1 = "{\"title\":\"Q1 windows\",\"targetAmount\":100000,\"startDate\":\"%s\",\"endDate\":\"%s\"}".formatted(from, from.plusMonths(1));
        String q2 = "{\"title\":\"Q2 doors\",\"targetAmount\":150000,\"startDate\":\"%s\",\"endDate\":\"%s\"}".formatted(from.plusMonths(1), to);
        // the sub-task targets must make the goal's target
        json(post("/api/performance/cycles/" + cycle + "/employees/" + emp[0] + "/goals"), lead, """
                {"title":"Sales FY","type":"SALES","targetAmount":300000,"weightage":60,"startDate":"%s","endDate":"%s","tasks":[%s,%s]}
                """.formatted(from, to, q1, q2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("Sub-task targets add up to 250000")));
        String goalJson = body(json(post("/api/performance/cycles/" + cycle + "/employees/" + emp[0] + "/goals"), lead, """
                {"title":"Sales FY","type":"SALES","targetAmount":250000,"weightage":60,"startDate":"%s","endDate":"%s","tasks":[%s,%s]}
                """.formatted(from, to, q1, q2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SALES")).andExpect(jsonPath("$.tasks[*].weightage", contains(40, 60)))
                .andExpect(jsonPath("$.progress").value(0)));
        String goalId = String.valueOf((Object) JsonPath.read(goalJson, "$.id"));
        Integer task1 = JsonPath.read(goalJson, "$.tasks[0].id");
        Integer task2 = JsonPath.read(goalJson, "$.tasks[1].id");

        // no stars or sliders by hand
        rate(emp[1], goalId, "self", 4, "").andExpect(status().isBadRequest());
        json(put("/api/performance/goals/" + goalId + "/tasks/" + task1 + "/progress"), emp[1], "{\"progress\":50}").andExpect(status().isBadRequest());
        // the manager does not record for them; the sub-task is needed; dates inside it
        String entry = "{\"taskId\":%s,\"amount\":%s,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"invoiceNumbers\":\"%s\",\"description\":\"Kumar villa\"}";
        json(post("/api/performance/goals/" + goalId + "/sales-entries"), lead, entry.formatted(task1, 1000, from, from.plusDays(5), "X"))
                .andExpect(status().isForbidden());
        json(post("/api/performance/goals/" + goalId + "/sales-entries"), emp[1], entry.formatted(null, 1000, from, from.plusDays(5), "X"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Choose the sub-task it counts for"));
        json(post("/api/performance/goals/" + goalId + "/sales-entries"), emp[1], entry.formatted(task1, 1000, from.minusDays(3), from, "X"))
                .andExpect(status().isBadRequest());

        // 80,000 of the first sub-task's 1,00,000 and 45,000 of the second's 1,50,000: 1,25,000 of 2,50,000 = 50%, 2.5 stars
        json(post("/api/performance/goals/" + goalId + "/sales-entries"), emp[1],
                entry.formatted(task1, 80000, from, from.plusDays(20), " INV-101 ,INV-102,, INV-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesEntries[0].invoiceNumbers", contains("INV-101", "INV-102")))
                .andExpect(jsonPath("$.tasks[0].achievedAmount").value(80000)).andExpect(jsonPath("$.tasks[0].progress").value(80));
        String after = body(json(post("/api/performance/goals/" + goalId + "/sales-entries"), emp[1],
                entry.formatted(task2, 45000, from.plusMonths(1), to, "INV-150")).andExpect(status().isOk())
                .andExpect(jsonPath("$.achievedAmount").value(125000)).andExpect(jsonPath("$.achievedPercent").value(50))
                .andExpect(jsonPath("$.progress").value(50)).andExpect(jsonPath("$.selfRating").value(2.5))
                .andExpect(jsonPath("$.canManagerRate").value(false)));
        Integer second = JsonPath.read(after, "$.salesEntries[1].id");
        // changed to 1,70,000: 2,50,000 of 2,50,000 = 100%, 5 stars
        json(put("/api/performance/sales-entries/" + second), emp[1], entry.formatted(task2, 170000, from.plusMonths(1), to, "INV-150, INV-151"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.achievedPercent").value(100)).andExpect(jsonPath("$.selfRating").value(5));
        // the review counts it with its weight (60% of the review at 100%)
        mockMvc.perform(get("/api/performance/cycles/" + cycle + "/me").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.totals.progress").value(60)).andExpect(jsonPath("$.currencySymbol").value("₹"));
        // a sub-task with records stays; the goal stays sales; it cannot be removed
        json(put("/api/performance/goals/" + goalId), lead, """
                {"title":"Sales FY","type":"SALES","targetAmount":100000,"weightage":60,"startDate":"%s","endDate":"%s","tasks":[%s]}
                """.formatted(from, to, q1.replace("\"title\"", "\"id\":" + task1 + ",\"title\""))).andExpect(status().isConflict());
        json(put("/api/performance/goals/" + goalId), lead, goalJson("Sales FY", 60, from, to)).andExpect(status().isConflict());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/performance/goals/" + goalId)
                .header("Authorization", auth(lead))).andExpect(status().isConflict());
        // the manager rates it with feedback; then it is final
        rate(lead, goalId, "manager", 4.5, "Target met").andExpect(status().isOk()).andExpect(jsonPath("$.canRecord").value(false));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/performance/sales-entries/" + second)
                .header("Authorization", auth(emp[1]))).andExpect(status().isConflict());
        // a general goal records nothing
        String general = idOf(goal(lead, emp[0], "General", 40, from, to));
        json(post("/api/performance/goals/" + general + "/sales-entries"), emp[1], entry.formatted(null, 1000, from, to, "X"))
                .andExpect(status().isBadRequest());
    }

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
