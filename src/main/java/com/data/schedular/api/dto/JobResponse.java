package com.data.schedular.api.dto;

import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.WriteMode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * A job as returned by the API.
 *
 * @param nextFireTime when the schedule fires next; null for manual-only or paused jobs
 * @param lastRun      the most recent run, if any
 */
public record JobResponse(
        Long id,
        String name,
        Long sourceConnectionId,
        Long targetConnectionId,
        String cron,
        String timezone,
        boolean enabled,
        SyncMode syncMode,
        WriteMode writeMode,
        int batchSize,
        boolean autoCreateSchema,
        int maxRetries,
        List<MappingResponse> mappings,
        Instant nextFireTime,
        RunResponse lastRun,
        Instant createdAt,
        Instant updatedAt) {

    public record MappingResponse(Long id, String sourceCollection, String targetTable, JsonNode mapping,
                                  String watermarkField, JsonNode filter) {
    }
}
