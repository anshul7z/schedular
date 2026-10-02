package com.data.schedular.api.dto;

import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.ConnectionKind;
import com.data.schedular.domain.DbType;

import java.time.Instant;
import java.util.Map;

/** A connection as returned by the API. The password is never included, only whether one is set. */
public record ConnectionResponse(
        Long id,
        String name,
        ConnectionKind kind,
        DbType dbType,
        String uri,
        String host,
        Integer port,
        String database,
        String username,
        boolean passwordSet,
        Map<String, String> options,
        Instant createdAt,
        Instant updatedAt) {

    public static ConnectionResponse from(ConnectionDef def) {
        return new ConnectionResponse(def.getId(), def.getName(), def.getKind(), def.getDbType(), def.getUri(),
                def.getHost(), def.getPort(), def.getDatabase(), def.getUsername(),
                def.getPasswordEncrypted() != null, Map.copyOf(def.getOptions()), def.getCreatedAt(),
                def.getUpdatedAt());
    }
}
