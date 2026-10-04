package com.data.schedular.api.dto;

import com.data.schedular.domain.SyncMode;
import com.data.schedular.domain.WriteMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Create/update payload for a migration job. Optional fields fall back to the documented defaults.
 *
 * @param cron     Quartz cron expression (seconds first, e.g. {@code 0 0/15 * * * ?}); omit for manual-only jobs
 * @param timezone time zone the cron is evaluated in (default {@code UTC})
 */
public record JobRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Long sourceConnectionId,
        @NotNull Long targetConnectionId,
        @Size(max = 120) String cron,
        @Size(max = 64) String timezone,
        Boolean enabled,
        SyncMode syncMode,
        WriteMode writeMode,
        @Min(1) @Max(50_000) Integer batchSize,
        Boolean autoCreateSchema,
        @Min(0) @Max(10) Integer maxRetries,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull MappingRequest> mappings) {

    /**
     * One collection → table mapping.
     *
     * @param mapping        the mapping spec (fields, primary key, ...); omit to map only the primary key
     * @param watermarkField field compared between runs in INCREMENTAL mode, e.g. {@code updatedAt}
     * @param filter         optional MongoDB query restricting which documents are migrated
     */
    public record MappingRequest(
            @NotBlank @Size(max = 255) String sourceCollection,
            @NotBlank @Size(max = 255) String targetTable,
            JsonNode mapping,
            @Size(max = 255) String watermarkField,
            JsonNode filter) {
    }
}
