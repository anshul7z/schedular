package com.data.schedular.engine.target;

import com.data.schedular.domain.WriteMode;
import com.data.schedular.engine.mapping.MappingEngine.MappedDocument;
import com.data.schedular.engine.mapping.MappingPlan;
import com.data.schedular.engine.mapping.MappingPlan.ChildPlan;
import com.data.schedular.engine.mapping.TableDef;
import com.data.schedular.engine.target.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Writes mapped documents to the target in one transaction per call. */
public final class JdbcTargetWriter {

    /** Rows per {@code executeBatch} and parameters per {@code IN} list (Oracle allows at most 1000). */
    private static final int CHUNK = 500;

    private JdbcTargetWriter() {
    }

    /**
     * Upserts (or inserts) the parent rows, replaces each parent's child rows, and commits.
     * Rolls back and rethrows on any error.
     *
     * @return the number of rows written across all tables
     */
    public static long write(Connection connection, SqlDialect dialect, MappingPlan plan, WriteMode mode,
                             List<MappedDocument> documents) throws SQLException {
        if (documents.isEmpty()) {
            return 0;
        }
        connection.setAutoCommit(false);
        try {
            // INSERT mode is append-only; the other modes key on the primary key so retries are idempotent.
            boolean upsert = mode != WriteMode.INSERT;
            long written = executeBatch(connection, dialect, plan.table(),
                    upsert ? dialect.upsert(plan.table()) : dialect.insert(plan.table()),
                    documents.stream().map(MappedDocument::parentRow).toList());

            for (int c = 0; c < plan.children().size(); c++) {
                ChildPlan child = plan.children().get(c);
                if (upsert) {
                    deleteChildren(connection, dialect, child.table(), documents);
                }
                List<Object[]> rows = new ArrayList<>();
                for (MappedDocument doc : documents) {
                    rows.addAll(doc.childRows().get(c));
                }
                written += executeBatch(connection, dialect, child.table(), dialect.insert(child.table()), rows);
            }
            connection.commit();
            return written;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
        }
    }

    /** Empties the plan's tables (children first) in one transaction, for TRUNCATE_AND_LOAD. */
    public static void truncate(Connection connection, SqlDialect dialect, MappingPlan plan) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (ChildPlan child : plan.children()) {
                statement.execute(dialect.truncate(child.table()));
            }
            statement.execute(dialect.truncate(plan.table()));
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
        }
    }

    private static long executeBatch(Connection connection, SqlDialect dialect, TableDef table, String sql,
                                     List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    dialect.bind(statement, i + 1, row[i], table.columns().get(i));
                }
                statement.addBatch();
                if (++pending == CHUNK) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
        return rows.size();
    }

    private static void deleteChildren(Connection connection, SqlDialect dialect, TableDef child,
                                       List<MappedDocument> documents) throws SQLException {
        var parentId = child.columns().getFirst();
        for (int from = 0; from < documents.size(); from += CHUNK) {
            List<MappedDocument> chunk = documents.subList(from, Math.min(from + CHUNK, documents.size()));
            try (PreparedStatement statement = connection.prepareStatement(
                    dialect.deleteWhereIn(child, parentId.name(), chunk.size()))) {
                for (int i = 0; i < chunk.size(); i++) {
                    dialect.bind(statement, i + 1, chunk.get(i).key(), parentId);
                }
                statement.executeUpdate();
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }
}
