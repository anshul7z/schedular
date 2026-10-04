package com.data.schedular.engine.target;

import com.data.schedular.engine.mapping.ColumnDef;
import com.data.schedular.engine.mapping.MappingPlan;
import com.data.schedular.engine.mapping.TableDef;
import com.data.schedular.engine.target.dialect.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes the target tables match a {@link MappingPlan}: creates missing tables and adds missing columns.
 * It never drops or alters existing columns.
 */
public final class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    private SchemaManager() {
    }

    /**
     * @param autoCreate   create missing tables/columns; when false, missing ones fail with {@link TargetSchemaException}
     * @param requireKey   the write mode upserts, so existing tables need a primary key or unique index on the key
     */
    public static void ensure(Connection connection, SqlDialect dialect, MappingPlan plan, boolean autoCreate,
                              boolean requireKey) throws SQLException {
        connection.setAutoCommit(true);
        for (TableDef table : plan.tables()) {
            Set<String> existing = existingColumns(connection, table);
            if (existing == null) {
                if (!autoCreate) {
                    throw new TargetSchemaException("Target table '" + table.name()
                            + "' does not exist and autoCreateSchema is off");
                }
                execute(connection, dialect.createTable(table));
                log.info("Created target table {}", table.name());
                continue;
            }
            List<ColumnDef> missing = table.columns().stream().filter(c -> !existing.contains(c.name())).toList();
            if (!missing.isEmpty()) {
                if (!autoCreate) {
                    throw new TargetSchemaException("Target table '" + table.name() + "' is missing columns "
                            + missing.stream().map(ColumnDef::name).toList() + " and autoCreateSchema is off");
                }
                for (ColumnDef column : missing) {
                    if (!column.nullable()) {
                        throw new TargetSchemaException("Target table '" + table.name() + "' has no key column '"
                                + column.name() + "'; it cannot be added to an existing table");
                    }
                    execute(connection, dialect.addColumn(table, column));
                    log.info("Added column {}.{}", table.name(), column.name());
                }
            }
            // Child tables are refilled with delete-then-insert, so only the parent needs a key to upsert on.
            if (requireKey && table == plan.table() && !hasUniqueKey(connection, table)) {
                throw new TargetSchemaException("Target table '" + table.name()
                        + "' has no primary key or unique index on " + table.primaryKey()
                        + "; upserts need one. Add it, or use writeMode INSERT.");
            }
        }
    }

    /** Column names of the table, or null if the table does not exist. */
    private static Set<String> existingColumns(Connection connection, TableDef table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String schema = schemaOf(connection, table);
        String escape = meta.getSearchStringEscape();
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet rs = meta.getColumns(connection.getCatalog(), pattern(schema, escape),
                pattern(table.simpleName(), escape), "%")) {
            while (rs.next()) {
                if (table.simpleName().equals(rs.getString("TABLE_NAME"))) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns.isEmpty() ? null : columns;
    }

    private static boolean hasUniqueKey(Connection connection, TableDef table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String schema = schemaOf(connection, table);
        Set<String> wanted = Set.copyOf(table.primaryKey());

        Set<String> primaryKey = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(connection.getCatalog(), schema, table.simpleName())) {
            while (rs.next()) {
                primaryKey.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (primaryKey.equals(wanted)) {
            return true;
        }
        Map<String, Set<String>> uniqueIndexes = new HashMap<>();
        try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), schema, table.simpleName(), true, true)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index != null && column != null && !rs.getBoolean("NON_UNIQUE")) {
                    uniqueIndexes.computeIfAbsent(index, k -> new HashSet<>()).add(column);
                }
            }
        }
        return uniqueIndexes.values().stream().anyMatch(wanted::equals);
    }

    private static String schemaOf(Connection connection, TableDef table) throws SQLException {
        return table.schema() != null ? table.schema() : connection.getSchema();
    }

    /** Escapes LIKE wildcards ({@code _} is common in table names) for metadata pattern arguments. */
    private static String pattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                out.append(escape);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
