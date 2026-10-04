package com.data.schedular.scheduler;

import com.data.schedular.domain.TriggerType;
import com.data.schedular.engine.MigrationExecutor;
import com.data.schedular.service.ConflictException;
import com.data.schedular.service.NotFoundException;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz entry point for a scheduled run. One Quartz job exists per migration job; Quartz never runs the same one
 * twice at once, anywhere in the cluster. A firing that finds the job already running (for example, started
 * manually) is skipped.
 */
@DisallowConcurrentExecution
public class MigrationQuartzJob implements Job {

    static final String JOB_ID = "jobId";
    private static final Logger log = LoggerFactory.getLogger(MigrationQuartzJob.class);

    private final MigrationExecutor executor;

    public MigrationQuartzJob(MigrationExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void execute(JobExecutionContext context) {
        long jobId = context.getMergedJobDataMap().getLong(JOB_ID);
        try {
            executor.run(jobId, TriggerType.CRON);
        } catch (ConflictException e) {
            log.info("Skipping scheduled run of job {}: {}", jobId, e.getMessage());
        } catch (NotFoundException e) {
            log.warn("Job {} no longer exists; removing its schedule", jobId);
            try {
                context.getScheduler().deleteJob(context.getJobDetail().getKey());
            } catch (SchedulerException ex) {
                log.warn("Could not remove the schedule of deleted job {}", jobId, ex);
            }
        }
    }
}
