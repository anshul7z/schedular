package com.data.schedular.service.connectivity;

import com.data.schedular.config.SchedularProperties;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.DbType;
import com.data.schedular.service.InvalidRequestException;
import com.data.schedular.service.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionResolverTest {

    private final SecretCipher cipher = new SecretCipher(
            new SchedularProperties(Base64.getEncoder().encodeToString(new byte[32]), null, null, null));
    private final ConnectionResolver resolver = new ConnectionResolver(cipher);

    @ParameterizedTest
    @CsvSource({
            "POSTGRESQL, jdbc:postgresql://db.local:5432/warehouse",
            "MYSQL,      jdbc:mysql://db.local:3306/warehouse",
            "MARIADB,    jdbc:mariadb://db.local:3306/warehouse",
            "SQLSERVER,  jdbc:sqlserver://db.local:1433;databaseName=warehouse",
            "ORACLE,     jdbc:oracle:thin:@//db.local:1521/warehouse",
            "MONGODB,    mongodb://db.local:27017/warehouse"
    })
    void buildsUrlFromHostWithDefaultPort(DbType type, String expectedUrl) {
        ConnectionDef def = hostConnection(type);

        assertThat(resolver.resolve(def).url()).isEqualTo(expectedUrl);
    }

    @Test
    void decryptsPasswordAndKeepsItOutOfToString() {
        ConnectionDef def = hostConnection(DbType.POSTGRESQL);
        def.setUsername("app");
        def.setPasswordEncrypted(cipher.encrypt("hunter2"));

        ResolvedConnection resolved = resolver.resolve(def);

        assertThat(resolved.password()).isEqualTo("hunter2");
        assertThat(resolved.toString()).doesNotContain("hunter2");
    }

    @Test
    void appendsMongoOptionsAsQueryParameters() {
        ConnectionDef def = hostConnection(DbType.MONGODB);
        def.setPort(27018);
        Map<String, String> options = new LinkedHashMap<>();
        options.put("authSource", "admin");
        options.put("replicaSet", "rs0");
        def.setOptions(options);

        ResolvedConnection resolved = resolver.resolve(def);

        assertThat(resolved.url()).isEqualTo("mongodb://db.local:27018/warehouse?authSource=admin&replicaSet=rs0");
        assertThat(resolved.options()).isEmpty();
    }

    @Test
    void passesJdbcOptionsAsDriverProperties() {
        ConnectionDef def = hostConnection(DbType.SQLSERVER);
        def.setOptions(Map.of("trustServerCertificate", "true"));

        assertThat(resolver.resolve(def).options()).containsEntry("trustServerCertificate", "true");
    }

    @Test
    void usesUriAsGiven() {
        ConnectionDef def = new ConnectionDef();
        def.setDbType(DbType.MONGODB);
        def.setUri("mongodb+srv://cluster0.example.net/shop?retryWrites=true");
        def.setUsername("app");

        ResolvedConnection resolved = resolver.resolve(def);

        assertThat(resolved.url()).isEqualTo("mongodb+srv://cluster0.example.net/shop?retryWrites=true");
        assertThat(MongoClientFactory.databaseName(resolved)).isEqualTo("shop");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MONGODB    | mongodb://app:secret@db.local:27017/shop",
            "MYSQL      | jdbc:mysql://app:secret@db.local:3306/shop",
            "POSTGRESQL | jdbc:postgresql://db.local/shop?user=app&password=secret",
            "SQLSERVER  | jdbc:sqlserver://db.local;databaseName=shop;password=secret"
    })
    void rejectsPasswordsEmbeddedInUri(DbType type, String uri) {
        ConnectionDef def = new ConnectionDef();
        def.setDbType(type);
        def.setUri(uri);

        assertThatThrownBy(() -> resolver.validate(def))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("password field");
    }

    @Test
    void rejectsUriForWrongDatabaseType() {
        ConnectionDef def = new ConnectionDef();
        def.setDbType(DbType.POSTGRESQL);
        def.setUri("jdbc:mysql://db.local/shop");

        assertThatThrownBy(() -> resolver.validate(def)).hasMessageContaining("jdbc:postgresql:");
    }

    @Test
    void requiresUriOrHost() {
        ConnectionDef def = new ConnectionDef();
        def.setDbType(DbType.MYSQL);

        assertThatThrownBy(() -> resolver.validate(def)).hasMessageContaining("uri or host");
    }

    @Test
    void requiresDatabaseForJdbcHostConnections() {
        ConnectionDef def = hostConnection(DbType.ORACLE);
        def.setDatabase(null);

        assertThatThrownBy(() -> resolver.validate(def)).hasMessageContaining("database is required");
    }

    private static ConnectionDef hostConnection(DbType type) {
        ConnectionDef def = new ConnectionDef();
        def.setDbType(type);
        def.setHost("db.local");
        def.setDatabase("warehouse");
        return def;
    }
}
