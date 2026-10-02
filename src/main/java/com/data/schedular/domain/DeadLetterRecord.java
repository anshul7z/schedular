package com.data.schedular.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A source document that could not be migrated, kept for inspection. */
@Entity
@Table(name = "dead_letter_record")
public class DeadLetterRecord {

    private static final int MAX_ERROR_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "collection_name", nullable = false)
    private String collectionName;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeadLetterRecord() {
    }

    public DeadLetterRecord(Long runId, String collectionName, String sourceId, String payloadJson, String error) {
        this.runId = runId;
        this.collectionName = collectionName;
        this.sourceId = sourceId;
        this.payloadJson = payloadJson;
        this.error = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
