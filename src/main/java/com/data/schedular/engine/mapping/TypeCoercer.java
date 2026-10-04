package com.data.schedular.engine.mapping;

import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a source value to the Java value bound for a column of a given {@link LogicalType}.
 * Values that cannot be converted raise {@link DocumentMappingException}, which sends the document to the dead-letter
 * table instead of failing the batch.
 */
public final class TypeCoercer {

    private TypeCoercer() {
    }

    public static Object coerce(Object value, ColumnDef column) {
        if (value == null || value instanceof BsonUndefined) {
            return null;
        }
        try {
            return switch (column.type()) {
                case STRING -> checkLength(asString(value), column);
                case TEXT -> asString(value);
                case INT -> number(value).intValueExact();
                case LONG -> number(value).longValueExact();
                case DECIMAL -> number(value);
                case DOUBLE -> value instanceof Double || value instanceof Float
                        ? ((Number) value).doubleValue()
                        : number(value).doubleValue();
                case BOOLEAN -> bool(value);
                case TIMESTAMP -> timestamp(value);
                case JSON -> BsonValues.toJson(value);
                case BINARY -> binary(value);
            };
        } catch (Unconvertible | ArithmeticException | NumberFormatException | DateTimeParseException e) {
            throw fail(value, column);
        }
    }

    private static String asString(Object value) {
        return switch (value) {
            case String s -> s;
            case ObjectId id -> id.toHexString();
            case Date d -> d.toInstant().toString();
            case Decimal128 d -> d.isNaN() || d.isInfinite() ? d.toString() : d.bigDecimalValue().toPlainString();
            case BigDecimal d -> d.toPlainString();
            case Double d when !d.isNaN() && !d.isInfinite() -> BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            case Map<?, ?> m -> BsonValues.toJson(m);
            case List<?> l -> BsonValues.toJson(l);
            default -> String.valueOf(BsonValues.toPlain(value));
        };
    }

    private static String checkLength(String s, ColumnDef column) {
        int max = column.effectiveLength();
        int length = s.codePointCount(0, s.length());
        if (length > max) {
            throw new DocumentMappingException("column '" + column.name() + "': value has " + length
                    + " characters, the column allows " + max);
        }
        return s;
    }

    private static BigDecimal number(Object value) {
        return switch (value) {
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f.doubleValue());
            case Decimal128 d -> d.bigDecimalValue();
            case BigDecimal d -> d;
            case String s -> new BigDecimal(s.trim());
            default -> throw Unconvertible.INSTANCE;
        };
    }

    private static Boolean bool(Object value) {
        return switch (value) {
            case Boolean b -> b;
            case String s -> switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes", "y" -> true;
                case "false", "0", "no", "n" -> false;
                default -> throw Unconvertible.INSTANCE;
            };
            case Number n when n.doubleValue() == 0 -> false;
            case Number n when n.doubleValue() == 1 -> true;
            default -> throw Unconvertible.INSTANCE;
        };
    }

    private static OffsetDateTime timestamp(Object value) {
        return switch (value) {
            case Date d -> d.toInstant().atOffset(ZoneOffset.UTC);
            case Instant i -> i.atOffset(ZoneOffset.UTC);
            case OffsetDateTime o -> o;
            case BsonTimestamp ts -> Instant.ofEpochSecond(ts.getTime()).atOffset(ZoneOffset.UTC);
            case Long millis -> Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);
            case Integer millis -> Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);
            case String s -> parseTimestamp(s.trim());
            default -> throw Unconvertible.INSTANCE;
        };
    }

    private static OffsetDateTime parseTimestamp(String s) {
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        return LocalDate.parse(s).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static byte[] binary(Object value) {
        return switch (value) {
            case Binary b -> b.getData();
            case byte[] bytes -> bytes;
            case UUID uuid -> ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
            default -> throw Unconvertible.INSTANCE;
        };
    }

    private static DocumentMappingException fail(Object value, ColumnDef column) {
        String shown = String.valueOf(BsonValues.toPlain(value));
        if (shown.length() > 60) {
            shown = shown.substring(0, 60) + "...";
        }
        return new DocumentMappingException("column '" + column.name() + "': cannot convert "
                + value.getClass().getSimpleName() + " '" + shown + "' to " + column.type());
    }

    /** Internal signal that a value has no conversion to the requested type; turned into a readable message. */
    private static final class Unconvertible extends RuntimeException {
        private static final Unconvertible INSTANCE = new Unconvertible();

        private Unconvertible() {
            super(null, null, false, false);
        }
    }
}
