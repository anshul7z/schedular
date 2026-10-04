package com.data.schedular.engine.source;

import java.util.Map;

/**
 * One document read from a source.
 *
 * @param id   the source's native document id (e.g. a MongoDB ObjectId), used for checkpoints
 * @param data the document; nested values are maps and lists
 */
public record SourceRecord(Object id, Map<String, Object> data) {
}
