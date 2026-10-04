package com.data.schedular.engine.mapping;

import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.Binary;
import org.bson.types.Code;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.bson.types.Symbol;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts values decoded by the MongoDB driver into plain Java values and JSON suitable for SQL. */
public final class BsonValues {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonWriterSettings EXTENDED = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final int MAX_DEBUG_JSON = 100_000;

    private BsonValues() {
    }

    /**
     * Recursively converts BSON-specific values to JSON-friendly Java values:
     * ObjectId → hex string, Date → ISO-8601 string, Decimal128 → BigDecimal, Binary → base64, documents → maps.
     */
    public static Object toPlain(Object value) {
        return switch (value) {
            case null -> null;
            case BsonUndefined u -> null;
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), toPlain(v)));
                yield out;
            }
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                list.forEach(v -> out.add(toPlain(v)));
                yield out;
            }
            case ObjectId id -> id.toHexString();
            case Date date -> date.toInstant().toString();
            case Decimal128 d -> d.isNaN() || d.isInfinite() ? d.toString() : d.bigDecimalValue();
            case Binary b -> Base64.getEncoder().encodeToString(b.getData());
            case byte[] bytes -> Base64.getEncoder().encodeToString(bytes);
            case BsonTimestamp ts -> Instant.ofEpochSecond(ts.getTime()).toString();
            case Symbol s -> s.getSymbol();
            case Code c -> c.getCode();
            case BsonRegularExpression r -> "/" + r.getPattern() + "/" + r.getOptions();
            case MinKey k -> "MinKey";
            case MaxKey k -> "MaxKey";
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            default -> value.toString();
        };
    }

    /** JSON text of a value after {@link #toPlain} conversion. */
    public static String toJson(Object value) {
        return JSON.writeValueAsString(toPlain(value));
    }

    /** Type-preserving MongoDB Extended JSON of a single value, e.g. {@code {"v": {"$oid": "..."}}}. */
    public static String encodeValue(Object value) {
        return new Document("v", value).toJson(EXTENDED);
    }

    /** Inverse of {@link #encodeValue}. */
    public static Object decodeValue(String encoded) {
        return encoded == null ? null : Document.parse(encoded).get("v");
    }

    /** Readable JSON of a whole document for dead-letter storage, truncated if very large. */
    public static String toDebugJson(Map<String, Object> document) {
        String json = document instanceof Document doc ? doc.toJson(RELAXED) : toJson(document);
        return json.length() <= MAX_DEBUG_JSON ? json : json.substring(0, MAX_DEBUG_JSON) + "...(truncated)";
    }

    /** Short human-readable form of a document id, e.g. an ObjectId's hex string. */
    public static String idToString(Object id) {
        Object plain = toPlain(id);
        String s = plain instanceof String str ? str : String.valueOf(plain);
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
