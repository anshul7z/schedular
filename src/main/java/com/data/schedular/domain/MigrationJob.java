package com.data.schedular.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A scheduled migration from one source connection to one target connection. */
@Entity
@Table(name = "migration_job")
public class MigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_conn_id", nullable = false)
    private ConnectionDef sourceConnection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_conn_id", nullable = false)
    private ConnectionDef targetConnection;

    /** Quartz cron expression; null means the job only runs when triggered manually. */
    @Column(length = 120)
    private String cron;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_mode", nullable = false, length = 20)
    private SyncMode syncMode = SyncMode.FULL;

    @Column(name = "batch_size", nullable = false)
    private int batchSize = 1000;

    @Enumerated(EnumType.STRING)
    @Column(name = "write_mode", nullable = false, length = 30)
    private WriteMode writeMode = WriteMode.UPSERT;

    @Column(name = "auto_create_schema", nullable = false)
    private boolean autoCreateSchema = true;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<CollectionMapping> mappings = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addMapping(CollectionMapping mapping) {
        mapping.setJob(this);
        mapping.setOrderIndex(mappings.size());
        mappings.add(mapping);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ConnectionDef getSourceConnection() {
        return sourceConnection;
    }

    public void setSourceConnection(ConnectionDef sourceConnection) {
        this.sourceConnection = sourceConnection;
    }

    public ConnectionDef getTargetConnection() {
        return targetConnection;
    }

    public void setTargetConnection(ConnectionDef targetConnection) {
        this.targetConnection = targetConnection;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(SyncMode syncMode) {
        this.syncMode = syncMode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public WriteMode getWriteMode() {
        return writeMode;
    }

    public void setWriteMode(WriteMode writeMode) {
        this.writeMode = writeMode;
    }

    public boolean isAutoCreateSchema() {
        return autoCreateSchema;
    }

    public void setAutoCreateSchema(boolean autoCreateSchema) {
        this.autoCreateSchema = autoCreateSchema;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public List<CollectionMapping> getMappings() {
        return mappings;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
