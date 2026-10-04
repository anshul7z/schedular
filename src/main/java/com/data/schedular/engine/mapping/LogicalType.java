package com.data.schedular.engine.mapping;

/** Database-neutral column types. Each {@code SqlDialect} maps them to concrete SQL types. */
public enum LogicalType {
    /** Bounded string, {@code varchar(length)}; length defaults to 255. */
    STRING,
    /** Unbounded string. */
    TEXT,
    INT,
    LONG,
    DECIMAL,
    DOUBLE,
    BOOLEAN,
    /** Instant in time, stored with time zone (UTC). */
    TIMESTAMP,
    JSON,
    BINARY
}
