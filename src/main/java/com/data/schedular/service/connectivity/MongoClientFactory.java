package com.data.schedular.service.connectivity;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates MongoDB clients from a {@link ResolvedConnection}. Callers own (and must close) the client. */
public final class MongoClientFactory {

    private static final Pattern AUTH_SOURCE = Pattern.compile("[?&]authSource=([^&]+)");

    private MongoClientFactory() {
    }

    public static MongoClient create(ResolvedConnection connection, long connectTimeoutMs) {
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connection.url()))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b.connectTimeout((int) connectTimeoutMs, TimeUnit.MILLISECONDS))
                .applicationName("schedular");
        if (connection.username() != null && !connection.username().isBlank()) {
            char[] password = connection.password() == null ? new char[0] : connection.password().toCharArray();
            settings.credential(MongoCredential.createCredential(
                    connection.username(), authSource(connection.url()), password));
        }
        return MongoClients.create(settings.build());
    }

    /** The database to work in: the explicit one, else the one named in the connection string. */
    public static String databaseName(ResolvedConnection connection) {
        if (connection.database() != null && !connection.database().isBlank()) {
            return connection.database();
        }
        return new ConnectionString(connection.url()).getDatabase();
    }

    private static String authSource(String url) {
        Matcher m = AUTH_SOURCE.matcher(url);
        return m.find() ? m.group(1) : "admin";
    }
}
