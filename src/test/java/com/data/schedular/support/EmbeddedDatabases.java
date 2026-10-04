package com.data.schedular.support;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.io.ProcessOutput;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.transitions.Start;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real PostgreSQL and MongoDB processes for engine tests, started lazily once per test JVM
 * and stopped on JVM exit. No Docker needed: the binaries are downloaded and cached on first use.
 */
public final class EmbeddedDatabases {

    private static EmbeddedPostgres postgres;
    private static TransitionWalker.ReachedState<RunningMongodProcess> mongo;

    private EmbeddedDatabases() {
    }

    public static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
        }
        return postgres;
    }

    /** JDBC URL of the embedded server's {@code postgres} database (user {@code postgres}, no password). */
    public static String postgresJdbcUrl() {
        return postgres().getJdbcUrl("postgres", "postgres");
    }

    public static synchronized RunningMongodProcess mongo() {
        if (mongo == null) {
            mongo = Mongod.instance()
                    .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                    .start(Version.Main.V8_0);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> mongo.close()));
        }
        return mongo.current();
    }

    /** Connection string of the embedded MongoDB server (no authentication). */
    public static String mongoUri() {
        var address = mongo().getServerAddress();
        return "mongodb://" + address.getHost() + ":" + address.getPort();
    }
}
