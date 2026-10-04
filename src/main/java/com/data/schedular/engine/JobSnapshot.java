package com.data.schedular.engine;

import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.WriteMode;
import com.data.schedular.service.connectivity.ResolvedConnection;

import java.util.List;

/**
 * Everything a run needs from the metadata DB, loaded once at the start so the run holds no JPA state
 * (and sees a consistent configuration even if the job is edited meanwhile).
 */
record JobSnapshot(Long id, String name, SyncMode syncMode, WriteMode writeMode, int batchSize,
                   boolean autoCreateSchema, int maxRetries, ResolvedConnection source,
                   ResolvedConnection target, List<MappingSnapshot> mappings) {

    record MappingSnapshot(Long id, String sourceCollection, String targetTable, String mappingJson,
                           String watermarkField, String filterJson) {
    }
}
