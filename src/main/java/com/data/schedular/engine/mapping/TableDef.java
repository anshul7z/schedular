package com.data.schedular.engine.mapping;

import java.util.List;

/**
 * A target table. {@code name} is either {@code table} (resolved in the connection's default schema)
 * or {@code schema.table}.
 */
public record TableDef(String name, List<ColumnDef> columns, List<String> primaryKey) {

    public TableDef {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
    }

    public String schema() {
        int dot = name.indexOf('.');
        return dot < 0 ? null : name.substring(0, dot);
    }

    public String simpleName() {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    public List<String> columnNames() {
        return columns.stream().map(ColumnDef::name).toList();
    }
}
