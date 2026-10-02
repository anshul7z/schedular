package com.data.schedular.service.connectivity;

import com.data.schedular.domain.DbType;

import java.util.Map;

/**
 * A connection ready to be opened: the effective URL plus the decrypted password.
 * {@link #toString()} never includes the password.
 *
 * @param url      a JDBC URL for SQL targets, a MongoDB connection string for MongoDB
 * @param database the database to work in; may be null when it is part of the URL
 * @param options  extra driver settings (JDBC properties, or MongoDB URI query parameters)
 */
public record ResolvedConnection(DbType dbType, String url, String username, String password,
                                 String database, Map<String, String> options) {

    public ResolvedConnection {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    @Override
    public String toString() {
        return "ResolvedConnection[dbType=" + dbType + ", url=" + url + ", username=" + username
                + ", database=" + database + "]";
    }
}
