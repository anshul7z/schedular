package com.data.schedular.engine.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappingCompilerTest {

    @Test
    void appliesDefaults() {
        MappingPlan plan = MappingCompiler.compile("orders", """
                {"fields": [{"path": "customer.name"}, {"path": "meta", "strategy": "JSON"}]}
                """);

        assertThat(plan.table().columnNames()).containsExactly("id", "customer_name", "meta");
        assertThat(plan.table().primaryKey()).containsExactly("id");
        assertThat(plan.primaryKey().path()).containsExactly("_id");
        assertThat(plan.primaryKey().column().type()).isEqualTo(LogicalType.STRING);
        assertThat(plan.primaryKey().column().nullable()).isFalse();
        assertThat(plan.columns().get(0).column().type()).isEqualTo(LogicalType.STRING);
        assertThat(plan.columns().get(1).column().type()).isEqualTo(LogicalType.JSON);
        assertThat(plan.overflow()).isFalse();
    }

    @Test
    void emptyMappingKeepsOnlyTheKey() {
        MappingPlan plan = MappingCompiler.compile("events", (String) null);

        assertThat(plan.table().columnNames()).containsExactly("id");
    }

    @Test
    void compilesChildTables() {
        MappingPlan plan = MappingCompiler.compile("orders", """
                {"primaryKey": {"type": "LONG", "source": "orderNo", "column": "order_no"},
                 "fields": [
                   {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
                    "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}, {"path": "attrs", "strategy": "JSON"}]},
                   {"path": "tags", "strategy": "child_table", "childTable": "order_tags"}
                 ]}
                """);

        assertThat(plan.children()).hasSize(2);
        TableDef items = plan.children().get(0).table();
        assertThat(items.columnNames()).containsExactly("parent_id", "ordinal", "sku", "qty", "attrs");
        assertThat(items.primaryKey()).containsExactly("parent_id", "ordinal");
        assertThat(items.columns().get(0).type()).isEqualTo(LogicalType.LONG);
        TableDef tags = plan.children().get(1).table();
        assertThat(tags.columnNames()).containsExactly("parent_id", "ordinal", "value");
        assertThat(plan.tables()).extracting(TableDef::name).containsExactly("orders", "order_items", "order_tags");
        assertThat(plan.mappedTopLevel()).containsExactlyInAnyOrder("orderNo", "items", "tags");
    }

    @Test
    void addsOverflowColumn() {
        MappingPlan plan = MappingCompiler.compile("customers", """
                {"fields": [{"path": "name"}], "unmappedFields": "JSON_OVERFLOW_COLUMN"}
                """);

        assertThat(plan.table().columnNames()).containsExactly("id", "name", "_extra");
        assertThat(plan.overflow()).isTrue();
    }

    @Test
    void acceptsSchemaQualifiedTables() {
        MappingPlan plan = MappingCompiler.compile("sales.orders", "{}");

        assertThat(plan.table().schema()).isEqualTo("sales");
        assertThat(plan.table().simpleName()).isEqualTo("orders");
    }

    @Test
    void rejectsInvalidMappings() {
        assertThatThrownBy(() -> MappingCompiler.compile("orders", "{\"fieldz\": []}"))
                .isInstanceOf(InvalidMappingException.class).hasMessageContaining("fieldz");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", "{not json"))
                .isInstanceOf(InvalidMappingException.class);
        assertThatThrownBy(() -> MappingCompiler.compile("drop table x;", "{}"))
                .hasMessageContaining("table name");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "a", "column": "x"}, {"path": "b", "column": "X"}]}"""))
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "id"}]}"""))
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "items", "strategy": "CHILD_TABLE"}]}"""))
                .hasMessageContaining("childTable");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "items", "strategy": "CHILD_TABLE", "childTable": "orders"}]}"""))
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "items", "strategy": "CHILD_TABLE", "childTable": "items",
                  "fields": [{"path": "subs", "strategy": "CHILD_TABLE", "childTable": "subs"}]}]}"""))
                .hasMessageContaining("Nested CHILD_TABLE");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "items", "strategy": "CHILD_TABLE", "childTable": "items",
                  "fields": [{"path": "ordinal"}]}]}"""))
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"primaryKey": {"type": "JSON"}}"""))
                .hasMessageContaining("primaryKey.type");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "a..b"}]}"""))
                .hasMessageContaining("Invalid field path");
        assertThatThrownBy(() -> MappingCompiler.compile("orders", """
                {"fields": [{"path": "meta", "strategy": "JSON", "type": "INT"}]}"""))
                .hasMessageContaining("remove type");
    }
}
