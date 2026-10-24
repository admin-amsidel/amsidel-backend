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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * News and announcements: owners and admins add, change, hide and delete them with a picture; everyone signed in sees
 * today's on the dashboard with today's birthdays; the website sees only the ones for customers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=news")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String staff;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
        // born on this day some years ago: a birthday today
        mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Amal Antony\",\"userId\":\"nw.amal\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"9847000004\",\"joinedDate\":\"2026-01-05\",\"dob\":\"%s\"}"
                        .formatted(dw, TODAY.minusYears(28)))).andExpect(status().isCreated());
        staff = token(changePin(token(login("nw.amal", "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void newsFromTheAdminToTheDashboardAndTheWebsite() throws Exception {
        // an offer for customers (with a picture) and a notice for staff; one that starts next week
        String offer = idOf(json(post("/api/news"), owner, """
                {"title":"Diwali offer: 15%% off uPVC windows","body":"Till the end of the month at every showroom.","category":"DISCOUNT",
                 "audience":"EVERYONE","brandId":%d,"endDate":"%s","pinned":true}
                """.formatted(dw, TODAY.plusDays(10))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LIVE")).andExpect(jsonPath("$.startDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.brandCode").value("DW")).andExpect(jsonPath("$.createdByName").value("Amsidel Owner")));
        String notice = idOf(json(post("/api/news"), owner,
                "{\"title\":\"Office closed on Saturday\",\"category\":\"IMPORTANT\",\"audience\":\"EMPLOYEES\"}"));
        // for staff to pass on to their customers: on the dashboard, not on the website
        json(post("/api/news"), owner, "{\"title\":\"Tell your customers: free mesh\",\"category\":\"PROMOTION\",\"audience\":\"CUSTOMERS\"}")
                .andExpect(status().isCreated());
        // public: on the website
        json(post("/api/news"), owner, "{\"title\":\"New showroom in Kochi\",\"category\":\"ANNOUNCEMENT\",\"audience\":\"PUBLIC\"}")
                .andExpect(status().isCreated());
        json(post("/api/news"), owner, "{\"title\":\"New showroom opening\",\"category\":\"EVENT\",\"audience\":\"EVERYONE\",\"startDate\":\"%s\"}"
                .formatted(TODAY.plusDays(7))).andExpect(jsonPath("$.status").value("SCHEDULED"));
        json(post("/api/news"), owner, "{\"title\":\"x\",\"category\":\"OTHER\",\"audience\":\"EVERYONE\",\"startDate\":\"%s\",\"endDate\":\"%s\"}"
                .formatted(TODAY, TODAY.minusDays(1))).andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/news/" + offer + "/image").file(new MockMultipartFile("file", "offer.png", "image/png", png()))
                .header("Authorization", auth(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.imageUpdatedAt").isNotEmpty());
        mockMvc.perform(multipart("/api/news/" + offer + "/image").file(new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes()))
                .header("Authorization", auth(owner))).andExpect(status().isBadRequest());

        // staff: today's news (pinned first) and today's birthday; they cannot change news
        mockMvc.perform(get("/api/news/feed").header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.news.length()").value(4))
                .andExpect(jsonPath("$.news[0].title", startsWith("Diwali offer")))
                .andExpect(jsonPath("$.news[*].title", hasItems("Office closed on Saturday", "Tell your customers: free mesh", "New showroom in Kochi")))
                .andExpect(jsonPath("$.birthdays[*].name", hasItem("Amal Antony")))
                .andExpect(jsonPath("$.canManage").value(false));
        mockMvc.perform(get("/api/news/" + offer + "/image").header("Authorization", auth(staff)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        json(post("/api/news"), staff, "{\"title\":\"Mine\",\"category\":\"OTHER\",\"audience\":\"EVERYONE\"}").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/news/" + offer).header("Authorization", auth(staff))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/news").header("Authorization", auth(staff))).andExpect(status().isForbidden());

        // the website: only Public and All news showing today, and its picture; no sign-in
        mockMvc.perform(get("/api/public/news"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title", startsWith("Diwali offer")))
                .andExpect(jsonPath("$[1].title").value("New showroom in Kochi"))
                .andExpect(jsonPath("$[0].createdByName").doesNotExist());
        mockMvc.perform(get("/api/public/news/" + offer + "/image")).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/news/" + notice + "/image")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/news/feed")).andExpect(status().isUnauthorized());

        // hidden: off the dashboard, still in the admin's list; then deleted with its picture
        json(put("/api/news/" + offer), owner, """
                {"title":"Diwali offer: 15%% off uPVC windows","category":"DISCOUNT","audience":"EVERYONE","brandId":%d,"active":false}
                """.formatted(dw)).andExpect(jsonPath("$.status").value("HIDDEN")).andExpect(jsonPath("$.updatedByName").value("Amsidel Owner"));
        mockMvc.perform(get("/api/news/feed").header("Authorization", auth(staff))).andExpect(jsonPath("$.news.length()").value(3));
        mockMvc.perform(get("/api/public/news")).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/news").header("Authorization", auth(owner))).andExpect(jsonPath("$.length()").value(5));
        mockMvc.perform(delete("/api/news/" + offer).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/news").header("Authorization", auth(owner))).andExpect(jsonPath("$.length()").value(4));
        mockMvc.perform(get("/api/news/feed").header("Authorization", auth(owner))).andExpect(jsonPath("$.canManage").value(true));
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
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
