package com.data.schedular.api.dto;

import com.data.schedular.domain.DbType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Create/update payload for a connection. Give either {@code uri} or {@code host} (+ {@code port}, {@code database}).
 * On update, a null {@code password} keeps the stored one and an empty string clears it.
 */
public record ConnectionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull DbType dbType,
        @Size(max = 2000) String uri,
        @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 255) String database,
        @Size(max = 255) String username,
        @Size(max = 500) String password,
        Map<String, String> options) {
}
