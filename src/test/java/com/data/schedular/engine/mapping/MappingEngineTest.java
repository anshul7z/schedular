package com.data.schedular.engine.mapping;

import com.data.schedular.engine.mapping.MappingEngine.MappedDocument;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappingEngineTest {

    private static final ObjectId ID = new ObjectId("660000000000000000000001");

    private static final MappingPlan ORDERS = MappingCompiler.compile("orders", """
            {"fields": [
              {"path": "status"},
              {"path": "total", "type": "DECIMAL"},
              {"path": "customer.name", "length": 10},
              {"path": "paid", "type": "BOOLEAN"},
              {"path": "updatedAt", "column": "updated_at", "type": "TIMESTAMP"},
              {"path": "items.0.sku", "column": "first_sku"},
              {"path": "meta", "strategy": "JSON"},
              {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
               "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}]},
              {"path": "tags", "strategy": "CHILD_TABLE", "childTable": "order_tags"}
            ],
            "unmappedFields": "JSON_OVERFLOW_COLUMN"}
            """);

    @Test
    void mapsAllStrategies() {
        Document doc = new Document("_id", ID)
                .append("status", "SHIPPED")
                .append("total", new Decimal128(new BigDecimal("149.97")))
                .append("customer", new Document("name", "Asha Rao"))
                .append("paid", "yes")
                .append("updatedAt", Date.from(OffsetDateTime.parse("2026-03-02T09:15:00Z").toInstant()))
                .append("meta", new Document("source", "web").append("ref", new ObjectId("650000000000000000000001")))
                .append("items", List.of(new Document("sku", "BOOK-001").append("qty", 2),
                        new Document("sku", "LAMP-010").append("qty", 1L)))
                .append("tags", List.of("vip", 7))
                .append("note", "keep me");

        MappedDocument mapped = MappingEngine.map(ORDERS, doc);

        assertThat(mapped.key()).isEqualTo(ID.toHexString());
        assertThat(mapped.parentRow()).containsExactly(
                ID.toHexString(), "SHIPPED", new BigDecimal("149.97"), "Asha Rao", true,
                OffsetDateTime.of(2026, 3, 2, 9, 15, 0, 0, ZoneOffset.UTC), "BOOK-001",
                "{\"source\":\"web\",\"ref\":\"650000000000000000000001\"}",
                "{\"note\":\"keep me\"}");
        List<Object[]> items = mapped.childRows().get(0);
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsExactly(ID.toHexString(), 0, "BOOK-001", 2);
        assertThat(items.get(1)).containsExactly(ID.toHexString(), 1, "LAMP-010", 1);
        assertThat(mapped.childRows().get(1)).extracting(r -> r[2]).containsExactly("vip", "7");
    }

    @Test
    void missingValuesBecomeNullAndMissingArraysEmpty() {
        MappedDocument mapped = MappingEngine.map(ORDERS, new Document("_id", "abc"));

        assertThat(Arrays.copyOfRange(mapped.parentRow(), 1, mapped.parentRow().length)).containsOnlyNulls();
        assertThat(mapped.childRows()).allSatisfy(rows -> assertThat(rows).isEmpty());
    }

    @Test
    void rejectsDocumentsThatDoNotFit() {
        assertThatThrownBy(() -> MappingEngine.map(ORDERS, new Document("status", "x")))
                .isInstanceOf(DocumentMappingException.class).hasMessageContaining("primary key");
        assertThatThrownBy(() -> MappingEngine.map(ORDERS, new Document("_id", 1).append("total", "a lot")))
                .isInstanceOf(DocumentMappingException.class)
                .hasMessageContaining("column 'total'").hasMessageContaining("DECIMAL");
        assertThatThrownBy(() -> MappingEngine.map(ORDERS,
                new Document("_id", 1).append("customer", new Document("name", "Bartholomew J."))))
                .hasMessageContaining("14 characters, the column allows 10");
        assertThatThrownBy(() -> MappingEngine.map(ORDERS, new Document("_id", 1).append("items", "none")))
                .hasMessageContaining("expected an array at 'items'");
        assertThatThrownBy(() -> MappingEngine.map(ORDERS, new Document("_id", 1)
                .append("items", List.of(new Document("qty", 2.5)))))
                .hasMessageContaining("column 'qty'");
    }

    @Test
    void coercesTypes() {
        ColumnDef intCol = new ColumnDef("n", LogicalType.INT, null, true);
        ColumnDef longCol = new ColumnDef("n", LogicalType.LONG, null, true);
        ColumnDef ts = new ColumnDef("t", LogicalType.TIMESTAMP, null, true);
        ColumnDef bin = new ColumnDef("b", LogicalType.BINARY, null, true);
        ColumnDef str = new ColumnDef("s", LogicalType.STRING, null, true);
        ColumnDef dbl = new ColumnDef("d", LogicalType.DOUBLE, null, true);

        assertThat(TypeCoercer.coerce(42L, intCol)).isEqualTo(42);
        assertThat(TypeCoercer.coerce("42", intCol)).isEqualTo(42);
        assertThat(TypeCoercer.coerce(3.0, intCol)).isEqualTo(3);
        assertThatThrownBy(() -> TypeCoercer.coerce(3_000_000_000L, intCol)).hasMessageContaining("INT");
        assertThat(TypeCoercer.coerce(new Decimal128(7), longCol)).isEqualTo(7L);
        assertThat(TypeCoercer.coerce("2026-03-02", ts)).isEqualTo(OffsetDateTime.parse("2026-03-02T00:00Z"));
        assertThat(TypeCoercer.coerce("2026-03-02T09:15:00+05:30", ts))
                .isEqualTo(OffsetDateTime.parse("2026-03-02T09:15:00+05:30"));
        assertThat(TypeCoercer.coerce(0L, ts)).isEqualTo(OffsetDateTime.parse("1970-01-01T00:00Z"));
        assertThat(TypeCoercer.coerce(new Binary(new byte[]{1, 2}), bin)).isEqualTo(new byte[]{1, 2});
        assertThat(TypeCoercer.coerce(12.5, str)).isEqualTo("12.5");
        assertThat(TypeCoercer.coerce(1e10, str)).isEqualTo("10000000000");
        assertThat(TypeCoercer.coerce(true, str)).isEqualTo("true");
        assertThat(TypeCoercer.coerce(List.of(1, "a"), str)).isEqualTo("[1,\"a\"]");
        assertThat(TypeCoercer.coerce(Double.NaN, dbl)).isEqualTo(Double.NaN);
        assertThat(TypeCoercer.coerce(null, intCol)).isNull();
    }

    @Test
    void checkpointValuesKeepTheirType() {
        for (Object value : List.of(ID, "plain-id", 42, 42L, new Date(0), new Decimal128(new BigDecimal("1.5")))) {
            assertThat(BsonValues.decodeValue(BsonValues.encodeValue(value))).isEqualTo(value);
        }
    }
}
