package com.data.schedular.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings under the {@code schedular.*} prefix.
 *
 * @param secretKey             base64-encoded 256-bit AES key used to encrypt connection passwords
 *                              (generate with {@code openssl rand -base64 32})
 * @param connectionTestTimeout upper bound for a connection test or object listing
 */
@ConfigurationProperties(prefix = "schedular")
public record SchedularProperties(String secretKey, Duration connectionTestTimeout) {

    public SchedularProperties {
        if (connectionTestTimeout == null) {
            connectionTestTimeout = Duration.ofSeconds(15);
        }
    }
}
