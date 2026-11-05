/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.service.MenuAccessService;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agents, naming them on projects, the commission at settlement, and each action behind its own tick. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=agents")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;

    private String owner;
    private long dw;
    private long ob;
    private String customer;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
        ob = ((Number) JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"AO\",\"name\":\"Agent Other\"}")), "$.id")).longValue();
        customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Varghese Villa\",\"phone\":\"9847033333\"}".formatted(dw)));
    }

    /** {id, token} of someone with exactly these ticks. */
    private String[] staff(String... checkpoints) throws Exception {
        String userId = "ag." + (++seq);
        String id = idOf(json(post("/api/employees"), owner, """
                {"name":"Staff %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(seq, userId, dw)).andExpect(status().isCreated()));
        menuAccess.save(AccessSubject.EMPLOYEE, id, List.of(checkpoints));
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private String agentJson(long brand, String name) {
        return "{\"brandId\":%d,\"name\":\"%s\",\"phone\":\"9847011111\",\"address\":\"Kakkanad\",\"location\":\"Kochi\",\"upiId\":\"anu@okhdfcbank\"}".formatted(brand, name);
    }

    private String agent(String name) throws Exception {
        return idOf(json(post("/api/agents"), owner, agentJson(dw, name)).andExpect(status().isCreated()));
    }

    private String projectJson(String name, String agentId) {
        return "{\"brandId\":%d,\"name\":\"%s\",\"customerId\":%s,\"memberIds\":[],\"agentId\":%s}".formatted(dw, name, customer, agentId);
    }

    private String project(String name, String agentId) throws Exception {
        return idOf(json(post("/api/trade/projects"), owner, projectJson(name, agentId)).andExpect(status().isCreated()));
    }

    private ResultActions settle(String id, String settleBody) throws Exception {
        return json(post("/api/trade/projects/" + id + "/settle"), owner, settleBody);
    }

    private ResultActions list(String token, String... filters) throws Exception {
        var request = get("/api/agents/commissions").header("Authorization", auth(token));
        for (int i = 0; i + 1 < filters.length; i += 2) {
            request = request.param(filters[i], filters[i + 1]);
        }
        return mockMvc.perform(request);
    }

    @Test
    void eachActionNeedsItsTick() throws Exception {
        String a = agent("Tick Agent");
        String none = staff("1001")[1];
        mockMvc.perform(get("/api/agents").header("Authorization", auth(none))).andExpect(status().isForbidden());
        json(post("/api/agents"), none, agentJson(dw, "No")).andExpect(status().isForbidden());
        list(none).andExpect(status().isForbidden());

        // the list alone: sees agents, changes nothing, and nothing about money
        String reader = staff("13201")[1];
        mockMvc.perform(get("/api/agents").header("Authorization", auth(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].settled").doesNotExist());
        json(post("/api/agents"), reader, agentJson(dw, "No")).andExpect(status().isForbidden());
        json(put("/api/agents/" + a), reader, agentJson(dw, "No")).andExpect(status().isForbidden());
        list(reader).andExpect(status().isForbidden());

        // add, and change, each their own
        String adder = staff("13201", "13203")[1];
        json(post("/api/agents"), adder, agentJson(dw, "Added")).andExpect(status().isCreated());
        json(put("/api/agents/" + a), adder, agentJson(dw, "No")).andExpect(status().isForbidden());
        String editor = staff("13201", "13204")[1];
        json(post("/api/agents"), editor, agentJson(dw, "No")).andExpect(status().isForbidden());
        json(put("/api/agents/" + a), editor, agentJson(dw, "Changed")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Changed"));

        // commissions to read, and their status to change, each their own
        String project = project("Tick project", a);
        settle(project, "{\"commissionAmount\":100}").andExpect(status().isOk());
        String id = String.valueOf((Object) JsonPath.read(body(list(owner, "agentId", a)), "$.rows[0].id"));
        String accountant = staff("13202")[1];
        list(accountant).andExpect(status().isOk()).andExpect(jsonPath("$.canChangeStatus").value(false));
        String paid = "{\"status\":\"SETTLED\",\"transactionId\":\"UTR1\",\"paidOn\":\"%s\"}".formatted(TODAY);
        json(put("/api/agents/commissions/" + id + "/status"), accountant, paid).andExpect(status().isForbidden());
        String payer = staff("13202", "13205")[1];
        list(payer).andExpect(jsonPath("$.canChangeStatus").value(true));
        json(put("/api/agents/commissions/" + id + "/status"), payer, paid).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"));
    }

    @Test
    void theAgentHasAnIdAndIsChecked() throws Exception {
        json(post("/api/agents"), owner, agentJson(dw, "Numbered")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", startsWith("DWAGT-"))).andExpect(jsonPath("$.upiId").value("anu@okhdfcbank")).andExpect(jsonPath("$.location").value("Kochi"));
        json(post("/api/agents"), owner, "{\"brandId\":%d,\"name\":\"Bad\",\"phone\":\"9847011111\",\"upiId\":\"nonsense\"}".formatted(dw)).andExpect(status().isBadRequest());
        json(post("/api/agents"), owner, "{\"brandId\":%d,\"phone\":\"9847011111\"}".formatted(dw)).andExpect(status().isBadRequest());
        // someone of a brand adds only for their own
        String adder = staff("13201", "13203")[1];
        json(post("/api/agents"), adder, agentJson(ob, "Elsewhere")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agents").param("brandId", String.valueOf(ob)).header("Authorization", auth(adder))).andExpect(status().isForbidden());
    }

    @Test
    void thePhotoMustBeAPicture() throws Exception {
        String a = agent("Photo Agent");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        mockMvc.perform(multipart("/api/agents/" + a + "/photo").file(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpeg)).header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photoUpdatedAt").isNotEmpty());
        mockMvc.perform(get("/api/agents/" + a + "/photo").header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(jpeg));
        mockMvc.perform(multipart("/api/agents/" + a + "/photo").file(new MockMultipartFile("file", "a.jpg", "image/jpeg", "<script>".getBytes())).header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/agents/" + a + "/photo").header("Authorization", auth(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + a + "/photo").header("Authorization", auth(owner))).andExpect(status().isNotFound());
    }

    @Test
    void aProjectNamesAnAgentOfItsBrand() throws Exception {
        String a = agent("Project Agent");
        String id = project("With agent", a);
        mockMvc.perform(get("/api/trade/projects/" + id).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.agent.name").value("Project Agent")).andExpect(jsonPath("$.agent.code", startsWith("DWAGT-")));
        // another brand's cannot be named
        String other = idOf(json(post("/api/agents"), owner, agentJson(ob, "Other Brand Agent")).andExpect(status().isCreated()));
        json(post("/api/trade/projects"), owner, projectJson("Wrong brand", other)).andExpect(status().isBadRequest());
        // nor a former agent, on a new project; one already named stays
        json(put("/api/agents/" + a), owner, "{\"name\":\"Project Agent\",\"phone\":\"9847011111\",\"active\":false}").andExpect(status().isOk());
        json(post("/api/trade/projects"), owner, projectJson("Former agent", a)).andExpect(status().isBadRequest());
        json(put("/api/trade/projects/" + id), owner, projectJson("With agent, renamed", a)).andExpect(status().isOk());
        // the project form's list has no money in it, and is for those who work on projects
        String worker = staff("11500")[1];
        mockMvc.perform(get("/api/trade/agents").param("brandId", String.valueOf(dw)).header("Authorization", auth(worker))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queued").doesNotExist()).andExpect(jsonPath("$[0].upiId").doesNotExist());
    }

    @Test
    void settlingRecordsTheCommission() throws Exception {
        String a = agent("Commission Agent");
        String id = project("Earning", a);
        // with an agent, it has to be said
        settle(id, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", startsWith("Enter the commission for")));
        settle(id, "{\"text\":\"Done\",\"commissionAmount\":2500.50,\"commissionRemarks\":\"Referred by phone\"}").andExpect(status().isOk());

        list(owner, "agentId", a).andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].amount").value(2500.50)).andExpect(jsonPath("$.rows[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.rows[0].remarks").value("Referred by phone")).andExpect(jsonPath("$.rows[0].completedOn").value(TODAY.toString()))
                .andExpect(jsonPath("$.rows[0].projectName").value("Earning")).andExpect(jsonPath("$.rows[0].customerName").value("Varghese Villa"));
        mockMvc.perform(get("/api/agents").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.id == " + a + ")].queued").value(org.hamcrest.Matchers.hasItem(2500.50)));
        // the project's history does not say how much: not everyone who sees a project sees commissions
        assertThat(body(mockMvc.perform(get("/api/trade/projects/" + id).header("Authorization", auth(owner))))).doesNotContain("2,500").doesNotContain("2500.5");

        // no agent: nothing to say. Zero: no line
        settle(project("No agent", "null"), "{}").andExpect(status().isOk());
        String zeroAgent = agent("Zero Agent");
        settle(project("Nothing earned", zeroAgent), "{\"commissionAmount\":0}").andExpect(status().isOk());
        list(owner, "agentId", zeroAgent).andExpect(jsonPath("$.rows", hasSize(0)));
        // negative is refused
        settle(project("Negative", zeroAgent), "{\"commissionAmount\":-5}").andExpect(status().isBadRequest());
    }

    @Test
    void eachLineSaysWhatTheCustomerPaidOnTheProject() throws Exception {
        String a = agent("Billed Agent");
        String paidProject = project("Billed and part paid", a);
        String unpaidProject = project("Nothing billed", a);
        String quote = idOf(json(post("/api/trade/sales"), owner, """
                {"brandId":%d,"kind":"QUOTATION","customerId":%s,"projectId":%s,"docDate":"%s","gstRate":0,"lines":[{"name":"Windows","quantity":1,"rate":48000}]}
                """.formatted(dw, customer, paidProject, TODAY)).andExpect(status().isCreated()));
        String invoice = idOf(mockMvc.perform(post("/api/trade/sales/" + quote + "/invoice").header("Authorization", auth(owner))));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner))).andExpect(status().isOk());
        json(post("/api/trade/sales/" + invoice + "/payments"), owner, "{\"paidOn\":\"%s\",\"amount\":20000,\"mode\":\"UPI\",\"transactionId\":\"C1\"}".formatted(TODAY)).andExpect(status().is2xxSuccessful());
        settle(paidProject, "{\"commissionAmount\":1000}").andExpect(status().isOk());
        settle(unpaidProject, "{\"commissionAmount\":200}").andExpect(status().isOk());

        String page = body(list(owner, "agentId", a));
        assertThat(JsonPath.<List<Double>>read(page, "$.rows[?(@.projectName == 'Billed and part paid')].customerPaid")).containsExactly(20000.0);
        assertThat(JsonPath.<List<Double>>read(page, "$.rows[?(@.projectName == 'Billed and part paid')].projectValue")).containsExactly(48000.0);
        assertThat(JsonPath.<List<Double>>read(page, "$.rows[?(@.projectName == 'Nothing billed')].customerPaid")).containsExactly(0.0);
        // the same figure as the project's own page
        mockMvc.perform(get("/api/trade/projects/" + paidProject).header("Authorization", auth(owner))).andExpect(jsonPath("$.money.paid").value(20000.0));
    }

    @Test
    void paymentDetailsAreRequiredAndCanBeUndone() throws Exception {
        String a = agent("Paid Agent");
        settle(project("To pay", a), "{\"commissionAmount\":1000}").andExpect(status().isOk());
        String id = String.valueOf((Object) JsonPath.read(body(list(owner, "agentId", a)), "$.rows[0].id"));
        String url = "/api/agents/commissions/" + id + "/status";

        json(put(url), owner, "{\"status\":\"SETTLED\",\"paidOn\":\"%s\"}".formatted(TODAY)).andExpect(status().isBadRequest());
        json(put(url), owner, "{\"status\":\"SETTLED\",\"transactionId\":\"UTR9\"}").andExpect(status().isBadRequest());
        json(put(url), owner, "{\"status\":\"SETTLED\",\"transactionId\":\"UTR9\",\"paidOn\":\"%s\"}".formatted(TODAY.plusDays(1))).andExpect(status().isBadRequest());
        json(put(url), owner, "{\"status\":\"SETTLED\",\"transactionId\":\"UTR9\",\"paidOn\":\"%s\",\"remarks\":\"By UPI\"}".formatted(TODAY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("UTR9")).andExpect(jsonPath("$.paymentRemarks").value("By UPI")).andExpect(jsonPath("$.statusChangedByName").isString());

        list(owner, "agentId", a, "status", "SETTLED").andExpect(jsonPath("$.rows", hasSize(1)));
        list(owner, "agentId", a, "status", "QUEUED").andExpect(jsonPath("$.rows", hasSize(0)));
        list(owner, "agentId", a, "from", TODAY.plusDays(1).toString()).andExpect(jsonPath("$.rows", hasSize(0)));
        list(owner, "agentId", a, "from", TODAY.toString(), "to", TODAY.toString()).andExpect(jsonPath("$.rows", hasSize(1)));

        // back to queued: the payment's details go
        json(put(url), owner, "{\"status\":\"QUEUED\"}").andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").doesNotExist()).andExpect(jsonPath("$.paidOn").doesNotExist());
    }

    @Test
    void reopeningAndDeletingRespectWhatWasPaid() throws Exception {
        String a = agent("Reopen Agent");
        String b = agent("Second Agent");
        String id = project("Reopened", a);
        settle(id, "{\"commissionAmount\":500}").andExpect(status().isOk());
        // reopened and settled again: a queued commission takes the new figure, and follows a new agent
        json(post("/api/trade/projects/" + id + "/reopen"), owner, "{}").andExpect(status().isOk());
        json(put("/api/trade/projects/" + id), owner, projectJson("Reopened", b)).andExpect(status().isOk());
        list(owner, "agentId", a).andExpect(jsonPath("$.rows", hasSize(0)));
        list(owner, "agentId", b).andExpect(jsonPath("$.rows", hasSize(1)));
        settle(id, "{\"commissionAmount\":750}").andExpect(status().isOk());
        list(owner, "agentId", b).andExpect(jsonPath("$.rows", hasSize(1))).andExpect(jsonPath("$.rows[0].amount").value(750.0));

        // once paid it stays as it is, and the project cannot be deleted
        String c = String.valueOf((Object) JsonPath.read(body(list(owner, "agentId", b)), "$.rows[0].id"));
        json(put("/api/agents/commissions/" + c + "/status"), owner, "{\"status\":\"SETTLED\",\"transactionId\":\"UTR2\",\"paidOn\":\"%s\"}".formatted(TODAY)).andExpect(status().isOk());
        json(post("/api/trade/projects/" + id + "/reopen"), owner, "{}").andExpect(status().isOk());
        settle(id, "{\"commissionAmount\":9999}").andExpect(status().isOk());
        list(owner, "agentId", b).andExpect(jsonPath("$.rows[0].amount").value(750.0)).andExpect(jsonPath("$.rows[0].transactionId").value("UTR2"));
        mockMvc.perform(delete("/api/trade/projects/" + id).header("Authorization", auth(owner))).andExpect(status().isConflict());
        json(put("/api/trade/projects/" + id), owner, projectJson("Reopened", a)).andExpect(status().isConflict());
        mockMvc.perform(get("/api/trade/projects/" + id).header("Authorization", auth(owner))).andExpect(jsonPath("$.agent.commissionPaid").value(true));

        // not yet paid: it goes with the project
        String queued = project("Deleted", a);
        settle(queued, "{\"commissionAmount\":10}").andExpect(status().isOk());
        mockMvc.perform(delete("/api/trade/projects/" + queued).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        list(owner, "agentId", a).andExpect(jsonPath("$.rows", hasSize(0)));
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
