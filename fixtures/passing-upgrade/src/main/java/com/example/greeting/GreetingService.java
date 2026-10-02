package com.example.greeting;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class GreetingService {

    public String greet(String name) {
        String clean = StringUtils.isBlank(name) ? "world" : StringUtils.capitalize(name.strip());
        return "Hello, " + StringUtils.abbreviate(clean, 20) + "!";
    }
}
