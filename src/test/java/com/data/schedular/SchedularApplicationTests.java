package com.data.schedular;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Boots the full context: Flyway migrations run and Hibernate validates the entities against them. */
@SpringBootTest
@ActiveProfiles("test")
class SchedularApplicationTests {

    @Test
    void contextLoads() {
    }

}
