package com.example.greeting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GreetingServiceTest {

    private final GreetingService service = new GreetingService();

    @Test
    void greetsByCapitalizedName() {
        assertThat(service.greet("ada")).isEqualTo("Hello, Ada!");
    }

    @Test
    void fallsBackToWorld() {
        assertThat(service.greet("  ")).isEqualTo("Hello, world!");
    }

    @Test
    void abbreviatesLongNames() {
        assertThat(service.greet("bartholomew-alexander-the-third")).isEqualTo("Hello, Bartholomew-alexa...!");
    }
}
