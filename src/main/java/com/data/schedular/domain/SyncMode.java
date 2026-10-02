package com.data.schedular.domain;

public enum SyncMode {
    /** Read the whole collection, resuming from the last checkpointed _id. */
    FULL,
    /** Read only documents whose watermark field is greater than the last saved watermark. */
    INCREMENTAL
}
