package com.example.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(OrderService.class)
class OrderControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsOrders() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void returnsOneOrder() throws Exception {
        mvc.perform(get("/api/orders/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").value("linus"));
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound());
    }

    // Existing API clients call these URLs with a trailing slash.
    @Test
    void listsOrdersWithTrailingSlash() throws Exception {
        mvc.perform(get("/api/orders/"))
                .andExpect(status().isOk());
    }

    @Test
    void returnsOneOrderWithTrailingSlash() throws Exception {
        mvc.perform(get("/api/orders/1/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").value("ada"));
    }
}
