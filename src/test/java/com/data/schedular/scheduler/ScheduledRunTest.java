package com.data.schedular.scheduler;

import com.data.schedular.api.dto.JobRequest;
import com.data.schedular.api.dto.JobResponse;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.DbType;
import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.TriggerType;
import com.data.schedular.repository.CheckpointRepository;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.DeadLetterRecordRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.service.JobService;
import com.data.schedular.support.EmbeddedDatabases;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A live Quartz scheduler firing a cron job (other test classes keep the scheduler stopped). */
@SpringBootTest(properties = "spring.quartz.auto-startup=true")
@ActiveProfiles("test")
class ScheduledRunTest {

    @Autowired
    JobService jobService;
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

    @Test
    void firesOnTheCronScheduleAndStopsWhenPaused() throws Exception {
        deadLetters.deleteAll();
        runs.deleteAll();
        checkpoints.deleteAll();
        jobs.deleteAll();
        connections.deleteAll();
        schedulerService.reconcile();
        try (MongoClient mongo = MongoClients.create(EmbeddedDatabases.mongoUri())) {
            mongo.getDatabase("scheduled").getCollection("events").drop();
            mongo.getDatabase("scheduled").getCollection("events").insertMany(List.of(
                    new Document("_id", "e1").append("kind", "click"),
                    new Document("_id", "e2").append("kind", "view")));
        }
        try (Connection c = DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS scheduled");
        }
        ConnectionDef source = new ConnectionDef();
        source.setName("scheduled-mongo");
        source.setDbType(DbType.MONGODB);
        source.setUri(EmbeddedDatabases.mongoUri());
        source.setDatabase("scheduled");
        ConnectionDef target = new ConnectionDef();
        target.setName("scheduled-pg");
        target.setDbType(DbType.POSTGRESQL);
        target.setUri(EmbeddedDatabases.postgresJdbcUrl());
        target.setOptions(Map.of("currentSchema", "scheduled"));

        JobResponse job = jobService.create(new JobRequest("every-second", connections.save(source).getId(),
                connections.save(target).getId(), "* * * * * ?", null, null, null, null, null, null, null,
                List.of(new JobRequest.MappingRequest("events", "events", null, null, null))));

        await().atMost(Duration.ofSeconds(30)).until(() -> runsOf(job.id()).stream()
                .anyMatch(r -> r.getStatus() == RunStatus.SUCCEEDED));
        JobRun first = runsOf(job.id()).stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED)
                .findFirst().orElseThrow();
        assertThat(first.getTriggerType()).isEqualTo(TriggerType.CRON);
        assertThat(first.getDocsRead()).isEqualTo(2);

        jobService.pause(job.id());
        Thread.sleep(1_500); // a firing acquired just before the pause may still start
        await().atMost(Duration.ofSeconds(30)).until(() -> runsOf(job.id()).stream()
                .noneMatch(r -> r.getStatus() == RunStatus.RUNNING));
        int afterPause = runsOf(job.id()).size();
        Thread.sleep(2_500);
        assertThat(runsOf(job.id())).hasSize(afterPause);

        jobService.delete(job.id());
    }

    private List<JobRun> runsOf(Long jobId) {
        return runs.findByJobIdOrderByStartedAtDescIdDesc(jobId, Pageable.unpaged()).getContent();
    }
}
