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

    /** FULL mode: last source id written by an unfinished run, as Extended JSON; null once a run completes. */
    @Column(name = "last_id", length = 2000)
    private String lastId;

    /** INCREMENTAL mode: highest watermark value written so far, as Extended JSON. */
    @Column(name = "last_watermark", length = 2000)
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
