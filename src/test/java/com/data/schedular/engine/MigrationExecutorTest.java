package com.data.schedular.engine;

import com.data.schedular.domain.Checkpoint;
import com.data.schedular.domain.CollectionMapping;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.DbType;
import com.data.schedular.domain.DeadLetterRecord;
import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.MigrationJob;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.TriggerType;
import com.data.schedular.domain.WriteMode;
import com.data.schedular.engine.mapping.BsonValues;
import com.data.schedular.repository.CheckpointRepository;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.DeadLetterRecordRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.service.ConflictException;
import com.data.schedular.support.EmbeddedDatabases;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end runs against real (embedded) MongoDB and PostgreSQL processes. */
@SpringBootTest
@ActiveProfiles("test")
class MigrationExecutorTest {

    private static final String ORDERS_MAPPING = """
            {"fields": [
              {"path": "status", "length": 20},
              {"path": "total", "type": "DECIMAL"},
              {"path": "customer.name"},
              {"path": "updatedAt", "column": "updated_at", "type": "TIMESTAMP"},
              {"path": "meta", "strategy": "JSON"},
              {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
               "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}, {"path": "price", "type": "DECIMAL"}]},
              {"path": "tags", "strategy": "CHILD_TABLE", "childTable": "order_tags"}
            ]}
            """;

    @Autowired
    MigrationExecutor executor;
    @Autowired
    StaleRunCleaner staleRunCleaner;
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

    private MongoClient mongo;
    private MongoCollection<Document> orders;
    private String mongoDb;
    private String pgSchema;

