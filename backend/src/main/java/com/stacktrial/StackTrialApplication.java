package com.stacktrial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StackTrialApplication {

    public static void main(String[] args) {
        SpringApplication.run(StackTrialApplication.class, args);
    }
}
