package com.data.schedular.api;

import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.MigrationJob;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.MigrationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionControllerTest {

    private static final String PG_TARGET = """
            {"name": "warehouse", "dbType": "POSTGRESQL", "host": "127.0.0.1", "port": 1,
             "database": "dw", "username": "app", "password": "hunter2",
             "options": {"sslmode": "disable"}}
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ConnectionDefRepository connections;
    @Autowired
    MigrationJobRepository jobs;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jobs.deleteAll();
        connections.deleteAll();
    }

    @Test
    void createsAndReadsConnectionWithoutExposingPassword() throws Exception {
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(PG_TARGET))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/connections/")))
                .andExpect(jsonPath("$.kind").value("TARGET"))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.options.sslmode").value("disable"))
                .andExpect(jsonPath("$.password").doesNotExist());

        Long id = connections.findAll().getFirst().getId();
        mvc.perform(get("/api/connections/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("warehouse"))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/connections")).andExpect(jsonPath("$", hasSize(1)));

        String stored = jdbc.queryForObject("select password_enc from connection_def where id = ?", String.class, id);
        assertThat(stored).startsWith("v1:").doesNotContain("hunter2");
    }

    @Test
    void updateKeepsPasswordWhenOmittedAndClearsItWhenEmpty() throws Exception {
        Long id = create(PG_TARGET);

        mvc.perform(put("/api/connections/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "warehouse-2", "dbType": "POSTGRESQL", "host": "127.0.0.1", "database": "dw"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("warehouse-2"))
                .andExpect(jsonPath("$.passwordSet").value(true));

        mvc.perform(put("/api/connections/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "warehouse-2", "dbType": "POSTGRESQL", "host": "127.0.0.1", "database": "dw",
                         "password": ""}
                        """))
                .andExpect(jsonPath("$.passwordSet").value(false));
    }

    @Test
    void rejectsDuplicateName() throws Exception {
        create(PG_TARGET);

        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(PG_TARGET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already exists")));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbType\": \"MYSQL\", \"host\": \"x\", \"database\": \"d\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"m\", \"dbType\": \"MONGODB\", \"uri\": \"mongodb://u:p@h/db\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("password field")));
    }

    @Test
    void returns404ForUnknownConnection() throws Exception {
        mvc.perform(get("/api/connections/{id}", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void reportsUnreachableDatabaseAsFailedTest() throws Exception {
        Long id = create(PG_TARGET);

        mvc.perform(post("/api/connections/{id}/test", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void testsUnsavedSettings() throws Exception {
        mvc.perform(post("/api/connections/test").contentType(MediaType.APPLICATION_JSON).content(PG_TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(connections.count()).isZero();
    }

    @Test
    void deletesUnusedConnectionButNotOneUsedByAJob() throws Exception {
        Long targetId = create(PG_TARGET);
        Long sourceId = create("""
                {"name": "shop", "dbType": "MONGODB", "host": "127.0.0.1", "database": "shop"}
                """);
        Long spareId = create("""
                {"name": "spare", "dbType": "MYSQL", "host": "127.0.0.1", "database": "dw"}
                """);
        MigrationJob job = new MigrationJob();
        job.setName("orders");
        job.setSourceConnection(connections.findById(sourceId).orElseThrow());
        job.setTargetConnection(connections.findById(targetId).orElseThrow());
        jobs.save(job);

        mvc.perform(delete("/api/connections/{id}", targetId)).andExpect(status().isConflict());
        mvc.perform(delete("/api/connections/{id}", spareId)).andExpect(status().isNoContent());
        assertThat(connections.findById(spareId)).isEmpty();
    }

    private Long create(String json) throws Exception {
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        return connections.findAll().stream()
                .map(ConnectionDef::getId)
                .max(Long::compare)
                .orElseThrow();
    }
}
