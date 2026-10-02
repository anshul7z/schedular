package com.data.schedular.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Progress marker for one collection mapping, so an interrupted or incremental run can resume. */
@Entity
@Table(name = "checkpoint")
public class Checkpoint {

    @Id
    @Column(name = "collection_mapping_id")
    private Long collectionMappingId;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** Last source _id written in the current FULL run (string form). */
    @Column(name = "last_id")
    private String lastId;

    /** Highest watermark value written by the last successful INCREMENTAL run (string form). */
    @Column(name = "last_watermark")
    private String lastWatermark;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Checkpoint() {
    }

    public Checkpoint(Long collectionMappingId, Long jobId) {
        this.collectionMappingId = collectionMappingId;
        this.jobId = jobId;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getCollectionMappingId() {
        return collectionMappingId;
    }

    public Long getJobId() {
        return jobId;
    }

    public String getLastId() {
        return lastId;
    }

    public void setLastId(String lastId) {
        this.lastId = lastId;
    }

    public String getLastWatermark() {
        return lastWatermark;
    }

    public void setLastWatermark(String lastWatermark) {
        this.lastWatermark = lastWatermark;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
