package com.data.schedular.service.connectivity;

import com.data.schedular.domain.DbType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MongoConnectionProbe implements ConnectionProbe {

    private static final long CONNECT_TIMEOUT_MS = 10_000;

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.MONGODB;
    }

    @Override
    public ConnectionTestResult test(ResolvedConnection connection) {
        long start = System.nanoTime();
        try (MongoClient client = MongoClientFactory.create(connection, CONNECT_TIMEOUT_MS)) {
            String dbName = MongoClientFactory.databaseName(connection);
            MongoDatabase db = client.getDatabase(dbName == null ? "admin" : dbName);
            db.runCommand(new Document("ping", 1));
            Document buildInfo = db.runCommand(new Document("buildInfo", 1));
            return ConnectionTestResult.ok("MongoDB", buildInfo.getString("version"), elapsedMs(start));
        }
    }

    @Override
    public List<String> listObjects(ResolvedConnection connection) {
        String dbName = MongoClientFactory.databaseName(connection);
        if (dbName == null) {
            throw new IllegalArgumentException("No database set on this connection");
        }
        try (MongoClient client = MongoClientFactory.create(connection, CONNECT_TIMEOUT_MS)) {
            List<String> names = client.getDatabase(dbName).listCollectionNames().into(new ArrayList<>());
            names.removeIf(n -> n.startsWith("system."));
            names.sort(null);
            return names;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
