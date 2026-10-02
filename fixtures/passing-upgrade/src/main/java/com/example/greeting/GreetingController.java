package com.example.greeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GreetingController {

    private final GreetingService greetings;

    public GreetingController(GreetingService greetings) {
        this.greetings = greetings;
    }

    @GetMapping("/api/greeting")
    public Map<String, String> greeting(@RequestParam(required = false) String name) {
        return Map.of("message", greetings.greet(name));
    }
}
