package com.data.schedular.service.connectivity;

import com.data.schedular.domain.DbType;

import java.util.List;

/** Database-specific connectivity checks. Implementations may block; callers apply the timeout. */
public interface ConnectionProbe {

    boolean supports(DbType dbType);

    /** Connects, verifies the server responds, and reports product and version. Throws on failure. */
    ConnectionTestResult test(ResolvedConnection connection) throws Exception;

    /** Lists collections (MongoDB) or tables (SQL, as {@code schema.table} where a schema applies). */
    List<String> listObjects(ResolvedConnection connection) throws Exception;
}
