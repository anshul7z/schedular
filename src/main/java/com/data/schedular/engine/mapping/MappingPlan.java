package com.data.schedular.engine.mapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A validated, compiled {@link MappingSpec}: the target tables plus how each column is extracted from a document.
 *
 * @param table           the parent table; column order matches the row arrays produced by {@link MappingEngine}
 * @param primaryKey      binding of the primary key (always the first column of {@code table})
 * @param columns         bindings for the remaining parent columns, excluding the overflow column
 * @param children        child tables, one per CHILD_TABLE field
 * @param overflow        whether unmapped top-level fields go into the {@code _extra} JSON column (last column)
 * @param mappedTopLevel  top-level document keys consumed by the mapping (excluded from the overflow column)
 */
public record MappingPlan(TableDef table, ColumnBinding primaryKey, List<ColumnBinding> columns,
                          List<ChildPlan> children, boolean overflow, Set<String> mappedTopLevel) {

    public static final String OVERFLOW_COLUMN = "_extra";
    public static final String PARENT_ID_COLUMN = "parent_id";
    public static final String ORDINAL_COLUMN = "ordinal";

    /** How one column's value is taken from a document (or array element). */
    public record ColumnBinding(String[] path, ColumnDef column) {
    }

    /**
     * A child table fed by an array. Rows are {@code (parent_id, ordinal, element columns...)}.
     * Scalar elements are seen as {@code {"value": element}}, so they map through a {@code value} path.
     */
    public record ChildPlan(TableDef table, String[] arrayPath, List<ColumnBinding> columns) {
    }

    public static final String SCALAR_ELEMENT_FIELD = "value";

    /** All tables this plan writes to, parent first. */
    public List<TableDef> tables() {
        return Stream.concat(Stream.of(table), children.stream().map(ChildPlan::table)).toList();
    }
}
