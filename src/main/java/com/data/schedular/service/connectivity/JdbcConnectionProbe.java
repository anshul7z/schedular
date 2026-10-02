package com.data.schedular.service.connectivity;

import com.data.schedular.domain.DbType;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

@Component
public class JdbcConnectionProbe implements ConnectionProbe {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast", "sys", "mysql", "performance_schema",
            "guest", "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator",
            "db_datareader", "db_datawriter", "db_denydatareader", "db_denydatawriter");

    @Override
    public boolean supports(DbType dbType) {
        return dbType.isJdbc();
    }

    @Override
    public ConnectionTestResult test(ResolvedConnection connection) throws SQLException {
        long start = System.nanoTime();
        try (Connection conn = open(connection)) {
            if (!conn.isValid(5)) {
                return ConnectionTestResult.failed("Connection opened but did not validate", elapsedMs(start));
            }
            DatabaseMetaData meta = conn.getMetaData();
            return ConnectionTestResult.ok(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(),
                    elapsedMs(start));
        }
    }

    @Override
    public List<String> listObjects(ResolvedConnection connection) throws SQLException {
        try (Connection conn = open(connection)) {
            DatabaseMetaData meta = conn.getMetaData();
            // Oracle exposes every schema the user can see; restrict to the user's own.
            String schemaPattern = connection.dbType() == DbType.ORACLE && connection.username() != null
                    ? connection.username().toUpperCase(Locale.ROOT)
                    : null;
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), schemaPattern, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String table = rs.getString("TABLE_NAME");
                    tables.add(schema == null ? table : schema + "." + table);
                }
            }
            tables.sort(null);
            return tables;
        }
    }

    /** Opens a JDBC connection, applying a driver-specific connect timeout unless one was configured. */
    public static Connection open(ResolvedConnection connection) throws SQLException {
        Properties props = new Properties();
        props.putAll(connection.options());
        if (connection.username() != null) {
            props.setProperty("user", connection.username());
        }
        if (connection.password() != null) {
            props.setProperty("password", connection.password());
        }
        switch (connection.dbType()) {
            case POSTGRESQL -> props.putIfAbsent("connectTimeout", "10");
            case MYSQL, MARIADB -> props.putIfAbsent("connectTimeout", "10000");
            case SQLSERVER -> props.putIfAbsent("loginTimeout", "10");
            case ORACLE -> props.putIfAbsent("oracle.net.CONNECT_TIMEOUT", "10000");
            default -> {
            }
        }
        return DriverManager.getConnection(connection.url(), props);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
