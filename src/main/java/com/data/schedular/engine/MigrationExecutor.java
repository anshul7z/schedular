package com.data.schedular.engine;

import com.data.schedular.config.SchedularProperties;
import com.data.schedular.domain.Checkpoint;
import com.data.schedular.domain.CollectionMapping;
import com.data.schedular.domain.ConnectionKind;
import com.data.schedular.domain.DeadLetterRecord;
import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.MigrationJob;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.TriggerType;
import com.data.schedular.domain.WriteMode;
import com.data.schedular.engine.JobSnapshot.MappingSnapshot;
import com.data.schedular.engine.RunContext.RunCancelledException;
import com.data.schedular.engine.mapping.BsonValues;
import com.data.schedular.engine.mapping.DocumentMappingException;
import com.data.schedular.engine.mapping.DocumentPaths;
import com.data.schedular.engine.mapping.InvalidMappingException;
import com.data.schedular.engine.mapping.MappingCompiler;
import com.data.schedular.engine.mapping.MappingEngine;
import com.data.schedular.engine.mapping.MappingEngine.MappedDocument;
import com.data.schedular.engine.mapping.MappingPlan;
import com.data.schedular.engine.source.ReadRequest;
import com.data.schedular.engine.source.SourceConnector;
import com.data.schedular.engine.source.SourceConnector.BatchCursor;
import com.data.schedular.engine.source.SourceConnectorFactory;
import com.data.schedular.engine.source.SourceRecord;
import com.data.schedular.engine.target.JdbcTargetWriter;
import com.data.schedular.engine.target.SchemaManager;
import com.data.schedular.engine.target.TargetSchemaException;
import com.data.schedular.engine.target.dialect.DialectFactory;
import com.data.schedular.engine.target.dialect.SqlDialect;
import com.data.schedular.repository.CheckpointRepository;
import com.data.schedular.repository.DeadLetterRecordRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.service.ConflictException;
import com.data.schedular.service.NotFoundException;
import com.data.schedular.service.connectivity.ConnectionResolver;
import com.data.schedular.service.connectivity.ResolvedConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs one migration job: for each collection mapping, reads batches from the source, maps them to rows,
 * writes each batch to the target in one transaction, then records the checkpoint.
 * <p>
 * Guarantees: a batch's target commit happens before its checkpoint is saved, and writes are upserts, so
 * re-running after any failure re-writes at most one batch and never duplicates rows.
 * Documents that cannot be mapped or are rejected by the target are dead-lettered; the run continues.
 */
@Service
public class MigrationExecutor {

    private static final Logger log = LoggerFactory.getLogger(MigrationExecutor.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);
    private static final Duration CANCEL_POLL = Duration.ofMillis(100);

