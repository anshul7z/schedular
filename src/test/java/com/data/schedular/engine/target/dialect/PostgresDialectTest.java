package com.data.schedular.engine.target.dialect;

import com.data.schedular.engine.mapping.MappingCompiler;
import com.data.schedular.engine.mapping.MappingPlan;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();
    private final MappingPlan plan = MappingCompiler.compile("sales.orders", """
            {"fields": [{"path": "status", "length": 20}, {"path": "total", "type": "DECIMAL"},
                        {"path": "meta", "strategy": "JSON"},
                        {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
                         "fields": [{"path": "qty", "type": "INT"}]}]}
            """);

    @Test
    void quotesIdentifiers() {
        assertThat(dialect.quote("sales.orders")).isEqualTo("\"sales\".\"orders\"");
        assertThat(dialect.quote("we\"ird")).isEqualTo("\"we\"\"ird\"");
    }

    @Test
    void generatesDdl() {
        assertThat(dialect.createTable(plan.table())).isEqualTo(
                "CREATE TABLE \"sales\".\"orders\" (\"id\" varchar(255) NOT NULL, \"status\" varchar(20), "
                        + "\"total\" numeric, \"meta\" jsonb, PRIMARY KEY (\"id\"))");
        assertThat(dialect.createTable(plan.children().getFirst().table())).isEqualTo(
                "CREATE TABLE \"order_items\" (\"parent_id\" varchar(255) NOT NULL, \"ordinal\" integer NOT NULL, "
                        + "\"qty\" integer, PRIMARY KEY (\"parent_id\", \"ordinal\"))");
        assertThat(dialect.addColumn(plan.table(), plan.table().columns().get(2)))
                .isEqualTo("ALTER TABLE \"sales\".\"orders\" ADD COLUMN \"total\" numeric");
    }

    @Test
    void generatesDml() {
        assertThat(dialect.upsert(plan.table())).isEqualTo(
                "INSERT INTO \"sales\".\"orders\" (\"id\", \"status\", \"total\", \"meta\") VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (\"id\") DO UPDATE SET \"status\" = EXCLUDED.\"status\", "
                        + "\"total\" = EXCLUDED.\"total\", \"meta\" = EXCLUDED.\"meta\"");
        MappingPlan keyOnly = MappingCompiler.compile("ids", "{}");
        assertThat(dialect.upsert(keyOnly.table())).endsWith("ON CONFLICT (\"id\") DO NOTHING");
        assertThat(dialect.deleteWhereIn(plan.children().getFirst().table(), "parent_id", 3))
                .isEqualTo("DELETE FROM \"order_items\" WHERE \"parent_id\" IN (?, ?, ?)");
    }

    @Test
    void classifiesErrors() {
        SQLException batch = new BatchUpdateException("Batch entry 0 was aborted", "22001", 0, new int[0], null);
        batch.setNextException(new SQLException("value too long for type character varying(20)", "22001"));

        assertThat(dialect.isDataError(batch)).isTrue();
        assertThat(dialect.isTransient(batch)).isFalse();
        assertThat(SqlDialect.describe(batch)).isEqualTo("[22001] value too long for type character varying(20)");
        assertThat(dialect.isTransient(new SQLException("deadlock detected", "40P01"))).isTrue();
        assertThat(dialect.isTransient(new SQLException("connection refused", "08001"))).isTrue();
        assertThat(dialect.isDataError(new SQLException("relation does not exist", "42P01"))).isFalse();
    }
}
