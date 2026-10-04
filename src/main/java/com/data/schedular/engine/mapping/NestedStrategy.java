package com.data.schedular.engine.mapping;

/** How one mapped field is stored in SQL. */
public enum NestedStrategy {
    /** The value at a (possibly dotted) path becomes one column; {@code a.b.c} defaults to column {@code a_b_c}. */
    FLATTEN,
    /** The value (object, array or scalar) is stored as JSON in one column. */
    JSON,
    /** Each element of the array at the path becomes a row in a child table. */
    CHILD_TABLE
}
