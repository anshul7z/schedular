package com.data.schedular.scheduler;

import com.data.schedular.domain.MigrationJob;
import com.data.schedular.repository.MigrationJobRepository;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * Keeps Quartz in step with the jobs table, which is the source of truth: an enabled job with a cron expression
 * has one cron trigger; any other job has none. Quartz writes go through the application's DataSource, so they
 * commit or roll back together with the surrounding job change.
 */
@Service
public class SchedulerService {

    static final String GROUP = "migration";
    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final Scheduler scheduler;
    private final MigrationJobRepository jobs;

    public SchedulerService(Scheduler scheduler, MigrationJobRepository jobs) {
        this.scheduler = scheduler;
        this.jobs = jobs;
    }

    /** Creates, replaces or removes the job's trigger to match its cron, timezone and enabled flag. */
    public void sync(MigrationJob job) {
        try {
            JobKey jobKey = jobKey(job.getId());
            TriggerKey triggerKey = triggerKey(job.getId());
            if (!job.isEnabled() || job.getCron() == null) {
                if (scheduler.checkExists(jobKey)) {
                    scheduler.deleteJob(jobKey);
                }
                return;
            }
            Trigger existing = scheduler.getTrigger(triggerKey);
            if (existing instanceof CronTrigger cron
                    && cron.getCronExpression().equals(job.getCron())
                    && cron.getTimeZone().getID().equals(job.getTimezone())) {
                return; // unchanged: keep the trigger so its fire history and next fire time are preserved
            }
            JobDetail detail = JobBuilder.newJob(MigrationQuartzJob.class)
                    .withIdentity(jobKey)
                    .withDescription(job.getName())
                    .usingJobData(MigrationQuartzJob.JOB_ID, job.getId())
                    .storeDurably()
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(job.getCron())
                            .inTimeZone(TimeZone.getTimeZone(job.getTimezone()))
                            // After downtime, don't replay missed firings; just wait for the next one.
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
            scheduler.scheduleJob(detail, Set.of(trigger), true);
            log.info("Scheduled job {} '{}' with cron '{}' ({})", job.getId(), job.getName(), job.getCron(),
                    job.getTimezone());
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not update the schedule of job " + job.getId(), e);
        }
    }

    public void remove(Long jobId) {
        try {
            scheduler.deleteJob(jobKey(jobId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not remove the schedule of job " + jobId, e);
        }
    }

    /** When the job will next fire, or null if it has no active schedule. */
    public Instant nextFireTime(Long jobId) {
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(jobId));
            return trigger == null || trigger.getNextFireTime() == null ? null
                    : trigger.getNextFireTime().toInstant();
        } catch (SchedulerException e) {
            log.warn("Could not read the schedule of job {}", jobId, e);
            return null;
        }
    }

    /**
     * Brings Quartz in line with the jobs table at startup: schedules what is missing or changed and removes
     * schedules whose job no longer exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcile() {
        try {
            Set<JobKey> expected = new HashSet<>();
            for (MigrationJob job : jobs.findAll()) {
                sync(job);
                if (job.isEnabled() && job.getCron() != null) {
                    expected.add(jobKey(job.getId()));
                }
            }
            for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) {
                if (!expected.contains(key)) {
                    scheduler.deleteJob(key);
                    log.info("Removed schedule {} that has no matching job", key.getName());
                }
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not reconcile schedules", e);
        }
    }

    static JobKey jobKey(Long jobId) {
        return JobKey.jobKey("job-" + Objects.requireNonNull(jobId), GROUP);
    }

    static TriggerKey triggerKey(Long jobId) {
        return TriggerKey.triggerKey("cron-" + Objects.requireNonNull(jobId), GROUP);
    }
}
