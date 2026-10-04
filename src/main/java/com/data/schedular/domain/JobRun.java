package com.data.schedular.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One execution of a migration job, with its outcome and counters. */
@Entity
@Table(name = "job_run")
public class JobRun {

    private static final int MAX_ERROR_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "docs_read", nullable = false)
    private long docsRead;

    @Column(name = "rows_written", nullable = false)
    private long rowsWritten;

    @Column(name = "docs_failed", nullable = false)
    private long docsFailed;

    @Column(name = "error_message", length = MAX_ERROR_LENGTH)
    private String errorMessage;

    /** The instance executing the run. */
    @Column(name = "node_id", length = 100)
    private String nodeId;

    protected JobRun() {
    }

    public static JobRun start(Long jobId, TriggerType triggerType, String nodeId) {
        JobRun run = new JobRun();
        run.jobId = jobId;
        run.triggerType = triggerType;
        run.status = RunStatus.RUNNING;
        run.startedAt = Instant.now();
        run.nodeId = nodeId;
        return run;
    }

    public void finish(RunStatus status, String errorMessage) {
        this.status = status;
        this.endedAt = Instant.now();
        this.errorMessage = errorMessage == null || errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    public void addCounts(long read, long written, long failed) {
        docsRead += read;
        rowsWritten += written;
        docsFailed += failed;
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public long getDocsRead() {
        return docsRead;
    }

    public long getRowsWritten() {
        return rowsWritten;
    }

    public long getDocsFailed() {
        return docsFailed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getNodeId() {
        return nodeId;
    }
}
