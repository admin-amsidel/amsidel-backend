package com.amsidel.backend.config;

import com.amsidel.backend.controller.ProductController;
import com.amsidel.backend.service.ProductService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    // Required because @EnableJpaAuditing sits on the main application class
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.amsidel.com",
            "https://amsidel.com",
            "https://amsidel-web.info-amsidel.workers.dev",
            "http://localhost:4200"
    })
    void allowsKnownOrigins(String origin) throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.com", "https://amsidel.com.evil.net"})
    void rejectsUnknownOrigins(String origin) throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
