package com.data.schedular.api;

import com.data.schedular.domain.Checkpoint;
import com.data.schedular.repository.CheckpointRepository;
import com.data.schedular.repository.CollectionMappingRepository;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.DeadLetterRecordRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.scheduler.SchedulerService;
import com.data.schedular.support.EmbeddedDatabases;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The job and run API, end to end against embedded MongoDB and PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Scheduler quartz;
    @Autowired
    SchedulerService schedulerService;
    @Autowired
    ConnectionDefRepository connections;
    @Autowired
    MigrationJobRepository jobs;
    @Autowired
    JobRunRepository runs;
    @Autowired
    CheckpointRepository checkpoints;
    @Autowired
    DeadLetterRecordRepository deadLetters;
    @Autowired
    CollectionMappingRepository mappings;

    private MongoClient mongo;
    private String mongoDb;
    private String pgSchema;
    private long sourceId;
    private long targetId;

    @BeforeEach
    void setUp() throws Exception {
        deadLetters.deleteAll();
        runs.deleteAll();
        checkpoints.deleteAll();
        jobs.deleteAll();
        connections.deleteAll();
        schedulerService.reconcile();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        mongoDb = "api_" + suffix;
        pgSchema = "api_" + suffix;
        mongo = MongoClients.create(EmbeddedDatabases.mongoUri());
        try (Connection c = DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + pgSchema);
        }
        sourceId = createConnection("""
                {"name": "mongo", "dbType": "MONGODB", "uri": "%s", "database": "%s"}
                """.formatted(EmbeddedDatabases.mongoUri(), mongoDb));
        targetId = createConnection("""
                {"name": "pg", "dbType": "POSTGRESQL", "uri": "%s", "options": {"currentSchema": "%s"}}
                """.formatted(EmbeddedDatabases.postgresJdbcUrl(), pgSchema));
    }

    @AfterEach
    void tearDown() {
        mongo.getDatabase(mongoDb).drop();
        mongo.close();
    }

    @Test
    void createsAScheduledJobAndRunsItOnDemand() throws Exception {
        seedOrders(3);
        String body = job("orders", "0 0/15 * * * ?", "FULL", "UPSERT", """
                [{"sourceCollection": "orders", "targetTable": "orders",
                  "mapping": {"fields": [{"path": "status"}, {"path": "qty", "type": "INT"},
                    {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
                     "fields": [{"path": "sku"}]}]}}]""");

        MvcResult created = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/jobs/")))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.nextFireTime").value(notNullValue()))
                .andExpect(jsonPath("$.batchSize").value(1000))
                .andExpect(jsonPath("$.mappings[0].mapping.fields", hasSize(3)))
                .andExpect(jsonPath("$.lastRun").value(nullValue()))
                .andReturn();
        long jobId = id(created);
        assertThat(quartz.checkExists(JobKey.jobKey("job-" + jobId, "migration"))).isTrue();

        MvcResult started = mvc.perform(post("/api/jobs/{id}/run", jobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        long runId = id(started);
        assertThat(started.getResponse().getHeader("Location")).endsWith("/api/runs/" + runId);
        awaitFinished(runId);

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.docsRead").value(3))
                .andExpect(jsonPath("$.rowsWritten").value(3 + 6))
                .andExpect(jsonPath("$.nodeId").value("test-node"));
        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(jsonPath("$.lastRun.id").value(runId))
                .andExpect(jsonPath("$.lastRun.status").value("SUCCEEDED"));
        mvc.perform(get("/api/jobs/{id}/runs", jobId))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(count("select count(*) from order_items")).isEqualTo(6);
    }

    @Test
    void rejectsInvalidJobs() throws Exception {
        String mapping = "[{\"sourceCollection\": \"orders\", \"targetTable\": \"orders\"}]";
        expectBadRequest(job("j", "every hour", "FULL", "UPSERT", mapping), "Invalid cron expression");
        expectBadRequest(job("j", null, "FULL", "UPSERT", mapping).replace("Asia/Kolkata", "Mars/Olympus"),
                "Unknown timezone");
        expectBadRequest(job("j", null, "INCREMENTAL", "UPSERT", mapping), "needs a watermarkField");
        expectBadRequest(job("j", null, "FULL", "UPSERT", """
                [{"sourceCollection": "orders", "targetTable": "orders", "mapping": {"fields": [{"paht": "x"}]}}]"""),
                "paht");
        expectBadRequest(job("j", null, "FULL", "UPSERT", """
                [{"sourceCollection": "orders", "targetTable": "orders"},
                 {"sourceCollection": "orders_v2", "targetTable": "ORDERS"}]"""),
                "written by more than one mapping");
        expectBadRequest(job("j", null, "FULL", "UPSERT", """
                [{"sourceCollection": "orders", "targetTable": "orders", "filter": "status = 1"}]"""),
                "filter must be a JSON object");
        expectBadRequest(job("j", null, "FULL", "UPSERT", "[]"), null);
        expectBadRequest(job("j", null, "FULL", "UPSERT", mapping)
                .replace("\"sourceConnectionId\": " + sourceId, "\"sourceConnectionId\": " + targetId),
                "cannot be used as a source");
        expectBadRequest(job("j", null, "FULL", "UPSERT", mapping)
                .replace("\"sourceConnectionId\": " + sourceId, "\"sourceConnectionId\": 999999"),
                "does not exist");
        long mysql = createConnection("""
                {"name": "mysql", "dbType": "MYSQL", "host": "localhost", "database": "dw"}""");
        expectBadRequest(job("j", null, "FULL", "UPSERT", mapping)
                .replace("\"targetConnectionId\": " + targetId, "\"targetConnectionId\": " + mysql),
                "not supported yet");

        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(job("dup", null, "FULL", "UPSERT", mapping))).andExpect(status().isCreated());
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(job("dup", null, "FULL", "UPSERT", mapping)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/jobs/{id}", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void pausesResumesUpdatesAndDeletes() throws Exception {
        long jobId = id(mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(job("orders", "0 0 3 * * ?", "INCREMENTAL", "UPSERT", """
                                [{"sourceCollection": "orders", "targetTable": "orders",
                                  "watermarkField": "updatedAt"}]""")))
                .andExpect(status().isCreated()).andReturn());
        JobKey key = JobKey.jobKey("job-" + jobId, "migration");

        mvc.perform(post("/api/jobs/{id}/pause", jobId))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextFireTime").value(nullValue()));
        assertThat(quartz.checkExists(key)).isFalse();
        mvc.perform(post("/api/jobs/{id}/resume", jobId))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextFireTime").value(notNullValue()));
        assertThat(quartz.checkExists(key)).isTrue();

        // An update that keeps a collection -> table pair keeps that mapping row, and so its checkpoint.
        long mappingId = mappings.findByJobIdOrderByOrderIndex(jobId).getFirst().getId();
        Checkpoint checkpoint = new Checkpoint(mappingId, jobId);
        checkpoint.setLastWatermark("{\"v\": 1}");
        checkpoints.save(checkpoint);
        mvc.perform(put("/api/jobs/{id}", jobId).contentType(MediaType.APPLICATION_JSON)
                        .content(job("orders-renamed", null, "INCREMENTAL", "UPSERT", """
                                [{"sourceCollection": "orders", "targetTable": "orders", "watermarkField": "updatedAt",
                                  "mapping": {"fields": [{"path": "status"}]}},
                                 {"sourceCollection": "customers", "targetTable": "customers",
                                  "watermarkField": "updatedAt"}]""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("orders-renamed"))
                .andExpect(jsonPath("$.cron").value(nullValue()))
                .andExpect(jsonPath("$.nextFireTime").value(nullValue()))
                .andExpect(jsonPath("$.mappings[0].id").value(mappingId))
                .andExpect(jsonPath("$.mappings[1].sourceCollection").value("customers"));
        assertThat(quartz.checkExists(key)).isFalse();
        assertThat(checkpoints.findById(mappingId)).isPresent();

        mvc.perform(post("/api/jobs/{id}/reset-checkpoint", jobId)).andExpect(status().isNoContent());
        assertThat(checkpoints.findById(mappingId)).isEmpty();

        mvc.perform(delete("/api/jobs/{id}", jobId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/{id}", jobId)).andExpect(status().isNotFound());
    }

    @Test
    void refusesOverlappingRunsAndCancelsARunningOne() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            docs.add(new Document("_id", i).append("status", "S" + i));
        }
        mongo.getDatabase(mongoDb).getCollection("orders").insertMany(docs);
        long jobId = id(mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(job("big", null, "FULL", "UPSERT", """
                        [{"sourceCollection": "orders", "targetTable": "orders",
                          "mapping": {"primaryKey": {"type": "INT"}, "fields": [{"path": "status"}]}}]""")
                        .replace("\"batchSize\": 1000", "\"batchSize\": 20"))).andReturn());

        long runId = id(mvc.perform(post("/api/jobs/{id}/run", jobId)).andExpect(status().isAccepted()).andReturn());
        mvc.perform(post("/api/jobs/{id}/run", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already running")));
        mvc.perform(delete("/api/jobs/{id}", jobId)).andExpect(status().isConflict());
        mvc.perform(post("/api/jobs/{id}/reset-checkpoint", jobId)).andExpect(status().isConflict());

        // Cancel once the run is under way, so there is a partial result and a checkpoint to resume from.
        await().atMost(Duration.ofSeconds(30)).until(() -> runs.findById(runId).orElseThrow().getRowsWritten() > 0);
        mvc.perform(post("/api/runs/{id}/cancel", runId)).andExpect(status().isAccepted());
        awaitFinished(runId);
        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        long written = count("select count(*) from orders");
        assertThat(written).isGreaterThan(0).isLessThan(30_000);
        // The checkpoint of the cancelled run is kept: the next run continues where it stopped.
        assertThat(checkpoints.findAll()).singleElement().satisfies(c -> assertThat(c.getLastId()).isNotNull());
        mvc.perform(post("/api/runs/{id}/cancel", runId)).andExpect(status().isConflict());

        long resumed = id(mvc.perform(post("/api/jobs/{id}/run", jobId)).andReturn());
        awaitFinished(resumed);
        mvc.perform(get("/api/runs/{id}", resumed))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.docsRead").value(30_000 - (int) written));
        assertThat(count("select count(*) from orders")).isEqualTo(30_000);
    }

    @Test
    void exposesDeadLetters() throws Exception {
        MongoCollection<Document> orders = mongo.getDatabase(mongoDb).getCollection("orders");
        orders.insertMany(List.of(new Document("_id", 1).append("qty", 5),
                new Document("_id", 2).append("qty", "many")));
        long jobId = id(mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(job("dl", null, "FULL", "UPSERT", """
                        [{"sourceCollection": "orders", "targetTable": "orders",
                          "mapping": {"primaryKey": {"type": "INT"}, "fields": [{"path": "qty", "type": "INT"}]}}]
                        """))).andReturn());
        long runId = id(mvc.perform(post("/api/jobs/{id}/run", jobId)).andReturn());
        awaitFinished(runId);

        mvc.perform(get("/api/runs/{id}", runId)).andExpect(jsonPath("$.status").value("PARTIAL"));
        mvc.perform(get("/api/runs/{id}/dead-letters", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sourceId").value("2"))
                .andExpect(jsonPath("$.content[0].error").value(containsString("column 'qty'")))
                .andExpect(jsonPath("$.content[0].document").value(containsString("many")));
        mvc.perform(get("/api/runs/{id}/dead-letters", runId).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/runs/{id}", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void infersAMappingThatCanBeUsedAsIs() throws Exception {
        seedOrders(20);
        MvcResult inferred = mvc.perform(get("/api/connections/{id}/collections/orders/mapping", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTable").value("orders"))
                .andExpect(jsonPath("$.sampledDocuments").value(20))
                .andExpect(jsonPath("$.watermarkField").value("updatedAt"))
                .andExpect(jsonPath("$.mapping.unmappedFields").value("JSON_OVERFLOW_COLUMN"))
                .andReturn();
        String mappingJson = JsonPath.parse(inferred.getResponse().getContentAsString()).read("$.mapping",
                net.minidev.json.JSONObject.class).toJSONString();

        long jobId = id(mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(job("inferred", null, "INCREMENTAL", "UPSERT", """
                                [{"sourceCollection": "orders", "targetTable": "orders", "watermarkField": "updatedAt",
                                  "mapping": %s}]""".formatted(mappingJson))))
                .andExpect(status().isCreated()).andReturn());
        long runId = id(mvc.perform(post("/api/jobs/{id}/run", jobId)).andReturn());
        awaitFinished(runId);

        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.docsRead").value(20));
        assertThat(count("select count(*) from orders where customer_name is not null and qty is not null"))
                .isEqualTo(20);
        assertThat(count("select count(*) from orders_items")).isEqualTo(40);
        mvc.perform(get("/api/connections/{id}/collections/orders/mapping", targetId))
                .andExpect(status().isBadRequest());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private String job(String name, String cron, String syncMode, String writeMode, String mappings) {
        return """
                {"name": "%s", "sourceConnectionId": %d, "targetConnectionId": %d, %s
                 "timezone": "Asia/Kolkata", "syncMode": "%s", "writeMode": "%s", "batchSize": 1000,
                 "mappings": %s}
                """.formatted(name, sourceId, targetId, cron == null ? "" : "\"cron\": \"" + cron + "\",",
                syncMode, writeMode, mappings);
    }

    private void seedOrders(int n) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            docs.add(new Document("_id", new ObjectId())
                    .append("status", i % 2 == 0 ? "NEW" : "SHIPPED")
                    .append("qty", i)
                    .append("customer", new Document("name", "Customer " + i).append("tier", i % 3))
                    .append("items", List.of(new Document("sku", "A-" + i), new Document("sku", "B-" + i)))
                    .append("updatedAt", new Date(1_750_000_000_000L + i * 1000L)));
        }
        mongo.getDatabase(mongoDb).getCollection("orders").insertMany(docs);
    }

    private long createConnection(String json) throws Exception {
        return id(mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn());
    }

    private void expectBadRequest(String body, String detail) throws Exception {
        ResultActions result = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        if (detail != null) {
            result.andExpect(jsonPath("$.detail").value(containsString(detail)));
        }
    }

    private void awaitFinished(long runId) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> runs.findById(runId).orElseThrow().getEndedAt() != null);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long count(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                EmbeddedDatabases.postgresJdbcUrl() + "&currentSchema=" + pgSchema);
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