    @BeforeEach
    void setUp() throws SQLException {
        deadLetters.deleteAll();
        runs.deleteAll();
        checkpoints.deleteAll();
        jobs.deleteAll();
        connections.deleteAll();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        mongoDb = "shop_" + suffix;
        pgSchema = "t_" + suffix;
        mongo = MongoClients.create(EmbeddedDatabases.mongoUri());
        orders = mongo.getDatabase(mongoDb).getCollection("orders");
        try (Connection c = DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + pgSchema);
        }
    }

    @AfterEach
    void tearDown() {
        mongo.getDatabase(mongoDb).drop();
        mongo.close();
    }

    @Test
    void migratesNestedDocumentsIntoParentAndChildTables() throws SQLException {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, ORDERS_MAPPING, null, true);

        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getDocsRead()).isEqualTo(3);
        assertThat(run.getDocsFailed()).isZero();
        assertThat(run.getRowsWritten()).isEqualTo(3 + 3 + 2); // orders + order_items + order_tags
        assertThat(run.getEndedAt()).isNotNull();

        Map<String, Object> first = row("""
                select status, total, customer_name, updated_at, meta->>'source' as source,
                       meta->'utm'->>'campaign' as campaign
                from orders where id = '660000000000000000000001'""");
        assertThat(first.get("status")).isEqualTo("SHIPPED");
        assertThat(first.get("total")).isEqualTo(new BigDecimal("149.97"));
        assertThat(first.get("customer_name")).isEqualTo("Asha Rao");
        assertThat(((OffsetDateTime) first.get("updated_at")).toInstant())
                .isEqualTo(Instant.parse("2026-03-02T09:15:00Z"));
        assertThat(first.get("source")).isEqualTo("web");
        assertThat(first.get("campaign")).isEqualTo("spring");

        assertThat(rows("select sku, qty, price from order_items where parent_id = '660000000000000000000001' "
                + "order by ordinal")).containsExactly(
                Map.of("sku", "BOOK-001", "qty", 2, "price", new BigDecimal("24.99")),
                Map.of("sku", "LAMP-010", "qty", 1, "price", new BigDecimal("99.99")));
        assertThat(rows("select value from order_tags order by ordinal"))
                .extracting(r -> r.get("value")).containsExactly("gift", "express");
        assertThat(count("orders where meta is null and status = 'NEW'")).isEqualTo(1);
        assertThat(checkpoints.findAll()).allSatisfy(c -> assertThat(c.getLastId()).isNull());
    }

    @Test
    void rerunIsIdempotentAndReflectsSourceChanges() throws SQLException {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, ORDERS_MAPPING, null, true);
        executor.run(job.getId(), TriggerType.MANUAL);

        orders.updateOne(Filters.eq("_id", new ObjectId("660000000000000000000001")), Updates.combine(
                Updates.set("status", "DELIVERED"),
                Updates.set("items", List.of(new Document("sku", "BOOK-001").append("qty", 3)))));
        JobRun second = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(second.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.getDocsRead()).isEqualTo(3);
        assertThat(count("orders")).isEqualTo(3);
        assertThat(row("select status from orders where id = '660000000000000000000001'").get("status"))
                .isEqualTo("DELIVERED");
        assertThat(rows("select sku, qty from order_items where parent_id = '660000000000000000000001'"))
                .containsExactly(Map.of("sku", "BOOK-001", "qty", 3));
        assertThat(count("order_items")).isEqualTo(2);
    }

    @Test
    void migratesManyDocumentsAcrossBatches() throws SQLException {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 2_345; i++) {
            docs.add(new Document("_id", i).append("status", "S" + (i % 7))
                    .append("items", List.of(new Document("sku", "SKU-" + i).append("qty", i % 5))));
        }
        orders.insertMany(docs);
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, """
                {"primaryKey": {"type": "INT"},
                 "fields": [{"path": "status"},
                   {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
                    "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}]}]}
                """, null, true);

        JobRun run = executor.run(job.getId(), TriggerType.CRON);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getDocsRead()).isEqualTo(2_345);
        assertThat(run.getRowsWritten()).isEqualTo(2 * 2_345);
        assertThat(count("orders")).isEqualTo(2_345);
        assertThat(count("order_items")).isEqualTo(2_345);
        assertThat(row("select max(id) as m from orders").get("m")).isEqualTo(2_344);
    }

    @Test
    void deadLettersBadDocumentsAndContinues() throws SQLException {
        seedOrders();
        orders.insertOne(new Document("_id", new ObjectId("660000000000000000000009"))
                .append("status", "BROKEN").append("total", "not a number"));
        // Accepted by the mapper but rejected by PostgreSQL (jsonb cannot hold \u0000): forces row-by-row retry.
        orders.insertOne(new Document("_id", new ObjectId("66000000000000000000000a"))
                .append("status", "BAD_JSON").append("meta", new Document("note", "nul\u0000byte")));
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, ORDERS_MAPPING, null, true);

        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getDocsRead()).isEqualTo(5);
        assertThat(run.getDocsFailed()).isEqualTo(2);
        assertThat(count("orders")).isEqualTo(3);
        List<DeadLetterRecord> dead = deadLetters.findByRunIdOrderById(run.getId(), Pageable.unpaged()).getContent();
        assertThat(dead).extracting(DeadLetterRecord::getSourceId)
                .containsExactlyInAnyOrder("660000000000000000000009", "66000000000000000000000a");
        assertThat(dead).filteredOn(d -> d.getSourceId().endsWith("9")).singleElement()
                .satisfies(d -> {
                    assertThat(d.getError()).contains("column 'total'");
                    assertThat(d.getPayloadJson()).contains("not a number");
                    assertThat(d.getCollectionName()).isEqualTo("orders");
                });
        assertThat(dead).filteredOn(d -> d.getSourceId().endsWith("a")).singleElement()
                .satisfies(d -> assertThat(d.getError()).startsWith("[22"));
    }

    @Test
    void resumesAfterTheLastCheckpointOfAnInterruptedRun() throws SQLException {
        for (int i = 1; i <= 5; i++) {
            orders.insertOne(new Document("_id", i).append("status", "S" + i));
        }
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, """
                {"primaryKey": {"type": "INT"}, "fields": [{"path": "status"}]}""", null, true);
        // Simulate an earlier run that committed documents 1 and 2 and then died.
        Checkpoint interrupted = new Checkpoint(job.getMappings().getFirst().getId(), job.getId());
        interrupted.setLastId(BsonValues.encodeValue(2));
        checkpoints.save(interrupted);

        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getDocsRead()).isEqualTo(3);
        assertThat(rows("select id from orders order by id")).extracting(r -> r.get("id"))
                .containsExactly(3, 4, 5);
        assertThat(checkpoints.findById(interrupted.getCollectionMappingId()).orElseThrow().getLastId()).isNull();
    }

    @Test
    void incrementalSyncReadsOnlyNewAndChangedDocuments() throws SQLException {
        seedOrders();
        MigrationJob job = job(SyncMode.INCREMENTAL, WriteMode.UPSERT, ORDERS_MAPPING, "updatedAt", true);
        JobRun first = executor.run(job.getId(), TriggerType.CRON);
        assertThat(first.getDocsRead()).isEqualTo(3);

        orders.updateOne(Filters.eq("_id", new ObjectId("660000000000000000000001")), Updates.combine(
                Updates.set("status", "RETURNED"), Updates.set("updatedAt", date("2026-04-01T00:00:00Z"))));
        orders.insertOne(new Document("_id", new ObjectId("660000000000000000000004"))
                .append("status", "NEW").append("updatedAt", date("2026-04-02T00:00:00Z")));
        JobRun second = executor.run(job.getId(), TriggerType.CRON);

        // The changed and the new document, plus the previous high-water-mark document (re-read on purpose).
        assertThat(second.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.getDocsRead()).isEqualTo(3);
        assertThat(count("orders")).isEqualTo(4);
        assertThat(row("select status from orders where id = '660000000000000000000001'").get("status"))
                .isEqualTo("RETURNED");

        JobRun third = executor.run(job.getId(), TriggerType.CRON);
        assertThat(third.getDocsRead()).isEqualTo(1);
    }

    @Test
    void truncateAndLoadRemovesRowsDeletedAtTheSource() throws SQLException {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.TRUNCATE_AND_LOAD, ORDERS_MAPPING, null, true);
        executor.run(job.getId(), TriggerType.MANUAL);

        orders.deleteOne(Filters.eq("_id", new ObjectId("660000000000000000000001")));
        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(count("order_items")).isEqualTo(1);
    }

    @Test
    void addsColumnsWhenTheMappingGrows() throws SQLException {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, """
                {"fields": [{"path": "status"}]}""", null, true);
        executor.run(job.getId(), TriggerType.MANUAL);

        CollectionMapping mapping = job.getMappings().getFirst();
        mapping.setMappingJson("{\"fields\": [{\"path\": \"status\"}, {\"path\": \"total\", \"type\": \"DECIMAL\"}]}");
        jobs.save(job);
        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(row("select total from orders where id = '660000000000000000000002'").get("total"))
                .isEqualTo(new BigDecimal("12.50"));
    }

    @Test
    void failsWithAClearMessageWhenTheTargetSchemaIsUnusable() throws SQLException {
        seedOrders();
        MigrationJob noAutoCreate = job(SyncMode.FULL, WriteMode.UPSERT, "{}", null, false);
        JobRun missingTable = executor.run(noAutoCreate.getId(), TriggerType.MANUAL);
        assertThat(missingTable.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(missingTable.getErrorMessage()).contains("'orders' does not exist");

        execute("CREATE TABLE orders (id varchar(255))"); // no primary key
        JobRun noKey = executor.run(noAutoCreate.getId(), TriggerType.MANUAL);
        assertThat(noKey.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(noKey.getErrorMessage()).contains("no primary key or unique index");
    }

    @Test
    void failsOnAnInvalidMapping() {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, "{\"fields\": [{\"paht\": \"x\"}]}", null, true);

        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("Mapping JSON is invalid").contains("paht");
    }

    @Test
    void closesRunsLeftRunningByACrash() {
        seedOrders();
        MigrationJob job = job(SyncMode.FULL, WriteMode.UPSERT, ORDERS_MAPPING, null, true);
        JobRun stale = runs.save(JobRun.start(job.getId(), TriggerType.CRON));

        assertThatThrownBy(() -> executor.run(job.getId(), TriggerType.MANUAL))
                .isInstanceOf(ConflictException.class);
        staleRunCleaner.closeStaleRuns();

        JobRun closed = runs.findById(stale.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(closed.getErrorMessage()).contains("Interrupted");
        assertThat(executor.run(job.getId(), TriggerType.MANUAL).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void seedOrders() {
        orders.insertMany(List.of(
                new Document("_id", new ObjectId("660000000000000000000001"))
                        .append("status", "SHIPPED")
                        .append("total", new Decimal128(new BigDecimal("149.97")))
                        .append("customer", new Document("name", "Asha Rao"))
                        .append("items", List.of(
                                new Document("sku", "BOOK-001").append("qty", 2)
                                        .append("price", new Decimal128(new BigDecimal("24.99"))),
                                new Document("sku", "LAMP-010").append("qty", 1)
                                        .append("price", new Decimal128(new BigDecimal("99.99")))))
                        .append("tags", List.of("gift", "express"))
                        .append("meta", new Document("source", "web").append("utm", new Document("campaign", "spring")))
                        .append("updatedAt", date("2026-03-02T09:15:00Z")),
                new Document("_id", new ObjectId("660000000000000000000002"))
                        .append("status", "PENDING")
                        .append("total", new Decimal128(new BigDecimal("12.50")))
                        .append("customer", new Document("name", "Daniel Kim"))
                        .append("items", List.of(new Document("sku", "PEN-100").append("qty", 5)))
                        .append("meta", new Document("source", "app"))
                        .append("updatedAt", date("2026-03-05T17:40:00Z")),
                new Document("_id", new ObjectId("660000000000000000000003"))
                        .append("status", "NEW")
                        .append("updatedAt", date("2026-03-06T08:00:00Z"))));
    }

    private MigrationJob job(SyncMode syncMode, WriteMode writeMode, String mappingJson, String watermarkField,
                             boolean autoCreateSchema) {
        ConnectionDef source = new ConnectionDef();
        source.setName("mongo-" + mongoDb);
        source.setDbType(DbType.MONGODB);
        source.setUri(EmbeddedDatabases.mongoUri());
        source.setDatabase(mongoDb);
        source = connections.save(source);

        ConnectionDef target = new ConnectionDef();
        target.setName("pg-" + pgSchema);
        target.setDbType(DbType.POSTGRESQL);
        target.setUri(EmbeddedDatabases.postgresJdbcUrl());
        target.setOptions(Map.of("currentSchema", pgSchema));
        target = connections.save(target);

        MigrationJob job = new MigrationJob();
        job.setName("orders-" + pgSchema + "-" + UUID.randomUUID());
        job.setSourceConnection(source);
        job.setTargetConnection(target);
        job.setSyncMode(syncMode);
        job.setWriteMode(writeMode);
        job.setBatchSize(1000);
        job.setAutoCreateSchema(autoCreateSchema);
        CollectionMapping mapping = new CollectionMapping();
        mapping.setSourceCollection("orders");
        mapping.setTargetTable("orders");
        mapping.setMappingJson(mappingJson);
        mapping.setWatermarkField(watermarkField);
        job.addMapping(mapping);
        return jobs.save(job);
    }

    private static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    private Connection pg() throws SQLException {
        return DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl() + "&currentSchema=" + pgSchema);
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = pg(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long count(String fromClause) throws SQLException {
        return ((Number) row("select count(*) as n from " + fromClause).get("n")).longValue();
    }

    private Map<String, Object> row(String sql) throws SQLException {
        List<Map<String, Object>> rows = rows(sql);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> rows(String sql) throws SQLException {
        try (Connection c = pg(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<Map<String, Object>> out = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new java.util.HashMap<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = rs.getMetaData().getColumnTypeName(i).equals("timestamptz")
                            ? rs.getObject(i, OffsetDateTime.class)
                            : rs.getObject(i);
                    row.put(rs.getMetaData().getColumnLabel(i), value);
                }
                out.add(row);
            }
            return out;
        }
    }
}
