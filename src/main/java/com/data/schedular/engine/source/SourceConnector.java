package com.data.schedular.engine.source;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads documents from a NoSQL source. One instance serves one run and holds its client;
 * closing it releases the client and any open cursors.
 */
public interface SourceConnector extends AutoCloseable {

    /** Streams the requested documents in batches, in the order described by {@link ReadRequest}. */
    BatchCursor read(ReadRequest request);

    /** Up to {@code size} documents picked at random, for proposing a mapping. */
    List<Map<String, Object>> sample(String collection, int size);

    @Override
    void close();

    /** A stream of document batches; never holds more than one batch in memory. */
    interface BatchCursor extends Iterator<List<SourceRecord>>, AutoCloseable {
        @Override
        void close();
    }
}
