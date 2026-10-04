package com.data.schedular.api.dto;

import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.domain.TriggerType;

import java.time.Duration;
import java.time.Instant;

/** One run of a job. {@code durationMs} counts up while the run is in progress. */
public record RunResponse(
        Long id,
        Long jobId,
        TriggerType trigger,
        RunStatus status,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        long docsRead,
        long rowsWritten,
        long docsFailed,
        String errorMessage,
        String nodeId) {

    public static RunResponse from(JobRun run) {
        Instant end = run.getEndedAt() != null ? run.getEndedAt() : Instant.now();
        return new RunResponse(run.getId(), run.getJobId(), run.getTriggerType(), run.getStatus(), run.getStartedAt(),
                run.getEndedAt(), Duration.between(run.getStartedAt(), end).toMillis(), run.getDocsRead(),
                run.getRowsWritten(), run.getDocsFailed(), run.getErrorMessage(), run.getNodeId());
    }
}
