package com.data.schedular.engine.source;

/**
 * What to read from one collection. Results are ordered so that a checkpoint can resume them:
 * by id for full reads, by (watermark, id) for incremental reads.
 *
 * @param collection     source collection
 * @param filterJson     optional source-native filter (a MongoDB query document as JSON)
 * @param afterId        full read: resume strictly after this id; null starts at the beginning
 * @param watermarkField incremental read: the field to order and filter by; null for a full read
 * @param fromWatermark  incremental read: read documents whose watermark is greater than or equal to this value
 *                       (ties are re-read, which is harmless with upserts); null reads everything
 * @param batchSize      documents per batch
 */
public record ReadRequest(String collection, String filterJson, Object afterId, String watermarkField,
                          Object fromWatermark, int batchSize) {

    public static ReadRequest full(String collection, String filterJson, Object afterId, int batchSize) {
        return new ReadRequest(collection, filterJson, afterId, null, null, batchSize);
    }

    public static ReadRequest incremental(String collection, String filterJson, String watermarkField,
                                          Object fromWatermark, int batchSize) {
        return new ReadRequest(collection, filterJson, null, watermarkField, fromWatermark, batchSize);
    }

    public boolean isIncremental() {
        return watermarkField != null;
    }
}
