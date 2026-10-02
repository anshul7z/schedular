package com.data.schedular.service.connectivity;

import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.DbType;
import com.data.schedular.service.InvalidRequestException;
import com.data.schedular.service.SecretCipher;
import com.mongodb.ConnectionString;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates connection settings and turns a stored {@link ConnectionDef} into a {@link ResolvedConnection}.
 * Passwords must be given in the password field: URIs that embed one are rejected so no secret is stored in clear.
 */
@Component
public class ConnectionResolver {

    private static final Pattern JDBC_USERINFO = Pattern.compile("//[^/@;?]*:[^/@;?]*@");
    private static final Pattern JDBC_PASSWORD_PARAM = Pattern.compile("(?i)[;?&:]password=");

    private final SecretCipher cipher;

    public ConnectionResolver(SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** Checks that the settings are complete and consistent; throws {@link InvalidRequestException} if not. */
    public void validate(ConnectionDef def) {
        boolean hasUri = notBlank(def.getUri());
        if (!hasUri && !notBlank(def.getHost())) {
            throw new InvalidRequestException("Either uri or host must be set");
        }
        if (hasUri) {
            validateUri(def.getDbType(), def.getUri().trim());
        } else if (def.getDbType().isJdbc() && !notBlank(def.getDatabase())) {
            throw new InvalidRequestException("database is required when connecting by host");
        }
    }

    public ResolvedConnection resolve(ConnectionDef def) {
        validate(def);
        String password = cipher.decrypt(def.getPasswordEncrypted());
        String url = notBlank(def.getUri()) ? def.getUri().trim() : buildUrl(def);
        // MongoDB options are already part of the URL; JDBC options are passed to the driver as properties.
        Map<String, String> options = def.getDbType().isJdbc() ? def.getOptions() : Map.of();
        return new ResolvedConnection(def.getDbType(), url, blankToNull(def.getUsername()), password,
                blankToNull(def.getDatabase()), options);
    }

    private static void validateUri(DbType dbType, String uri) {
        if (dbType == DbType.MONGODB) {
            ConnectionString cs;
            try {
                cs = new ConnectionString(uri);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Invalid MongoDB connection string: " + e.getMessage());
            }
            if (cs.getPassword() != null) {
                throw new InvalidRequestException(
                        "Do not put the password in the uri; set it in the password field instead");
            }
            return;
        }
        String prefix = jdbcPrefix(dbType);
        if (!uri.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            throw new InvalidRequestException("A " + dbType + " uri must start with '" + prefix + "'");
        }
        if (JDBC_USERINFO.matcher(uri).find() || JDBC_PASSWORD_PARAM.matcher(uri).find()) {
            throw new InvalidRequestException(
                    "Do not put the password in the uri; set it in the password field instead");
        }
    }

    private static String jdbcPrefix(DbType dbType) {
        return switch (dbType) {
            case POSTGRESQL -> "jdbc:postgresql:";
            case MYSQL -> "jdbc:mysql:";
            case MARIADB -> "jdbc:mariadb:";
            case SQLSERVER -> "jdbc:sqlserver:";
            case ORACLE -> "jdbc:oracle:";
            case MONGODB -> throw new IllegalArgumentException("MongoDB has no JDBC URL");
        };
    }

    private static String buildUrl(ConnectionDef def) {
        String host = def.getHost().trim();
        int port = def.getPort() != null ? def.getPort() : def.getDbType().defaultPort();
        String db = def.getDatabase() == null ? "" : def.getDatabase().trim();
        return switch (def.getDbType()) {
            case MONGODB -> "mongodb://" + host + ":" + port + "/" + db + mongoQuery(def.getOptions());
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db;
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + db;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db;
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + db;
        };
    }

    private static String mongoQuery(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }
        return options.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }
}
