package com.data.schedular.api.dto;

import com.data.schedular.domain.DeadLetterRecord;

import java.time.Instant;

/** A document that could not be migrated, with the reason and the document itself (as JSON). */
public record DeadLetterResponse(Long id, String collection, String sourceId, String error, String document,
                                 Instant createdAt) {

    public static DeadLetterResponse from(DeadLetterRecord record) {
        return new DeadLetterResponse(record.getId(), record.getCollectionName(), record.getSourceId(),
                record.getError(), record.getPayloadJson(), record.getCreatedAt());
    }
}
