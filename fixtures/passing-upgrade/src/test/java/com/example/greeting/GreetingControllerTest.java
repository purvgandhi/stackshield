package com.example.greeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GreetingController.class)
@Import(GreetingService.class)
class GreetingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsGreeting() throws Exception {
        mvc.perform(get("/api/greeting").param("name", "grace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello, Grace!"));
    }

    @Test
    void contextHasDefaultGreeting() throws Exception {
        mvc.perform(get("/api/greeting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello, world!"));
    }
}
