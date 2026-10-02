package com.example.orders;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceTest {

    private final OrderService service = new OrderService();

    @Test
    void computesRevenue() {
        assertThat(service.revenue()).isEqualByComparingTo(new BigDecimal("311.48"));
    }

    @Test
    void findsOrderById() {
        assertThat(service.findById(3)).get().extracting(Order::customer).isEqualTo("grace");
    }
}
