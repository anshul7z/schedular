package com.data.schedular;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SchedularApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedularApplication.class, args);
    }

}
