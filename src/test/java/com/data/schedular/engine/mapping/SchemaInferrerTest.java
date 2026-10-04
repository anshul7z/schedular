package com.data.schedular.engine.mapping;

import com.data.schedular.engine.mapping.MappingSpec.FieldSpec;
import com.data.schedular.engine.mapping.SchemaInferrer.InferredMapping;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInferrerTest {

    @Test
    void proposesColumnsJsonAndChildTables() {
        List<Map<String, Object>> docs = List.of(
                new Document("_id", new ObjectId())
                        .append("status", "NEW")
                        .append("qty", 2)
                        .append("big", 3_000_000_000L)
                        .append("score", 1)
                        .append("price", new Decimal128(new BigDecimal("9.99")))
                        .append("paid", true)
                        .append("lastModified", new Date())
                        .append("customer", new Document("name", "Asha").append("vip", true))
                        .append("meta", new Document("a", new Document("deep", 1)))
                        .append("items", List.of(new Document("sku", "A").append("qty", 1)
                                .append("attrs", new Document("color", "red"))))
                        .append("tags", List.of("x", "y"))
                        .append("empty", List.of())
                        .append("mixed", List.of(1, new Document("a", 1)))
                        .append("notes", "n".repeat(300))
                        .append("a.b", "dotted keys cannot be addressed")
                        .append("Order Total", 5),
                new Document("_id", new ObjectId())
                        .append("status", "SHIPPED")
                        .append("qty", 3)
                        .append("score", 1.5)
                        .append("paid", "yes"));

        InferredMapping inferred = SchemaInferrer.infer("Orders-2024", docs);

        assertThat(inferred.targetTable()).isEqualTo("Orders_2024");
        assertThat(inferred.sampledDocuments()).isEqualTo(2);
        assertThat(inferred.watermarkField()).isEqualTo("lastModified");
        MappingSpec spec = inferred.mapping();
        assertThat(spec.primaryKey()).isNull(); // the default (_id -> id STRING) fits ObjectIds
        assertThat(spec.unmappedFields()).isEqualTo(MappingSpec.UnmappedFields.JSON_OVERFLOW_COLUMN);

        Map<String, FieldSpec> byPath = spec.fields().stream()
                .collect(java.util.stream.Collectors.toMap(FieldSpec::path, f -> f));
        assertThat(byPath.get("status").type()).isNull(); // STRING is the default
        assertThat(byPath.get("qty").type()).isEqualTo(LogicalType.INT);
        assertThat(byPath.get("big").type()).isEqualTo(LogicalType.LONG);
        assertThat(byPath.get("score").type()).isEqualTo(LogicalType.DOUBLE);
        assertThat(byPath.get("price").type()).isEqualTo(LogicalType.DECIMAL);
        assertThat(byPath.get("paid").type()).isNull(); // boolean and string seen: stored as text
        assertThat(byPath.get("lastModified").type()).isEqualTo(LogicalType.TIMESTAMP);
        assertThat(byPath.get("customer.name").column()).isEqualTo("customer_name");
        assertThat(byPath.get("customer.vip").type()).isEqualTo(LogicalType.BOOLEAN);
        assertThat(byPath.get("meta").strategy()).isEqualTo(NestedStrategy.JSON);
        assertThat(byPath.get("empty").strategy()).isEqualTo(NestedStrategy.JSON);
        assertThat(byPath.get("mixed").strategy()).isEqualTo(NestedStrategy.JSON);
        assertThat(byPath.get("notes").length()).isEqualTo(1000);
        assertThat(byPath.get("Order Total").column()).isEqualTo("Order_Total");
        assertThat(byPath).doesNotContainKey("a.b");

        FieldSpec items = byPath.get("items");
        assertThat(items.strategy()).isEqualTo(NestedStrategy.CHILD_TABLE);
        assertThat(items.childTable()).isEqualTo("Orders_2024_items");
        assertThat(items.fields()).extracting(FieldSpec::path).containsExactly("sku", "qty", "attrs");
        assertThat(items.fields().get(2).strategy()).isEqualTo(NestedStrategy.JSON);
        FieldSpec tags = byPath.get("tags");
        assertThat(tags.strategy()).isEqualTo(NestedStrategy.CHILD_TABLE);
        assertThat(tags.fields()).isNull();

        MappingPlan plan = MappingCompiler.compile(inferred.targetTable(), spec);
        assertThat(plan.tables()).hasSize(3);
    }

    @Test
    void picksKeyTypeFromIds() {
        InferredMapping ints = SchemaInferrer.infer("t", List.of(new Document("_id", 1), new Document("_id", 2)));
        assertThat(ints.mapping().primaryKey().type()).isEqualTo(LogicalType.INT);

        InferredMapping longIds = SchemaInferrer.infer("t", List.of(new Document("_id", 1), new Document("_id", 5L)));
        assertThat(longIds.mapping().primaryKey().type()).isEqualTo(LogicalType.LONG);

        InferredMapping empty = SchemaInferrer.infer("events", List.of());
        assertThat(empty.mapping().fields()).isEmpty();
        assertThat(empty.watermarkField()).isNull();
    }

    @Test
    void keepsColumnNamesUnique() {
        InferredMapping inferred = SchemaInferrer.infer("t", List.of(new Document("_id", 1)
                .append("a_b", 1).append("a", new Document("b", 2)).append("id", "clashes with the key")));

        assertThat(inferred.mapping().fields()).extracting(FieldSpec::column)
                .containsExactly("a_b", "a_b_2", "id_2");
    }
}
