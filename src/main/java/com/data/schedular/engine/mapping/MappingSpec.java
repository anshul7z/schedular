package com.data.schedular.engine.mapping;

import java.util.List;

/**
 * The user-facing mapping of one collection to one table, stored as JSON in {@code collection_mapping.mapping_json}.
 * Every property is optional except the field list's entries' {@code path}. Example:
 * <pre>{@code
 * {
 *   "primaryKey": {"source": "_id", "column": "id", "type": "STRING"},
 *   "fields": [
 *     {"path": "customer.name", "type": "STRING"},
 *     {"path": "meta", "strategy": "JSON"},
 *     {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
 *      "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}]}
 *   ],
 *   "unmappedFields": "IGNORE"
 * }
 * }</pre>
 */
public record MappingSpec(PrimaryKeySpec primaryKey, List<FieldSpec> fields, UnmappedFields unmappedFields) {

    /** Source path and target column of the primary key; defaults to {@code _id → id STRING}. */
    public record PrimaryKeySpec(String source, String column, LogicalType type, Integer length) {
    }

    /**
     * One mapped field.
     *
     * @param path       dotted path in the document (numeric segments index into arrays, e.g. {@code items.0.sku});
     *                   inside a child table, relative to the array element ({@code value} for scalar elements)
     * @param column     target column; defaults to the path with dots replaced by underscores
     * @param type       target type; defaults to STRING (ignored for JSON and CHILD_TABLE)
     * @param length     maximum length for STRING columns; defaults to 255
     * @param strategy   defaults to FLATTEN
     * @param childTable target table for CHILD_TABLE
     * @param fields     element fields for CHILD_TABLE; empty means a single {@code value} column
     */
    public record FieldSpec(String path, String column, LogicalType type, Integer length, NestedStrategy strategy,
                            String childTable, List<FieldSpec> fields) {
    }

    public enum UnmappedFields {
        /** Top-level fields that are not mapped are dropped. */
        IGNORE,
        /** Top-level fields that are not mapped are kept as a JSON object in an {@code _extra} column. */
        JSON_OVERFLOW_COLUMN
    }
}
