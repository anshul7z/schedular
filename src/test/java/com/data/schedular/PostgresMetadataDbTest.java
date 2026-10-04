package com.data.schedular;

import com.data.schedular.domain.CollectionMapping;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.DbType;
import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.MigrationJob;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.TriggerType;
import com.data.schedular.engine.MigrationExecutor;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.support.EmbeddedDatabases;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application with PostgreSQL as the metadata DB (the production setup): Flyway migrations and
 * Hibernate validation must pass on real PostgreSQL, not only on H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostgresMetadataDbTest {

    private static final String META_DB = "schedular_meta";
    private static final String TARGET_DB = "meta_test_target";

    @DynamicPropertySource
    static void metadataDb(DynamicPropertyRegistry registry) throws SQLException {
        createDatabase(META_DB);
        createDatabase(TARGET_DB);
        registry.add("spring.datasource.url",
                () -> EmbeddedDatabases.postgres().getJdbcUrl("postgres", META_DB));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ConnectionDefRepository connections;
    @Autowired
    MigrationJobRepository jobs;
    @Autowired
    MigrationExecutor executor;

    @Test
    void appliesMigrationsAndRunsAJobOnPostgres() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(META_DB);
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank",
                String.class)).containsExactly("1", "2");
        assertThat(jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_name = 'checkpoint' and column_name = 'last_id'""", Integer.class)).isEqualTo(2000);

        try (MongoClient mongo = MongoClients.create(EmbeddedDatabases.mongoUri())) {
            mongo.getDatabase("meta_test").getCollection("customers").drop();
            mongo.getDatabase("meta_test").getCollection("customers").insertMany(List.of(
                    new Document("_id", "c1").append("name", "Asha").append("tags", List.of("vip")),
                    new Document("_id", "c2").append("name", "Daniel")));
        }
        ConnectionDef source = new ConnectionDef();
        source.setName("mongo");
        source.setDbType(DbType.MONGODB);
        source.setUri(EmbeddedDatabases.mongoUri());
        source.setDatabase("meta_test");
        ConnectionDef target = new ConnectionDef();
        target.setName("pg");
        target.setDbType(DbType.POSTGRESQL);
        target.setUri(EmbeddedDatabases.postgres().getJdbcUrl("postgres", TARGET_DB));
        target.setOptions(Map.of("ApplicationName", "schedular-test"));
        MigrationJob job = new MigrationJob();
        job.setName("customers");
        job.setSourceConnection(connections.save(source));
        job.setTargetConnection(connections.save(target));
        CollectionMapping mapping = new CollectionMapping();
        mapping.setSourceCollection("customers");
        mapping.setTargetTable("customers");
        mapping.setMappingJson("""
                {"fields": [{"path": "name"},
                  {"path": "tags", "strategy": "CHILD_TABLE", "childTable": "customer_tags"}]}""");
        job.addMapping(mapping);
        job = jobs.save(job);

        JobRun run = executor.run(job.getId(), TriggerType.MANUAL);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getRowsWritten()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select status from job_run where id = ?", String.class, run.getId()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from checkpoint", Integer.class)).isEqualTo(1);
    }

    private static void createDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl());
             ResultSet rs = c.createStatement().executeQuery(
                     "select 1 from pg_database where datname = '" + name + "'")) {
            if (!rs.next()) {
                c.createStatement().execute("CREATE DATABASE " + name);
            }
        }
    }
}
