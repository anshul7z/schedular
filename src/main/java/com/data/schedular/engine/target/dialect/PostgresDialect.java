package com.data.schedular.engine.target.dialect;

import com.data.schedular.domain.DbType;
import com.data.schedular.engine.mapping.ColumnDef;
import com.data.schedular.engine.mapping.LogicalType;
import com.data.schedular.engine.mapping.TableDef;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PostgresDialect implements SqlDialect {

    /** Deadlock, admin/crash shutdown, cannot connect now, too many connections. */
    private static final Set<String> TRANSIENT_STATES = Set.of("40P01", "57P01", "57P02", "57P03", "53300");

    @Override
    public DbType dbType() {
        return DbType.POSTGRESQL;
    }

    @Override
    public String quote(String identifier) {
        StringBuilder out = new StringBuilder();
        for (String part : identifier.split("\\.")) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append('"').append(part.replace("\"", "\"\"")).append('"');
        }
        return out.toString();
    }

    @Override
    public String columnType(ColumnDef column) {
        return switch (column.type()) {
            case STRING -> "varchar(" + column.effectiveLength() + ")";
            case TEXT -> "text";
            case INT -> "integer";
            case LONG -> "bigint";
            case DECIMAL -> "numeric";
            case DOUBLE -> "double precision";
            case BOOLEAN -> "boolean";
            case TIMESTAMP -> "timestamptz";
            case JSON -> "jsonb";
            case BINARY -> "bytea";
        };
    }

    @Override
    public String upsert(TableDef table) {
        Set<String> keys = Set.copyOf(table.primaryKey());
        String updates = table.columnNames().stream()
                .filter(c -> !keys.contains(c))
                .map(c -> quote(c) + " = EXCLUDED." + quote(c))
                .collect(Collectors.joining(", "));
        return insert(table) + " ON CONFLICT (" + quoteAll(table.primaryKey()) + ") "
                + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + updates);
    }

    @Override
    public void bind(PreparedStatement statement, int index, Object value, ColumnDef column) throws SQLException {
        if (value == null) {
            statement.setNull(index, sqlType(column));
        } else if (column.type() == LogicalType.JSON) {
            statement.setObject(index, value, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    @Override
    public Map<String, String> defaultConnectionProperties() {
        // Lets the driver send a JDBC batch as multi-row INSERTs instead of one round trip per row.
        return Map.of("reWriteBatchedInserts", "true");
    }

    @Override
    public boolean isTransient(SQLException e) {
        if (SqlDialect.super.isTransient(e)) {
            return true;
        }
        return SqlDialect.chain(e).stream().anyMatch(ex -> TRANSIENT_STATES.contains(ex.getSQLState()));
    }

    private static int sqlType(ColumnDef column) {
        return switch (column.type()) {
            case STRING, TEXT -> Types.VARCHAR;
            case INT -> Types.INTEGER;
            case LONG -> Types.BIGINT;
            case DECIMAL -> Types.NUMERIC;
            case DOUBLE -> Types.DOUBLE;
            case BOOLEAN -> Types.BOOLEAN;
            case TIMESTAMP -> Types.TIMESTAMP_WITH_TIMEZONE;
            case JSON -> Types.OTHER;
            case BINARY -> Types.BINARY;
        };
    }
}
