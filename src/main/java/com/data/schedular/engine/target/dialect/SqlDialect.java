package com.data.schedular.engine.target.dialect;

import com.data.schedular.domain.DbType;
import com.data.schedular.engine.mapping.ColumnDef;
import com.data.schedular.engine.mapping.TableDef;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything database-specific about writing to a SQL target: quoting, types, DDL, upsert syntax,
 * parameter binding and error classification. Statements use positional parameters in table column order.
 */
public interface SqlDialect {

    DbType dbType();

    /** Quotes an identifier; {@code schema.table} is quoted part by part. */
    String quote(String identifier);

    /** The SQL type for a column, e.g. {@code varchar(255)}. */
    String columnType(ColumnDef column);

    /** {@code INSERT ... ON CONFLICT/DUPLICATE KEY/MERGE} that inserts or updates by primary key. */
    String upsert(TableDef table);

    /** Binds one value (already converted by {@code TypeCoercer}) to a statement parameter. */
    void bind(PreparedStatement statement, int index, Object value, ColumnDef column) throws SQLException;

    /** Driver properties applied unless the connection's options override them. */
    default Map<String, String> defaultConnectionProperties() {
        return Map.of();
    }

    default String createTable(TableDef table) {
        String columns = table.columns().stream()
                .map(c -> quote(c.name()) + " " + columnType(c) + (c.nullable() ? "" : " NOT NULL"))
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + quote(table.name()) + " (" + columns + ", PRIMARY KEY ("
                + quoteAll(table.primaryKey()) + "))";
    }

    default String addColumn(TableDef table, ColumnDef column) {
        return "ALTER TABLE " + quote(table.name()) + " ADD COLUMN " + quote(column.name()) + " "
                + columnType(column);
    }

    default String insert(TableDef table) {
        return "INSERT INTO " + quote(table.name()) + " (" + quoteAll(table.columnNames()) + ") VALUES ("
                + String.join(", ", Collections.nCopies(table.columns().size(), "?")) + ")";
    }

    /** {@code DELETE FROM child WHERE parent_id IN (?, ...)} with {@code count} parameters. */
    default String deleteWhereIn(TableDef table, String column, int count) {
        return "DELETE FROM " + quote(table.name()) + " WHERE " + quote(column) + " IN ("
                + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    default String truncate(TableDef table) {
        return "TRUNCATE TABLE " + quote(table.name());
    }

    /** Errors worth retrying: lost connections, deadlocks, serialization failures, failover. */
    default boolean isTransient(SQLException e) {
        for (SQLException ex : chain(e)) {
            if (ex instanceof SQLTransientException || ex instanceof SQLRecoverableException) {
                return true;
            }
            String state = ex.getSQLState();
            if (state != null && (state.startsWith("08") || state.equals("40001"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Errors caused by the data of a particular row (SQLSTATE classes 22 data exception and 23 integrity
     * violation). The writer then retries row by row so that only the offending documents are dead-lettered.
     */
    default boolean isDataError(SQLException e) {
        for (SQLException ex : chain(e)) {
            String state = ex.getSQLState();
            if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
                return true;
            }
        }
        return false;
    }

    default String quoteAll(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    /** The exception plus its {@code getNextException()} and SQL causes; batch errors hide the real one there. */
    static List<SQLException> chain(SQLException e) {
        List<SQLException> all = new ArrayList<>();
        SQLException current = e;
        while (current != null && all.size() < 20 && !all.contains(current)) {
            all.add(current);
            current = current.getNextException() != null ? current.getNextException()
                    : current.getCause() instanceof SQLException cause ? cause : null;
        }
        return all;
    }

    /** The most specific message in a SQL exception chain, with its SQLSTATE. */
    static String describe(SQLException e) {
        List<SQLException> all = chain(e);
        SQLException last = all.getLast();
        String state = last.getSQLState() != null ? last.getSQLState() : e.getSQLState();
        return (state != null ? "[" + state + "] " : "") + last.getMessage();
    }
}
