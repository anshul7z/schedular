package com.data.schedular.engine;

import com.data.schedular.config.SchedularProperties;
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
 * Closes runs that this instance left in RUNNING through a crash or hard stop, so they don't block new runs of the
 * same job. Their checkpoints are kept, so the next run resumes where they stopped. Runs started by other instances
 * of a cluster are left alone (see {@code schedular.node-id}).
 */
@Component
public class StaleRunCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleRunCleaner.class);

    private final JobRunRepository runs;
    private final String nodeId;

    public StaleRunCleaner(JobRunRepository runs, SchedularProperties properties) {
        this.runs = runs;
        this.nodeId = properties.nodeId();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeStaleRuns() {
        List<JobRun> stale = runs.findRunningOnNode(nodeId);
        for (JobRun run : stale) {
            run.finish(RunStatus.FAILED, "Interrupted: the application stopped while this run was in progress");
        }
        if (!stale.isEmpty()) {
            log.warn("Marked {} interrupted run(s) as FAILED; their next runs resume from the last checkpoint",
                    stale.size());
        }
    }
}
