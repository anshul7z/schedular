package com.data.schedular.engine.mapping;

import com.data.schedular.engine.mapping.MappingSpec.FieldSpec;
import com.data.schedular.engine.mapping.MappingSpec.PrimaryKeySpec;
import com.data.schedular.engine.mapping.MappingSpec.UnmappedFields;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proposes a {@link MappingSpec} from sample documents.
 * <ul>
 *   <li>scalar fields become typed columns;</li>
 *   <li>small objects whose values are all scalars (at most {@value #MAX_FLATTENED_FIELDS} keys) are flattened,
 *       other objects are stored as JSON;</li>
 *   <li>arrays of objects or of scalars become child tables; empty or mixed arrays are stored as JSON;</li>
 *   <li>string lengths get headroom (twice the longest value seen), and fields missing from the sample are kept
 *       in the {@code _extra} JSON column.</li>
 * </ul>
 * The result is a starting point to review, not a guarantee: a sample can miss rare fields and longer values.
 */
public final class SchemaInferrer {

    static final int MAX_FLATTENED_FIELDS = 10;
    private static final Pattern NOT_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern WATERMARK_NAME =
            Pattern.compile("(?i).*(updated|modified|changed|lastmod|last_mod).*");

    private SchemaInferrer() {
    }

    /**
     * @param watermarkField a date field that looks like a last-modified time, usable for INCREMENTAL sync; or null
     */
    public record InferredMapping(String sourceCollection, String targetTable, int sampledDocuments,
                                  MappingSpec mapping, String watermarkField) {
    }

    private enum Kind { INT, LONG, DOUBLE, DECIMAL, BOOLEAN, DATE, STRING, BINARY, OBJECT, ARRAY, OTHER }

    /** Everything observed about the values at one path. */
    private static final class Stats {
        final EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        int maxLength;
        final Map<String, Stats> fields = new LinkedHashMap<>();
        Stats elements;

        void add(Object value) {
            Kind kind = kindOf(value);
            if (kind == null) {
                return;
            }
            kinds.add(kind);
            switch (kind) {
                case STRING -> maxLength = Math.max(maxLength, stringLength(value));
                case OBJECT -> ((Map<?, ?>) value).forEach((k, v) ->
                        fields.computeIfAbsent(String.valueOf(k), x -> new Stats()).add(v));
                case ARRAY -> {
                    if (elements == null) {
                        elements = new Stats();
                    }
                    ((List<?>) value).forEach(elements::add);
                }
                default -> {
                }
            }
        }

        boolean scalarOnly() {
            return !kinds.isEmpty() && !kinds.contains(Kind.OBJECT) && !kinds.contains(Kind.ARRAY);
        }
    }

    public static InferredMapping infer(String collection, List<Map<String, Object>> documents) {
        String table = identifier(collection);
        Map<String, Stats> topLevel = new LinkedHashMap<>();
        for (Map<String, Object> doc : documents) {
            doc.forEach((k, v) -> topLevel.computeIfAbsent(k, x -> new Stats()).add(v));
        }

        Stats idStats = topLevel.getOrDefault("_id", new Stats());
        LogicalType keyType = keyType(idStats);
        Integer keyLength = keyType == LogicalType.STRING ? lengthFor(idStats.maxLength) : null;
        if (keyLength != null && keyLength < 0) {
            keyLength = 4000; // a key column must be bounded
        }
        PrimaryKeySpec primaryKey = new PrimaryKeySpec(null, null, keyType == LogicalType.STRING ? null : keyType,
                keyLength);

        Set<String> columns = new HashSet<>(Set.of("id", MappingPlan.OVERFLOW_COLUMN));
        Set<String> tables = new HashSet<>(Set.of(table.toLowerCase(Locale.ROOT)));
        List<FieldSpec> fields = new ArrayList<>();
        String watermark = null;
        for (Map.Entry<String, Stats> entry : topLevel.entrySet()) {
            String key = entry.getKey();
            Stats stats = entry.getValue();
            if (key.equals("_id") || !usableKey(key) || stats.kinds.isEmpty()) {
                continue;
            }
            if (stats.scalarOnly()) {
                fields.add(column(key, stats, columns));
                if (watermark == null && stats.kinds.equals(EnumSet.of(Kind.DATE))
                        && WATERMARK_NAME.matcher(key).matches()) {
                    watermark = key;
                }
            } else if (stats.kinds.equals(EnumSet.of(Kind.OBJECT)) && flattenable(stats)) {
                for (Map.Entry<String, Stats> child : stats.fields.entrySet()) {
                    if (usableKey(child.getKey()) && !child.getValue().kinds.isEmpty()) {
                        fields.add(column(key + "." + child.getKey(), child.getValue(), columns));
                    }
                }
            } else if (stats.kinds.equals(EnumSet.of(Kind.ARRAY)) && childTableCandidate(stats.elements)) {
                fields.add(childTable(table, key, stats.elements, tables));
            } else {
                fields.add(new FieldSpec(key, columnName(key, columns), null, null, NestedStrategy.JSON, null, null));
            }
        }

        MappingSpec spec = new MappingSpec(isDefault(primaryKey) ? null : primaryKey, fields,
                UnmappedFields.JSON_OVERFLOW_COLUMN);
        MappingCompiler.compile(table, spec); // the proposal must always be a valid mapping
        return new InferredMapping(collection, table, documents.size(), spec, watermark);
    }

    private static FieldSpec column(String path, Stats stats, Set<String> columns) {
        LogicalType type = scalarType(stats);
        Integer length = null;
        if (type == LogicalType.STRING) {
            length = lengthFor(stats.maxLength);
            if (length != null && length < 0) {
                type = LogicalType.TEXT;
                length = null;
            }
        }
        return new FieldSpec(path, columnName(path, columns), type == LogicalType.STRING ? null : type, length,
                null, null, null);
    }

    private static FieldSpec childTable(String parentTable, String key, Stats elements, Set<String> tables) {
        String childTable = uniqueName(identifier(parentTable + "_" + key), tables, 63);
        if (elements.scalarOnly()) {
            FieldSpec value = column(MappingPlan.SCALAR_ELEMENT_FIELD, elements, new HashSet<>());
            return new FieldSpec(key, null, value.type(), value.length(), NestedStrategy.CHILD_TABLE, childTable,
                    null);
        }
        Set<String> columns = new HashSet<>(Set.of(MappingPlan.PARENT_ID_COLUMN, MappingPlan.ORDINAL_COLUMN));
        List<FieldSpec> fields = new ArrayList<>();
        for (Map.Entry<String, Stats> child : elements.fields.entrySet()) {
            Stats stats = child.getValue();
            if (!usableKey(child.getKey()) || stats.kinds.isEmpty()) {
                continue;
            }
            fields.add(stats.scalarOnly()
                    ? column(child.getKey(), stats, columns)
                    : new FieldSpec(child.getKey(), columnName(child.getKey(), columns), null, null,
                    NestedStrategy.JSON, null, null));
        }
        return new FieldSpec(key, null, null, null, NestedStrategy.CHILD_TABLE, childTable, fields);
    }

    private static boolean flattenable(Stats object) {
        return !object.fields.isEmpty() && object.fields.size() <= MAX_FLATTENED_FIELDS
                && object.fields.values().stream().allMatch(s -> s.kinds.isEmpty() || s.scalarOnly());
    }

    /** Arrays become child tables when all their elements are objects, or all are scalars. */
    private static boolean childTableCandidate(Stats elements) {
        if (elements == null || elements.kinds.isEmpty()) {
            return false; // only empty arrays seen: nothing to base columns on
        }
        return elements.scalarOnly() || (elements.kinds.equals(EnumSet.of(Kind.OBJECT))
                && !elements.fields.isEmpty());
    }

    private static LogicalType scalarType(Stats stats) {
        Set<Kind> kinds = stats.kinds;
        if (kinds.size() == 1) {
            switch (kinds.iterator().next()) {
                case INT: return LogicalType.INT;
                case LONG: return LogicalType.LONG;
                case DOUBLE: return LogicalType.DOUBLE;
                case DECIMAL: return LogicalType.DECIMAL;
                case BOOLEAN: return LogicalType.BOOLEAN;
                case DATE: return LogicalType.TIMESTAMP;
                case BINARY: return LogicalType.BINARY;
                default: return LogicalType.STRING;
            }
        }
        if (EnumSet.of(Kind.INT, Kind.LONG).containsAll(kinds)) {
            return LogicalType.LONG;
        }
        if (EnumSet.of(Kind.INT, Kind.LONG, Kind.DOUBLE).containsAll(kinds)) {
            return LogicalType.DOUBLE;
        }
        if (EnumSet.of(Kind.INT, Kind.LONG, Kind.DOUBLE, Kind.DECIMAL).containsAll(kinds)) {
            return LogicalType.DECIMAL;
        }
        return LogicalType.STRING; // mixed types: every scalar has a string form
    }

    private static LogicalType keyType(Stats id) {
        if (id.kinds.equals(EnumSet.of(Kind.INT))) {
            return LogicalType.INT;
        }
        if (!id.kinds.isEmpty() && EnumSet.of(Kind.INT, Kind.LONG).containsAll(id.kinds)) {
            return LogicalType.LONG;
        }
        return LogicalType.STRING;
    }

    /**
     * Column length with headroom: null for the default 255, 1000 or 4000 for longer values, or -1 when only an
     * unbounded TEXT column will do.
     */
    private static Integer lengthFor(int maxSeen) {
        int needed = maxSeen * 2;
        if (needed <= ColumnDef.DEFAULT_STRING_LENGTH) {
            return null;
        }
        if (needed <= 1000) {
            return 1000;
        }
        return needed <= 4000 ? 4000 : -1;
    }

    private static boolean isDefault(PrimaryKeySpec pk) {
        return pk.type() == null && pk.length() == null;
    }

    /** Keys that can be addressed by a dotted path. */
    private static boolean usableKey(String key) {
        return !key.isEmpty() && !key.contains(".") && !key.startsWith("$");
    }

    private static String columnName(String path, Set<String> taken) {
        return uniqueName(identifier(path.replace('.', '_')), taken, 63);
    }

    private static String uniqueName(String base, Set<String> taken, int maxLength) {
        String name = base;
        for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) {
            String suffix = "_" + n;
            name = (base.length() + suffix.length() > maxLength ? base.substring(0, maxLength - suffix.length())
                    : base) + suffix;
        }
        taken.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    /** A valid SQL identifier derived from any name: invalid characters become underscores, max 63 chars. */
    static String identifier(String name) {
        String id = NOT_IDENTIFIER.matcher(name).replaceAll("_");
        if (id.isEmpty() || Character.isDigit(id.charAt(0))) {
            id = "_" + id;
        }
        return id.length() > 63 ? id.substring(0, 63) : id;
    }

    private static Kind kindOf(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> Kind.INT;
            case Long l -> Kind.LONG;
            case Double d -> Kind.DOUBLE;
            case Decimal128 d -> Kind.DECIMAL;
            case Boolean b -> Kind.BOOLEAN;
            case Date d -> Kind.DATE;
            case String s -> Kind.STRING;
            case ObjectId id -> Kind.STRING;
            case UUID uuid -> Kind.STRING;
            case Binary b -> Kind.BINARY;
            case Map<?, ?> m -> Kind.OBJECT;
            case List<?> l -> Kind.ARRAY;
            default -> BsonValues.toPlain(value) == null ? null : Kind.STRING;
        };
    }

    private static int stringLength(Object value) {
        String s = value instanceof ObjectId id ? id.toHexString() : String.valueOf(value);
        return s.codePointCount(0, s.length());
    }
}
