package com.data.schedular.engine.mapping;

/** A target column. {@code length} applies to STRING only. */
public record ColumnDef(String name, LogicalType type, Integer length, boolean nullable) {

    public static final int DEFAULT_STRING_LENGTH = 255;

    public int effectiveLength() {
        return length != null ? length : DEFAULT_STRING_LENGTH;
    }
}
