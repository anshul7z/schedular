package com.data.schedular.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Maps one source collection to one target table. The field-level mapping
 * (flatten / JSON column / child table) is kept as JSON in {@link #mappingJson}.
 */
@Entity
@Table(name = "collection_mapping")
public class CollectionMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private MigrationJob job;

    @Column(name = "source_collection", nullable = false)
    private String sourceCollection;

    @Column(name = "target_table", nullable = false)
    private String targetTable;

    @Column(name = "mapping_json", nullable = false)
    private String mappingJson;

    @Column(name = "watermark_field")
    private String watermarkField;

    /** Optional Mongo query (JSON) restricting which documents are migrated. */
    @Column(name = "filter_json")
    private String filterJson;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public Long getId() {
        return id;
    }

    public MigrationJob getJob() {
        return job;
    }

    void setJob(MigrationJob job) {
        this.job = job;
    }

    public String getSourceCollection() {
        return sourceCollection;
    }

    public void setSourceCollection(String sourceCollection) {
        this.sourceCollection = sourceCollection;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(String mappingJson) {
        this.mappingJson = mappingJson;
    }

    public String getWatermarkField() {
        return watermarkField;
    }

    public void setWatermarkField(String watermarkField) {
        this.watermarkField = watermarkField;
    }

    public String getFilterJson() {
        return filterJson;
    }

    public void setFilterJson(String filterJson) {
        this.filterJson = filterJson;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}
