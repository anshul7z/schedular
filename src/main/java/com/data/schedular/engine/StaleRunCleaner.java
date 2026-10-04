package com.data.schedular.engine;

import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import com.data.schedular.repository.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Closes runs left in RUNNING by a crash or hard stop, so they don't block new runs of the same job.
 * Their checkpoints are kept, so the next run resumes where they stopped.
 * <p>
 * Assumes a single instance: with several instances sharing the metadata DB (Quartz cluster, Phase 3)
 * this must only touch runs owned by the starting node.
 */
@Component
public class StaleRunCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleRunCleaner.class);

    private final JobRunRepository runs;

    public StaleRunCleaner(JobRunRepository runs) {
        this.runs = runs;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeStaleRuns() {
        List<JobRun> stale = runs.findByStatus(RunStatus.RUNNING);
        for (JobRun run : stale) {
            run.finish(RunStatus.FAILED, "Interrupted: the application stopped while this run was in progress");
        }
        if (!stale.isEmpty()) {
            log.warn("Marked {} interrupted run(s) as FAILED; their next runs resume from the last checkpoint",
                    stale.size());
        }
    }
}
