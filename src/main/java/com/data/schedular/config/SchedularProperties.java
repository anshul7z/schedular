package com.data.schedular.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings under the {@code schedular.*} prefix.
 *
 * @param secretKey             base64-encoded 256-bit AES key used to encrypt connection passwords
 *                              (generate with {@code openssl rand -base64 32})
 * @param connectionTestTimeout upper bound for a connection test or object listing
 * @param engine                migration engine settings
 */
@ConfigurationProperties(prefix = "schedular")
public record SchedularProperties(String secretKey, Duration connectionTestTimeout, Engine engine) {

    public SchedularProperties {
        if (connectionTestTimeout == null) {
            connectionTestTimeout = Duration.ofSeconds(15);
        }
        if (engine == null) {
            engine = new Engine(null, null);
        }
    }

    /**
     * @param retryBackoff    wait before the first retry of a failed batch; doubles on each further retry
     * @param maxRetryBackoff cap for the doubling wait
     */
    public record Engine(Duration retryBackoff, Duration maxRetryBackoff) {

        public Engine {
            if (retryBackoff == null) {
                retryBackoff = Duration.ofSeconds(1);
            }
            if (maxRetryBackoff == null) {
                maxRetryBackoff = Duration.ofSeconds(30);
            }
        }
    }
}