    private final MigrationJobRepository jobs;
    private final JobRunRepository runs;
    private final CheckpointRepository checkpoints;
    private final DeadLetterRecordRepository deadLetters;
    private final ConnectionResolver resolver;
    private final SourceConnectorFactory sources;
    private final DialectFactory dialects;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final Duration retryBackoff;
    private final Duration maxRetryBackoff;
    private final String nodeId;
    /** Runs on this instance by job id; a job has at most one. */
    private final Map<Long, RunContext> active = new ConcurrentHashMap<>();
    /** Threads for manual runs started through the API (scheduled runs use Quartz's threads). */
    private final ExecutorService background =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("migration-run-", 0).factory());

    public MigrationExecutor(MigrationJobRepository jobs, JobRunRepository runs, CheckpointRepository checkpoints,
                             DeadLetterRecordRepository deadLetters, ConnectionResolver resolver,
                             SourceConnectorFactory sources, DialectFactory dialects,
                             PlatformTransactionManager transactionManager, SchedularProperties properties) {
        this.jobs = jobs;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.deadLetters = deadLetters;
        this.resolver = resolver;
        this.sources = sources;
        this.dialects = dialects;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.retryBackoff = properties.engine().retryBackoff();
        this.maxRetryBackoff = properties.engine().maxRetryBackoff();
        this.nodeId = properties.nodeId();
    }

    /**
     * Runs the job on the calling thread (used by the scheduler) and returns the finished run record.
     *
     * @throws NotFoundException if the job does not exist
     * @throws ConflictException if the job is already running
     */
    public JobRun run(Long jobId, TriggerType trigger) {
        return execute(claim(jobId, trigger));
    }

    /**
     * Claims the job and runs it on a background thread (used for manual runs). Returns the new RUNNING run record
     * at once.
     *
     * @throws NotFoundException if the job does not exist
     * @throws ConflictException if the job is already running
     */
    public JobRun start(Long jobId, TriggerType trigger) {
        RunContext context = claim(jobId, trigger);
        try {
            background.execute(() -> execute(context));
        } catch (RejectedExecutionException e) {
            active.remove(jobId, context);
            finish(context.runId(), RunStatus.FAILED, "The application is shutting down");
            throw new ConflictException("The application is shutting down; try again later");
        }
        return runs.findById(context.runId()).orElseThrow();
    }

    /** Whether this instance is running the job right now. */
    public boolean isRunning(Long jobId) {
        return active.containsKey(jobId);
    }

    /** Asks a running run to stop after its current batch. Returns false if the run is not active here. */
    public boolean cancel(Long runId) {
        for (RunContext context : active.values()) {
            if (runId.equals(context.runId())) {
                context.cancel();
                return true;
            }
        }
        return false;
    }

    /** On shutdown, cancels runs in progress so they stop cleanly (status CANCELLED, checkpoint kept). */
    @PreDestroy
    void shutdown() throws InterruptedException {
        background.shutdown();
        if (active.isEmpty()) {
            return;
        }
        log.info("Shutting down: cancelling {} running migration(s)", active.size());
        active.values().forEach(RunContext::cancel);
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        if (!active.isEmpty()) {
            log.warn("{} run(s) did not stop within {}s; they will be marked FAILED at next startup",
                    active.size(), SHUTDOWN_WAIT.toSeconds());
        }
    }

    /** Reserves the job for one run on this instance and records the run as RUNNING. */
    private RunContext claim(Long jobId, TriggerType trigger) {
        if (!jobs.existsById(jobId)) {
            throw new NotFoundException("Job", jobId);
        }
        RunContext reservation = new RunContext(jobId, null);
        if (active.putIfAbsent(jobId, reservation) != null) {
            throw new ConflictException("Job " + jobId + " is already running");
        }
        try {
            if (runs.existsByJobIdAndStatus(jobId, RunStatus.RUNNING)) {
                throw new ConflictException("Job " + jobId + " is already running on another instance");
            }
            JobRun run = runs.save(JobRun.start(jobId, trigger, nodeId));
            RunContext context = new RunContext(jobId, run.getId());
            active.put(jobId, context);
            return context;
        } catch (RuntimeException e) {
            active.remove(jobId, reservation);
            throw e;
        }
    }

    private JobRun execute(RunContext context) {
        MDC.put("jobId", String.valueOf(context.jobId()));
        MDC.put("runId", String.valueOf(context.runId()));
        try {
            return executeClaimed(context);
        } finally {
            active.remove(context.jobId(), context);
            MDC.remove("jobId");
            MDC.remove("runId");
        }
    }

    private JobRun executeClaimed(RunContext context) {
        RunStatus status;
        String error = null;
        Counters totals = new Counters();
        try {
            JobSnapshot job = loadSnapshot(context.jobId());
            log.info("Starting run of job '{}' ({} mapping(s), {} / {})", job.name(), job.mappings().size(),
                    job.syncMode(), job.writeMode());
            SqlDialect dialect = dialects.forType(job.target().dbType());
            try (SourceConnector source = sources.open(job.source());
                 HikariDataSource target = openTarget(job, dialect)) {
                for (MappingSnapshot mapping : job.mappings()) {
                    migrateCollection(job, mapping, source, target, dialect, context, totals);
                }
            }
            status = totals.failed > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
        } catch (RunCancelledException e) {
            status = RunStatus.CANCELLED;
            error = "Cancelled";
        } catch (Exception e) {
            if (context.isCancelled()) {
                status = RunStatus.CANCELLED;
                error = "Cancelled";
            } else {
                status = RunStatus.FAILED;
                error = describe(e);
                if (isConfigurationProblem(e)) {
                    log.error("Run failed: {}", error);
                } else {
                    log.error("Run failed: {}", error, e);
                }
            }
        }
        JobRun finished = finish(context.runId(), status, error);
        log.info("Run finished {}: {} read, {} rows written, {} failed", status, totals.read, totals.written,
                totals.failed);
        return finished;
    }

    private JobRun finish(Long runId, RunStatus status, String error) {
        return tx.execute(s -> {
            JobRun run = runs.findById(runId).orElseThrow();
            run.finish(status, error);
            return runs.save(run);
        });
    }

    private void migrateCollection(JobSnapshot job, MappingSnapshot mapping, SourceConnector source,
                                   HikariDataSource target, SqlDialect dialect, RunContext context, Counters totals)
            throws SQLException {
        MappingPlan plan = MappingCompiler.compile(mapping.targetTable(), mapping.mappingJson());
        boolean incremental = job.syncMode() == SyncMode.INCREMENTAL;
        try (Connection connection = target.getConnection()) {
            SchemaManager.ensure(connection, dialect, plan, job.autoCreateSchema(), job.writeMode() != WriteMode.INSERT);
        }

        Checkpoint checkpoint = checkpoints.findById(mapping.id()).orElseGet(() -> new Checkpoint(mapping.id(), job.id()));
        ReadRequest request;
        if (incremental) {
            request = ReadRequest.incremental(mapping.sourceCollection(), mapping.filterJson(),
                    mapping.watermarkField(), BsonValues.decodeValue(checkpoint.getLastWatermark()), job.batchSize());
        } else {
            Object afterId = BsonValues.decodeValue(checkpoint.getLastId());
            if (job.writeMode() == WriteMode.TRUNCATE_AND_LOAD && afterId == null) {
                try (Connection connection = target.getConnection()) {
                    JdbcTargetWriter.truncate(connection, dialect, plan);
                }
                log.info("Truncated {} before full load", plan.table().name());
            }
            request = ReadRequest.full(mapping.sourceCollection(), mapping.filterJson(), afterId, job.batchSize());
        }
        log.info("Migrating {} -> {}{}", mapping.sourceCollection(), plan.table().name(),
                checkpoint.getLastId() != null ? " (resuming after a previous interrupted run)" : "");

        try (BatchCursor cursor = source.read(request)) {
            while (cursor.hasNext()) {
                context.checkCancelled();
                List<SourceRecord> batch = cursor.next();
                Counters counters = new Counters();
                List<DeadLetterRecord> rejected = new ArrayList<>();
                writeBatch(job, mapping, plan, target, dialect, context, batch, counters, rejected);

                SourceRecord last = batch.getLast();
                if (incremental) {
                    Object watermark = DocumentPaths.resolve(last.data(), mapping.watermarkField());
                    if (watermark != null) {
                        checkpoint.setLastWatermark(BsonValues.encodeValue(watermark));
                    }
                } else {
                    checkpoint.setLastId(BsonValues.encodeValue(last.id()));
                }
                saveProgress(context.runId(), checkpoint, counters, rejected);
                totals.add(counters);
            }
        }
        if (!incremental) {
            checkpoint.setLastId(null); // the full read completed; the next run starts from the beginning
            tx.executeWithoutResult(s -> checkpoints.save(checkpoint));
        }
    }

    /** Maps and writes one batch. Rejected documents are collected in {@code rejected}; fatal errors propagate. */
    private void writeBatch(JobSnapshot job, MappingSnapshot mapping, MappingPlan plan, HikariDataSource target,
                            SqlDialect dialect, RunContext context, List<SourceRecord> batch, Counters counters,
                            List<DeadLetterRecord> rejected) throws SQLException {
        counters.read = batch.size();
        List<MappedDocument> mapped = new ArrayList<>(batch.size());
        List<SourceRecord> mappedSources = new ArrayList<>(batch.size());
        for (SourceRecord record : batch) {
            try {
                mapped.add(MappingEngine.map(plan, record.data()));
                mappedSources.add(record);
            } catch (DocumentMappingException e) {
                rejected.add(deadLetter(context, mapping, record, e.getMessage()));
            }
        }

        try {
            counters.written = writeWithRetry(job, target, dialect, plan, mapped, context);
        } catch (SQLException e) {
            if (!dialect.isDataError(e)) {
                throw e;
            }
            // Some row was rejected by the database itself (value out of range, invalid JSON, ...):
            // write the documents one by one so only the offending ones are dead-lettered.
            log.warn("Batch rejected ({}); retrying {} document(s) one at a time", SqlDialect.describe(e),
                    mapped.size());
            for (int i = 0; i < mapped.size(); i++) {
                context.checkCancelled();
                try {
                    counters.written += writeWithRetry(job, target, dialect, plan, List.of(mapped.get(i)), context);
                } catch (SQLException single) {
                    if (!dialect.isDataError(single)) {
                        throw single;
                    }
                    rejected.add(deadLetter(context, mapping, mappedSources.get(i), SqlDialect.describe(single)));
                }
            }
        }
        counters.failed = rejected.size();
    }

    private long writeWithRetry(JobSnapshot job, HikariDataSource target, SqlDialect dialect, MappingPlan plan,
                                List<MappedDocument> documents, RunContext context) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try (Connection connection = target.getConnection()) {
                return JdbcTargetWriter.write(connection, dialect, plan, job.writeMode(), documents);
            } catch (SQLException e) {
                if (!dialect.isTransient(e) || attempt >= job.maxRetries()) {
                    throw e;
                }
                Duration wait = backoff(attempt);
                log.warn("Transient write error (attempt {} of {}), retrying in {} ms: {}", attempt + 1,
                        job.maxRetries() + 1, wait.toMillis(), SqlDialect.describe(e));
                sleep(wait, context);
            }
        }
    }

    private Duration backoff(int attempt) {
        Duration wait = retryBackoff.multipliedBy(1L << Math.min(attempt, 20));
        return wait.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : wait;
    }

    /** Waits before a retry, in short slices so that a cancel takes effect within ~100 ms. */
    private static void sleep(Duration wait, RunContext context) {
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            while (true) {
                context.checkCancelled();
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                Thread.sleep(Duration.ofNanos(Math.min(left, CANCEL_POLL.toNanos())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the thread is being shut down: stop like a cancel
            throw new RunCancelledException();
        }
    }

    private void saveProgress(Long runId, Checkpoint checkpoint, Counters counters, List<DeadLetterRecord> rejected) {
        tx.executeWithoutResult(s -> {
            checkpoints.save(checkpoint);
            deadLetters.saveAll(rejected);
            JobRun run = runs.findById(runId).orElseThrow();
            run.addCounts(counters.read, counters.written, counters.failed);
            runs.save(run);
        });
    }

    private static DeadLetterRecord deadLetter(RunContext context, MappingSnapshot mapping, SourceRecord record,
                                               String error) {
        return new DeadLetterRecord(context.runId(), mapping.sourceCollection(), BsonValues.idToString(record.id()),
                BsonValues.toDebugJson(record.data()), error);
    }

    private JobSnapshot loadSnapshot(Long jobId) {
        return readOnlyTx.execute(s -> {
            MigrationJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Job", jobId));
            if (job.getSourceConnection().getKind() != ConnectionKind.SOURCE) {
                throw new IllegalStateException("Connection '" + job.getSourceConnection().getName()
                        + "' cannot be used as a source");
            }
            if (job.getTargetConnection().getKind() != ConnectionKind.TARGET) {
                throw new IllegalStateException("Connection '" + job.getTargetConnection().getName()
                        + "' cannot be used as a target");
            }
            if (job.getMappings().isEmpty()) {
                throw new IllegalStateException("Job '" + job.getName() + "' has no collection mappings");
            }
            if (job.getSyncMode() == SyncMode.INCREMENTAL && job.getWriteMode() == WriteMode.TRUNCATE_AND_LOAD) {
                throw new IllegalStateException("TRUNCATE_AND_LOAD can only be used with FULL sync");
            }
            List<MappingSnapshot> mappings = new ArrayList<>();
            for (CollectionMapping m : job.getMappings()) {
                if (job.getSyncMode() == SyncMode.INCREMENTAL
                        && (m.getWatermarkField() == null || m.getWatermarkField().isBlank())) {
                    throw new IllegalStateException("INCREMENTAL sync needs a watermarkField on mapping '"
                            + m.getSourceCollection() + "'");
                }
                mappings.add(new MappingSnapshot(m.getId(), m.getSourceCollection(), m.getTargetTable(),
                        m.getMappingJson(), m.getWatermarkField(), m.getFilterJson()));
            }
            ResolvedConnection source = resolver.resolve(job.getSourceConnection());
            ResolvedConnection target = resolver.resolve(job.getTargetConnection());
            return new JobSnapshot(job.getId(), job.getName(), job.getSyncMode(), job.getWriteMode(),
                    job.getBatchSize(), job.isAutoCreateSchema(), job.getMaxRetries(), source, target,
                    List.copyOf(mappings));
        });
    }

    /** A small pool for this run only, so editing or deleting the connection never affects other runs. */
    private static HikariDataSource openTarget(JobSnapshot job, SqlDialect dialect) {
        ResolvedConnection target = job.target();
        HikariConfig config = new HikariConfig();
        config.setPoolName("target-job-" + job.id());
        config.setJdbcUrl(target.url());
        config.setUsername(target.username());
        config.setPassword(target.password());
        Properties properties = new Properties();
        properties.putAll(dialect.defaultConnectionProperties());
        properties.putAll(target.options());
        config.setDataSourceProperties(properties);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        // Don't fail while building the pool: connection errors surface on use, where they are retried.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    /** Errors whose message says everything; logged without a stack trace. */
    private static boolean isConfigurationProblem(Exception e) {
        return e instanceof InvalidMappingException || e instanceof TargetSchemaException
                || e instanceof IllegalStateException || e instanceof IllegalArgumentException
                || e instanceof UnsupportedOperationException;
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (e instanceof SQLException sql) {
            return SqlDialect.describe(sql);
        }
        if (root instanceof SQLException sql) {
            return SqlDialect.describe(sql);
        }
        if (e.getMessage() != null) {
            return e.getMessage();
        }
        return root.getMessage() != null ? root.getMessage() : e.getClass().getSimpleName();
    }

    private static final class Counters {
        long read;
        long written;
        long failed;

        void add(Counters other) {
            read += other.read;
            written += other.written;
            failed += other.failed;
        }
    }
}
