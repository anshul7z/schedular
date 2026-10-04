package com.data.schedular.service;

import com.data.schedular.api.dto.JobRequest;
import com.data.schedular.api.dto.JobRequest.MappingRequest;
import com.data.schedular.api.dto.JobResponse;
import com.data.schedular.api.dto.JobResponse.MappingResponse;
import com.data.schedular.api.dto.RunResponse;
import com.data.schedular.domain.CollectionMapping;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.ConnectionKind;
import com.data.schedular.domain.MigrationJob;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.TriggerType;
import com.data.schedular.domain.WriteMode;
import com.data.schedular.engine.MigrationExecutor;
import com.data.schedular.engine.mapping.InvalidMappingException;
import com.data.schedular.engine.mapping.MappingCompiler;
import com.data.schedular.engine.mapping.MappingPlan;
import com.data.schedular.engine.mapping.TableDef;
import com.data.schedular.engine.target.dialect.DialectFactory;
import com.data.schedular.repository.CheckpointRepository;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.scheduler.SchedulerService;
import org.bson.Document;
import org.bson.json.JsonParseException;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.ParseException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Job configuration: validation, persistence, and keeping the schedule in step. */
@Service
public class JobService {

    private final MigrationJobRepository jobs;
    private final ConnectionDefRepository connections;
    private final CheckpointRepository checkpoints;
    private final JobRunRepository runs;
    private final SchedulerService scheduler;
    private final MigrationExecutor executor;
    private final DialectFactory dialects;
    private final JsonMapper json;

