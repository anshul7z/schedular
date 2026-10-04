package com.data.schedular.service;

import com.data.schedular.api.dto.DeadLetterResponse;
import com.data.schedular.api.dto.PageResponse;
import com.data.schedular.api.dto.RunResponse;
import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.engine.MigrationExecutor;
import com.data.schedular.repository.DeadLetterRecordRepository;
import com.data.schedular.repository.JobRunRepository;
import com.data.schedular.repository.MigrationJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Run history, dead letters and cancellation. */
@Service
public class RunService {

    private static final int MAX_PAGE_SIZE = 200;

    private final JobRunRepository runs;
    private final MigrationJobRepository jobs;
    private final DeadLetterRecordRepository deadLetters;
    private final MigrationExecutor executor;

    public RunService(JobRunRepository runs, MigrationJobRepository jobs, DeadLetterRecordRepository deadLetters,
                      MigrationExecutor executor) {
        this.runs = runs;
        this.jobs = jobs;
        this.deadLetters = deadLetters;
        this.executor = executor;
    }

    @Transactional(readOnly = true)
    public RunResponse get(Long runId) {
        return RunResponse.from(find(runId));
    }

    @Transactional(readOnly = true)
    public PageResponse<RunResponse> forJob(Long jobId, int page, int size) {
        if (!jobs.existsById(jobId)) {
            throw new NotFoundException("Job", jobId);
        }
        return PageResponse.from(runs.findByJobIdOrderByStartedAtDescIdDesc(jobId, pageRequest(page, size)),
                RunResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterResponse> deadLetters(Long runId, int page, int size) {
        find(runId);
        return PageResponse.from(deadLetters.findByRunIdOrderById(runId, pageRequest(page, size)),
                DeadLetterResponse::from);
    }

    /** Asks a running run to stop after its current batch; it ends CANCELLED and keeps its checkpoint. */
    public RunResponse cancel(Long runId) {
        JobRun run = find(runId);
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new ConflictException("Run " + runId + " is not running (status " + run.getStatus() + ")");
        }
        if (!executor.cancel(runId)) {
            throw new ConflictException("Run " + runId + " is running on instance '" + run.getNodeId()
                    + "'; send the cancel request to that instance");
        }
        return RunResponse.from(run);
    }

    private JobRun find(Long runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("Run", runId));
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size);
    }
}
