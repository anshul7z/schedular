package com.data.schedular.domain;

/** Supported databases. The kind decides which side of a migration job a connection may be used on. */
public enum DbType {
    MONGODB(ConnectionKind.SOURCE, 27017),
    POSTGRESQL(ConnectionKind.TARGET, 5432),
    MYSQL(ConnectionKind.TARGET, 3306),
    MARIADB(ConnectionKind.TARGET, 3306),
    SQLSERVER(ConnectionKind.TARGET, 1433),
    ORACLE(ConnectionKind.TARGET, 1521);

    private final ConnectionKind kind;
    private final int defaultPort;

    DbType(ConnectionKind kind, int defaultPort) {
        this.kind = kind;
        this.defaultPort = defaultPort;
    }

    public ConnectionKind kind() {
        return kind;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public boolean isJdbc() {
        return kind == ConnectionKind.TARGET;
    }
}
