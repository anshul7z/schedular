package com.data.schedular.engine.target.dialect;

import com.data.schedular.domain.DbType;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the {@link SqlDialect} for a target database. */
@Component
public class DialectFactory {

    private final List<SqlDialect> dialects;

    public DialectFactory(List<SqlDialect> dialects) {
        this.dialects = dialects;
    }

    public SqlDialect forType(DbType dbType) {
        return dialects.stream()
                .filter(d -> d.dbType() == dbType)
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "Writing to " + dbType + " is not supported yet"));
    }
}
