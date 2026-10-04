package com.data.schedular.engine;

/**
 * State of one claimed or in-progress run, shared with whoever asks to cancel it.
 * Cancelling only sets a flag: the run checks it between batches and while waiting to retry. It never interrupts
 * the thread, because an interrupt during JDBC or MongoDB I/O closes the connection mid-statement.
 */
final class RunContext {

    private final Long jobId;
    private final Long runId;
    private volatile boolean cancelled;

    RunContext(Long jobId, Long runId) {
        this.jobId = jobId;
        this.runId = runId;
    }

    Long jobId() {
        return jobId;
    }

    Long runId() {
        return runId;
    }

    boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }

    void checkCancelled() {
        if (cancelled) {
            throw new RunCancelledException();
        }
    }

    static final class RunCancelledException extends RuntimeException {
        RunCancelledException() {
            super("Run was cancelled", null, false, false);
        }
    }
}
