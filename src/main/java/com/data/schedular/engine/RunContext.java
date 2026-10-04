package com.data.schedular.engine;

/** State of one in-progress run, shared with whoever asks to cancel it. */
final class RunContext {

    private final Long jobId;
    private final Thread thread;
    private volatile Long runId;
    private volatile boolean cancelled;

    RunContext(Long jobId) {
        this.jobId = jobId;
        this.thread = Thread.currentThread();
    }

    Long jobId() {
        return jobId;
    }

    Long runId() {
        return runId;
    }

    void runId(Long runId) {
        this.runId = runId;
    }

    boolean isCancelled() {
        return cancelled;
    }

    /** Flags the run and interrupts its thread so a retry wait ends at once; checked between batches. */
    void cancel() {
        cancelled = true;
        thread.interrupt();
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
