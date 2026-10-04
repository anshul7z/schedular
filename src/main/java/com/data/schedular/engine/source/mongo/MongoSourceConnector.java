package com.data.schedular.engine.source.mongo;

import com.data.schedular.engine.source.ReadRequest;
import com.data.schedular.engine.source.SourceConnector;
import com.data.schedular.engine.source.SourceRecord;
import com.data.schedular.service.connectivity.MongoClientFactory;
import com.data.schedular.service.connectivity.ResolvedConnection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Reads a MongoDB collection with a single cursor per request, sorted by {@code _id} (full) or by
 * {@code (watermark, _id)} (incremental), so the last document of each batch is a valid resume point.
 */
public class MongoSourceConnector implements SourceConnector {

    private static final long CONNECT_TIMEOUT_MS = 30_000;

    private final MongoClient client;
    private final String database;

    public MongoSourceConnector(ResolvedConnection connection) {
        this.database = MongoClientFactory.databaseName(connection);
        if (database == null) {
            throw new IllegalArgumentException("The MongoDB connection has no database set");
        }
        this.client = MongoClientFactory.create(connection, CONNECT_TIMEOUT_MS);
    }

    @Override
    public BatchCursor read(ReadRequest request) {
        List<Bson> conditions = new ArrayList<>();
        if (request.filterJson() != null && !request.filterJson().isBlank()) {
            try {
                conditions.add(Document.parse(request.filterJson()));
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("Invalid filter JSON for collection '" + request.collection()
                        + "': " + e.getMessage(), e);
            }
        }
        Bson sort;
        if (request.isIncremental()) {
            if (request.fromWatermark() != null) {
                conditions.add(Filters.gte(request.watermarkField(), request.fromWatermark()));
            }
            sort = Sorts.ascending(request.watermarkField(), "_id");
        } else {
            if (request.afterId() != null) {
                conditions.add(Filters.gt("_id", request.afterId()));
            }
            sort = Sorts.ascending("_id");
        }
        Bson filter = conditions.isEmpty() ? new Document() : Filters.and(conditions);

        MongoCursor<Document> cursor = client.getDatabase(database)
                .getCollection(request.collection())
                .find(filter)
                .sort(sort)
                .batchSize(request.batchSize())
                .cursor();
        return new MongoBatchCursor(cursor, request.batchSize());
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class MongoBatchCursor implements BatchCursor {

        private final MongoCursor<Document> cursor;
        private final int batchSize;

        private MongoBatchCursor(MongoCursor<Document> cursor, int batchSize) {
            this.cursor = cursor;
            this.batchSize = batchSize;
        }

        @Override
        public boolean hasNext() {
            return cursor.hasNext();
        }

        @Override
        public List<SourceRecord> next() {
            if (!cursor.hasNext()) {
                throw new NoSuchElementException();
            }
            List<SourceRecord> batch = new ArrayList<>(batchSize);
            while (batch.size() < batchSize && cursor.hasNext()) {
                Document doc = cursor.next();
                batch.add(new SourceRecord(doc.get("_id"), doc));
            }
            return batch;
        }

        @Override
        public void close() {
            cursor.close();
        }
    }
}
