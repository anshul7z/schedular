package com.data.schedular.support;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDatabasesSmokeTest {

    @Test
    void postgresAnswers() throws Exception {
        try (Connection c = DriverManager.getConnection(EmbeddedDatabases.postgresJdbcUrl(), "postgres", "");
             ResultSet rs = c.createStatement().executeQuery("select version()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).startsWith("PostgreSQL");
        }
    }

    @Test
    void mongoAnswers() {
        try (MongoClient client = MongoClients.create(EmbeddedDatabases.mongoUri())) {
            Document pong = client.getDatabase("admin").runCommand(new Document("ping", 1));
            assertThat(pong.get("ok")).isIn(1, 1.0);
        }
    }
}