    public JobService(MigrationJobRepository jobs, ConnectionDefRepository connections,
                      CheckpointRepository checkpoints, JobRunRepository runs, SchedulerService scheduler,
                      MigrationExecutor executor, DialectFactory dialects, JsonMapper json) {
        this.jobs = jobs;
        this.connections = connections;
        this.checkpoints = checkpoints;
        this.runs = runs;
        this.scheduler = scheduler;
        this.executor = executor;
        this.dialects = dialects;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<JobResponse> list() {
        return jobs.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public JobResponse create(JobRequest request) {
        if (jobs.existsByName(request.name().trim())) {
            throw new ConflictException("A job named '" + request.name().trim() + "' already exists");
        }
        MigrationJob job = new MigrationJob();
        apply(job, request);
        List<CollectionMapping> mappings = new ArrayList<>();
        for (MappingRequest m : request.mappings()) {
            CollectionMapping mapping = new CollectionMapping();
            applyMapping(mapping, m);
            mappings.add(mapping);
        }
        job.setMappings(mappings);
        job = jobs.saveAndFlush(job);
        scheduler.sync(job);
        return toResponse(job);
    }

    @Transactional
    public JobResponse update(Long id, JobRequest request) {
        MigrationJob job = find(id);
        requireNotRunning(id, "changed");
        if (jobs.existsByNameAndIdNot(request.name().trim(), id)) {
            throw new ConflictException("A job named '" + request.name().trim() + "' already exists");
        }
        apply(job, request);
        // Keep existing mapping rows (and so their checkpoints) when collection and table are unchanged.
        Map<String, CollectionMapping> existing = new HashMap<>();
        job.getMappings().forEach(m -> existing.put(mappingKey(m.getSourceCollection(), m.getTargetTable()), m));
        List<CollectionMapping> mappings = new ArrayList<>();
        for (MappingRequest m : request.mappings()) {
            CollectionMapping mapping = existing.remove(mappingKey(m.sourceCollection(), m.targetTable()));
            if (mapping == null) {
                mapping = new CollectionMapping();
            }
            applyMapping(mapping, m);
            mappings.add(mapping);
        }
        job.setMappings(mappings);
        job = jobs.saveAndFlush(job);
        scheduler.sync(job);
        return toResponse(job);
    }

    @Transactional
    public void delete(Long id) {
        MigrationJob job = find(id);
        requireNotRunning(id, "deleted");
        scheduler.remove(id);
        jobs.delete(job);
    }

    /** Stops scheduled runs (a run in progress continues; manual runs stay possible). */
    @Transactional
    public JobResponse pause(Long id) {
        MigrationJob job = find(id);
        job.setEnabled(false);
        scheduler.sync(job);
        return toResponse(job);
    }

    @Transactional
    public JobResponse resume(Long id) {
        MigrationJob job = find(id);
        job.setEnabled(true);
        scheduler.sync(job);
        return toResponse(job);
    }

    /** Starts a run now, in the background. */
    public RunResponse runNow(Long id) {
        return RunResponse.from(executor.start(id, TriggerType.MANUAL));
    }

    /** Forgets all progress, so the next run reads every collection from the beginning. */
    @Transactional
    public void resetCheckpoints(Long id) {
        find(id);
        requireNotRunning(id, "reset");
        checkpoints.deleteByJobId(id);
    }

    private MigrationJob find(Long id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("Job", id));
    }

    private void requireNotRunning(Long id, String action) {
        if (executor.isRunning(id) || runs.existsByJobIdAndStatus(id, RunStatus.RUNNING)) {
            throw new ConflictException("Job " + id + " is running and cannot be " + action
                    + " now; cancel the run or wait for it to finish");
        }
    }

    private void apply(MigrationJob job, JobRequest request) {
        ConnectionDef source = connection(request.sourceConnectionId(), ConnectionKind.SOURCE, "sourceConnectionId");
        ConnectionDef target = connection(request.targetConnectionId(), ConnectionKind.TARGET, "targetConnectionId");
        try {
            dialects.forType(target.getDbType());
        } catch (UnsupportedOperationException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        String cron = request.cron() == null || request.cron().isBlank() ? null : request.cron().trim();
        if (cron != null) {
            try {
                new CronExpression(cron);
            } catch (ParseException e) {
                throw new InvalidRequestException("Invalid cron expression '" + cron + "': " + e.getMessage()
                        + " (Quartz format: seconds minutes hours day-of-month month day-of-week [year])");
            }
        }
        String timezone = request.timezone() == null || request.timezone().isBlank() ? "UTC" : request.timezone();
        try {
            timezone = ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw new InvalidRequestException("Unknown timezone '" + timezone + "'");
        }
        SyncMode syncMode = request.syncMode() != null ? request.syncMode() : SyncMode.FULL;
        WriteMode writeMode = request.writeMode() != null ? request.writeMode() : WriteMode.UPSERT;
        if (syncMode == SyncMode.INCREMENTAL && writeMode == WriteMode.TRUNCATE_AND_LOAD) {
            throw new InvalidRequestException("writeMode TRUNCATE_AND_LOAD can only be used with syncMode FULL");
        }
        validateMappings(request.mappings(), syncMode);

        job.setName(request.name().trim());
        job.setSourceConnection(source);
        job.setTargetConnection(target);
        job.setCron(cron);
        job.setTimezone(timezone);
        job.setEnabled(request.enabled() == null || request.enabled());
        job.setSyncMode(syncMode);
        job.setWriteMode(writeMode);
        job.setBatchSize(request.batchSize() != null ? request.batchSize() : 1000);
        job.setAutoCreateSchema(request.autoCreateSchema() == null || request.autoCreateSchema());
        job.setMaxRetries(request.maxRetries() != null ? request.maxRetries() : 3);
    }

    private ConnectionDef connection(Long id, ConnectionKind kind, String field) {
        ConnectionDef connection = connections.findById(id)
                .orElseThrow(() -> new InvalidRequestException(field + ": connection " + id + " does not exist"));
        if (connection.getKind() != kind) {
            throw new InvalidRequestException(field + ": connection '" + connection.getName() + "' is a "
                    + connection.getDbType() + " " + connection.getKind().name().toLowerCase(Locale.ROOT)
                    + " and cannot be used as a " + kind.name().toLowerCase(Locale.ROOT));
        }
        return connection;
    }

    private void validateMappings(List<MappingRequest> mappings, SyncMode syncMode) {
        Set<String> pairs = new HashSet<>();
        Map<String, String> tables = new HashMap<>();
        for (MappingRequest m : mappings) {
            String where = "mapping '" + m.sourceCollection() + "' -> '" + m.targetTable() + "'";
            if (!pairs.add(mappingKey(m.sourceCollection(), m.targetTable()))) {
                throw new InvalidRequestException(where + " is listed twice");
            }
            MappingPlan plan;
            try {
                plan = MappingCompiler.compile(m.targetTable().trim(), toJson(m.mapping()));
            } catch (InvalidMappingException e) {
                throw new InvalidRequestException(where + ": " + e.getMessage());
            }
            for (TableDef table : plan.tables()) {
                String previous = tables.put(table.name().toLowerCase(Locale.ROOT), m.sourceCollection());
                if (previous != null) {
                    throw new InvalidRequestException("Table '" + table.name()
                            + "' is written by more than one mapping");
                }
            }
            if (m.filter() != null && !m.filter().isNull()) {
                if (!m.filter().isObject()) {
                    throw new InvalidRequestException(where + ": filter must be a JSON object (a MongoDB query)");
                }
                try {
                    Document.parse(toJson(m.filter()));
                } catch (JsonParseException e) {
                    throw new InvalidRequestException(where + ": invalid filter: " + e.getMessage());
                }
            }
            if (syncMode == SyncMode.INCREMENTAL && (m.watermarkField() == null || m.watermarkField().isBlank())) {
                throw new InvalidRequestException(where + ": syncMode INCREMENTAL needs a watermarkField");
            }
        }
    }

    private void applyMapping(CollectionMapping mapping, MappingRequest request) {
        mapping.setSourceCollection(request.sourceCollection().trim());
        mapping.setTargetTable(request.targetTable().trim());
        String mappingJson = toJson(request.mapping());
        mapping.setMappingJson(mappingJson != null ? mappingJson : "{}");
        mapping.setWatermarkField(request.watermarkField() == null || request.watermarkField().isBlank()
                ? null : request.watermarkField().trim());
        mapping.setFilterJson(toJson(request.filter()));
    }

    private JobResponse toResponse(MigrationJob job) {
        List<MappingResponse> mappings = job.getMappings().stream()
                .map(m -> new MappingResponse(m.getId(), m.getSourceCollection(), m.getTargetTable(),
                        fromJson(m.getMappingJson()), m.getWatermarkField(), fromJson(m.getFilterJson())))
                .toList();
        RunResponse lastRun = runs.findFirstByJobIdOrderByStartedAtDescIdDesc(job.getId())
                .map(RunResponse::from).orElse(null);
        return new JobResponse(job.getId(), job.getName(), job.getSourceConnection().getId(),
                job.getTargetConnection().getId(), job.getCron(), job.getTimezone(), job.isEnabled(),
                job.getSyncMode(), job.getWriteMode(), job.getBatchSize(), job.isAutoCreateSchema(),
                job.getMaxRetries(), mappings, scheduler.nextFireTime(job.getId()), lastRun, job.getCreatedAt(),
                job.getUpdatedAt());
    }

    private String toJson(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : json.writeValueAsString(node);
    }

    private JsonNode fromJson(String text) {
        return text == null ? null : json.readTree(text);
    }

    private static String mappingKey(String collection, String table) {
        return collection.trim() + "\u0000" + table.trim().toLowerCase(Locale.ROOT);
    }
}
