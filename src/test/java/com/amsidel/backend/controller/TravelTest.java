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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Travel allowance: rates, manual claims, GPS trips, approval with a changed amount, and settlement. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=travel", "app.maps.google-api-key="})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TravelTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private int brand;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[*].id");
        brand = ids.getFirst();
    }

    /** [id, token]: someone with exactly these ticks, reporting to reportsTo. */
    private String[] person(String reportsTo, String... ticks) throws Exception {
        String userId = "ta." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Traveller %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"reportsToId":%s,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand, reportsTo)).andExpect(status().isCreated())), "$.id"));
        json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", ticks) + "\"]}").andExpect(status().isOk());
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private void rate(String id, String rate) throws Exception {
        json(put("/api/travel/rates/" + id), owner, "{\"ratePerKm\":" + rate + "}").andExpect(status().isOk()).andExpect(jsonPath("$.ratePerKm").value(Double.parseDouble(rate)));
    }

    private String manual(String token, String km, String food) throws Exception {
        return """
                {"customerName":"Thomas Mathew","meetingDate":"%s","fromLocation":"Office","toLocation":"Kakkanad","meetingPoint":"Thomas residence","distanceKm":%s,"foodExpense":%s,"otherExpense":20}
                """.formatted(TODAY, km, food);
    }

    @Test
    void aManualClaimIsWorkedOutApprovedWithAnotherAmountAndSettled() throws Exception {
        String[] boss = person("null", "5201", "5202", "5207");
        String[] me = person(boss[0], "5201", "5204");

        // no rate yet: no claim
        json(post("/api/travel/claims"), me[1], manual(me[1], "42.5", "150")).andExpect(status().isBadRequest());
        rate(me[0], "12");
        mockMvc.perform(get("/api/travel/me").header("Authorization", auth(me[1]))).andExpect(jsonPath("$.ratePerKm").value(12.0))
                .andExpect(jsonPath("$.currencySymbol").value("₹")).andExpect(jsonPath("$.canManual").value(true)).andExpect(jsonPath("$.canGps").value(false));

        // 42.5 km x 12 = 510, + 150 food + 20 other = 680
        String claim = body(json(post("/api/travel/claims"), me[1], manual(me[1], "42.5", "150")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.travelAmount").value(510.0))
                .andExpect(jsonPath("$.claimedAmount").value(680.0)).andExpect(jsonPath("$.code").isNotEmpty()));
        int id = JsonPath.read(claim, "$.id");

        // the rate changing later does not change the claim
        rate(me[0], "15");
        mockMvc.perform(get("/api/travel/me").header("Authorization", auth(me[1])))
                .andExpect(jsonPath("$.claims[0].claimedAmount").value(680.0)).andExpect(jsonPath("$.totals.pending").value(680.0));

        // not one's own; a lower amount needs a reason
        json(put("/api/travel/claims/" + id + "/decision"), me[1], "{\"approve\":true}").andExpect(status().isForbidden());
        json(put("/api/travel/claims/" + id + "/decision"), boss[1], "{\"approve\":true,\"approvedAmount\":600}").andExpect(status().isBadRequest());
        json(put("/api/travel/claims/" + id + "/decision"), boss[1], "{\"approve\":true,\"approvedAmount\":600,\"remarks\":\"Food limit is 70\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvedAmount").value(600.0))
                .andExpect(jsonPath("$.decisionRemarks").value("Food limit is 70"));

        // waiting, then settled
        json(put("/api/travel/claims/" + id + "/waiting"), boss[1], "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_SETTLEMENT"));
        json(put("/api/travel/claims/" + id + "/settlement"), boss[1], """
                {"transactionId":"UTR123456","paymentDate":"%s","paymentAmount":600,"remarks":"Paid by NEFT"}
                """.formatted(TODAY)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.transactionId").value("UTR123456")).andExpect(jsonPath("$.paymentAmount").value(600.0));
        // a decided claim cannot be deleted; the figures follow
        mockMvc.perform(delete("/api/travel/claims/" + id).header("Authorization", auth(me[1]))).andExpect(status().isConflict());
        String month = TODAY.toString().substring(0, 7);
        mockMvc.perform(get("/api/travel/me").header("Authorization", auth(me[1])))
                .andExpect(jsonPath("$.totals.allocated").value(600.0)).andExpect(jsonPath("$.totals.settled").value(600.0))
                .andExpect(jsonPath("$.months[11].month").value(month)).andExpect(jsonPath("$.months[11].settled").value(600.0));
        mockMvc.perform(get("/api/travel/team").header("Authorization", auth(boss[1])).param("status", "SETTLED"))
                .andExpect(jsonPath("$.claims[0].employeeId").value(Integer.parseInt(me[0]))).andExpect(jsonPath("$.totals.settled").value(600.0));
        // the Overview's brand choice: that brand's people only
        mockMvc.perform(get("/api/travel/team").header("Authorization", auth(owner)).param("brandId", String.valueOf(brand)))
                .andExpect(jsonPath("$.claims[*].employeeId", hasItem(Integer.parseInt(me[0])))).andExpect(jsonPath("$.months[11].settled").value(600.0));
        int otherBrand = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"TO\",\"name\":\"Travel Other Brand\"}")), "$.id");
        mockMvc.perform(get("/api/travel/team").header("Authorization", auth(owner)).param("brandId", String.valueOf(otherBrand)))
                .andExpect(jsonPath("$.claims").isEmpty()).andExpect(jsonPath("$.totals.settled").value(0.0)).andExpect(jsonPath("$.months[11].settled").value(0.0));
    }

    @Test
    void theTicksDecideHowAClaimMayBeMade() throws Exception {
        String[] noManual = person("null", "5201", "5205");
        rate(noManual[0], "10");
        json(post("/api/travel/claims"), noManual[1], manual(noManual[1], "10", "0")).andExpect(status().isForbidden());
        String[] noGps = person("null", "5201", "5204");
        rate(noGps[0], "10");
        json(post("/api/travel/trips"), noGps[1], start()).andExpect(status().isForbidden());
        // no future meetings; someone else's approver cannot decide; settling needs its tick
        json(post("/api/travel/claims"), noGps[1], manual(noGps[1], "10", "0").replace(TODAY.toString(), TODAY.plusDays(2).toString()))
                .andExpect(status().isBadRequest());
        int id = JsonPath.read(body(json(post("/api/travel/claims"), noGps[1], manual(noGps[1], "10", "0")).andExpect(status().isCreated())), "$.id");
        String[] stranger = person("null", "5201", "5202");
        json(put("/api/travel/claims/" + id + "/decision"), stranger[1], "{\"approve\":true}").andExpect(status().isForbidden());
        // nobody to ask: the owner decides; rejecting needs a reason
        json(put("/api/travel/claims/" + id + "/decision"), owner, "{\"approve\":false}").andExpect(status().isBadRequest());
        json(put("/api/travel/claims/" + id + "/decision"), owner, "{\"approve\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.approvedAmount").value(120.0));
        json(put("/api/travel/claims/" + id + "/settlement"), stranger[1], """
                {"transactionId":"X1","paymentDate":"%s","paymentAmount":120}
                """.formatted(TODAY)).andExpect(status().isForbidden());
    }

    @Test
    void aGpsTripGetsItsDistanceFromTheStopsAndChangingItNeedsTheTick() throws Exception {
        String[] boss = person("null", "5201", "5202");
        String[] me = person(boss[0], "5201", "5205");
        rate(me[0], "10");
        int id = JsonPath.read(body(json(post("/api/travel/trips"), me[1], start()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))), "$.id");
        // one trip at a time
        json(post("/api/travel/trips"), me[1], start()).andExpect(status().isConflict());
        // Kochi office -> about 11 km north -> back
        json(post("/api/travel/trips/" + id + "/stops"), me[1], """
                {"label":"Customer site","fix":{"latitude":10.1000,"longitude":76.3000,"accuracy":12}}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.stops.length()").value(2)).andExpect(jsonPath("$.meetingPoint").value("Customer site"));
        String done = body(json(post("/api/travel/trips/" + id + "/complete"), me[1], """
                {"fix":{"latitude":10.0000,"longitude":76.3000}}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TO_CONFIRM"))
                .andExpect(jsonPath("$.distanceSource").value("STRAIGHT_LINE")).andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.shortestKm", closeTo(22.24, 0.05))).andExpect(jsonPath("$.longestKm", closeTo(31.13, 0.1))));
        double shortest = JsonPath.read(done, "$.shortestKm");
        // not in the team list until sent
        mockMvc.perform(get("/api/travel/team").header("Authorization", auth(boss[1]))).andExpect(jsonPath("$.claims[*].id", not(hasItem(id))));

        // another distance without the tick: refused; the GPS one: sent
        json(post("/api/travel/trips/" + id + "/confirm"), me[1], "{\"distanceKm\":30,\"toLocation\":\"Edappally\"}").andExpect(status().isForbidden());
        json(post("/api/travel/trips/" + id + "/confirm"), me[1], "{\"distanceKm\":%s,\"toLocation\":\"Edappally\",\"foodExpense\":50}".formatted(shortest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.distanceChanged").value(false))
                .andExpect(jsonPath("$.claimedAmount", closeTo(shortest * 10 + 50, 0.01)));

        // with the tick another distance may be given
        String[] editor = person(boss[0], "5201", "5205", "5206");
        rate(editor[0], "10");
        int trip = JsonPath.read(body(json(post("/api/travel/trips"), editor[1], start())), "$.id");
        json(post("/api/travel/trips/" + trip + "/complete"), editor[1], "{\"fix\":{\"latitude\":10.05,\"longitude\":76.3}}").andExpect(status().isOk());
        json(post("/api/travel/trips/" + trip + "/confirm"), editor[1], "{\"distanceKm\":14,\"toLocation\":\"Kalamassery\",\"meetingPoint\":\"Plant\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.distanceChanged").value(true)).andExpect(jsonPath("$.claimedAmount").value(140.0));
        // an undecided claim may still be deleted
        mockMvc.perform(delete("/api/travel/claims/" + trip).header("Authorization", auth(editor[1]))).andExpect(status().isNoContent());
    }

    @Test
    void stopLocationsChangeOnlyWithTheTickAndTheRouteFollows() throws Exception {
        String[] boss = person("null", "5201", "5202", "5208");
        String[] plain = person(boss[0], "5201", "5205");
        String[] me = person(boss[0], "5201", "5205", "5208");
        rate(plain[0], "10");
        rate(me[0], "10");
        int other = JsonPath.read(body(json(post("/api/travel/trips"), plain[1], start())), "$.id");
        json(put("/api/travel/claims/" + other + "/stops/1"), plain[1], "{\"latitude\":10.01,\"longitude\":76.3}").andExpect(status().isForbidden());

        int id = JsonPath.read(body(json(post("/api/travel/trips"), me[1], start())), "$.id");
        json(post("/api/travel/trips/" + id + "/stops"), me[1], "{\"label\":\"Site\",\"fix\":{\"latitude\":10.1,\"longitude\":76.3}}").andExpect(status().isOk());
        json(post("/api/travel/trips/" + id + "/complete"), me[1], "{\"fix\":{\"latitude\":10.0,\"longitude\":76.3}}")
                .andExpect(jsonPath("$.shortestKm", closeTo(22.24, 0.05)));
        // the stop really was half as far: the route follows, the GPS location is kept
        json(put("/api/travel/claims/" + id + "/stops/2"), me[1], "{\"latitude\":10.05,\"longitude\":76.3,\"label\":\"Site gate\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.shortestKm", closeTo(11.12, 0.05))).andExpect(jsonPath("$.distanceKm", closeTo(11.12, 0.05)))
                .andExpect(jsonPath("$.stops[1].label").value("Site gate")).andExpect(jsonPath("$.stops[1].originalLatitude").value(10.1))
                .andExpect(jsonPath("$.stops[1].editedByName").isNotEmpty()).andExpect(jsonPath("$.canEditStops").value(true));
        double km = JsonPath.read(body(mockMvc.perform(get("/api/travel/me").header("Authorization", auth(me[1])))), "$.openTrip.distanceKm");
        json(post("/api/travel/trips/" + id + "/confirm"), me[1], "{\"distanceKm\":%s,\"toLocation\":\"Site\",\"meetingPoint\":\"Site\"}".formatted(km)).andExpect(status().isOk());
        // the approver moves it back while it waits; the amount follows
        json(put("/api/travel/claims/" + id + "/stops/2"), boss[1], "{\"latitude\":10.1,\"longitude\":76.3}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.claimedAmount", closeTo(222.4, 0.6))).andExpect(jsonPath("$.stops[1].originalLatitude").value(10.1));
        // once decided, no more
        json(put("/api/travel/claims/" + id + "/decision"), boss[1], "{\"approve\":true}").andExpect(status().isOk());
        json(put("/api/travel/claims/" + id + "/stops/2"), boss[1], "{\"latitude\":10.2,\"longitude\":76.3}").andExpect(status().isForbidden());
    }

    @Test
    void theCustomerComesFromTheProjectPortalOrIsTypedIn() throws Exception {
        String[] me = person("null", "5201", "5204");
        rate(me[0], "10");
        int customer = JsonPath.read(body(json(post("/api/trade/customers"), owner, """
                {"name":"Lakeview Homes","phone":"+91 97470 12121","brandId":%d,"location":"Kakkanad"}
                """.formatted(brand)).andExpect(status().isCreated())), "$.id");
        // found by name or by phone, with its code
        mockMvc.perform(get("/api/travel/customers").param("q", "lakeview").header("Authorization", auth(me[1])))
                .andExpect(jsonPath("$[?(@.id == " + customer + ")].location", hasItem("Kakkanad")));
        mockMvc.perform(get("/api/travel/customers").param("q", "12121").header("Authorization", auth(me[1])))
                .andExpect(jsonPath("$[*].id", hasItem(customer)));
        // picked: the list's name wins over what was typed
        json(post("/api/travel/claims"), me[1], manual(me[1], "5", "0").replace("\"customerName\":\"Thomas Mathew\"", "\"customerId\":" + customer + ",\"customerName\":\"lake\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerName").value("Lakeview Homes"))
                .andExpect(jsonPath("$.customerId").value(customer)).andExpect(jsonPath("$.customerCode").isNotEmpty());
        // not in the list: the name as typed
        json(post("/api/travel/claims"), me[1], manual(me[1], "5", "0")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Thomas Mathew")).andExpect(jsonPath("$.customerId").doesNotExist());
        // another brand's customer cannot be picked
        int other = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"TX\",\"name\":\"Travel Other\"}")), "$.id");
        int theirs = JsonPath.read(body(json(post("/api/trade/customers"), owner, """
                {"name":"Far Away Ltd","phone":"+91 97470 34343","brandId":%d}
                """.formatted(other)).andExpect(status().isCreated())), "$.id");
        json(post("/api/travel/claims"), me[1], manual(me[1], "5", "0").replace("{\"customerName\"", "{\"customerId\":" + theirs + ",\"customerName\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ratesAreSetByThoseWithTheTickButNeverOnesOwn() throws Exception {
        String[] admin = person("null", "5203");
        String[] staff = person(admin[0], "5201");
        json(put("/api/travel/rates/" + staff[0]), admin[1], "{\"ratePerKm\":9.5}").andExpect(status().isOk());
        json(put("/api/travel/rates/" + admin[0]), admin[1], "{\"ratePerKm\":50}").andExpect(status().isForbidden());
        json(put("/api/travel/rates/" + staff[0]), staff[1], "{\"ratePerKm\":50}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/travel/rates").header("Authorization", auth(admin[1])))
                .andExpect(jsonPath("$[?(@.employeeId == " + staff[0] + ")].ratePerKm", hasItem(9.5)));
    }

    private static String start() {
        return """
                {"customerName":"Asha Builders","fromLocation":"Kochi office","fix":{"latitude":10.0000,"longitude":76.3000,"accuracy":8}}
                """;
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String token(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.token");
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
