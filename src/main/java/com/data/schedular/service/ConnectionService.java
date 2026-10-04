package com.data.schedular.service;

import com.data.schedular.api.dto.ConnectionRequest;
import com.data.schedular.config.SchedularProperties;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.domain.ConnectionKind;
import com.data.schedular.engine.mapping.SchemaInferrer;
import com.data.schedular.engine.mapping.SchemaInferrer.InferredMapping;
import com.data.schedular.engine.source.SourceConnector;
import com.data.schedular.engine.source.SourceConnectorFactory;
import com.data.schedular.repository.ConnectionDefRepository;
import com.data.schedular.repository.MigrationJobRepository;
import com.data.schedular.service.connectivity.ConnectionProbe;
import com.data.schedular.service.connectivity.ConnectionResolver;
import com.data.schedular.service.connectivity.ConnectionTestResult;
import com.data.schedular.service.connectivity.ConnectivityException;
import com.data.schedular.service.connectivity.ResolvedConnection;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int MAX_SAMPLE_SIZE = 1000;

    private final ConnectionDefRepository connections;
    private final MigrationJobRepository jobs;
    private final ConnectionResolver resolver;
    private final SecretCipher cipher;
    private final List<ConnectionProbe> probes;
    private final SourceConnectorFactory sources;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConnectionService(ConnectionDefRepository connections, MigrationJobRepository jobs,
                             ConnectionResolver resolver, SecretCipher cipher, List<ConnectionProbe> probes,
                             SourceConnectorFactory sources, SchedularProperties properties) {
        this.connections = connections;
        this.jobs = jobs;
        this.resolver = resolver;
        this.cipher = cipher;
        this.probes = probes;
        this.sources = sources;
        this.timeout = properties.connectionTestTimeout();
    }

    @Transactional(readOnly = true)
    public List<ConnectionDef> list() {
        return connections.findAll();
    }

    @Transactional(readOnly = true)
    public ConnectionDef get(Long id) {
        return connections.findById(id).orElseThrow(() -> new NotFoundException("Connection", id));
    }

    @Transactional
    public ConnectionDef create(ConnectionRequest request) {
        if (connections.existsByName(request.name())) {
            throw new ConflictException("A connection named '" + request.name() + "' already exists");
        }
        ConnectionDef def = new ConnectionDef();
        apply(def, request);
        def.setPasswordEncrypted(isEmpty(request.password()) ? null : cipher.encrypt(request.password()));
        resolver.validate(def);
        return connections.save(def);
    }

    @Transactional
    public ConnectionDef update(Long id, ConnectionRequest request) {
        ConnectionDef def = get(id);
        if (connections.existsByNameAndIdNot(request.name(), id)) {
            throw new ConflictException("A connection named '" + request.name() + "' already exists");
        }
        if (def.getKind() != request.dbType().kind() && jobs.existsByConnectionId(id)) {
            throw new ConflictException("Cannot change a connection between source and target while jobs use it");
        }
        apply(def, request);
        if (request.password() != null) {
            def.setPasswordEncrypted(request.password().isEmpty() ? null : cipher.encrypt(request.password()));
        }
        resolver.validate(def);
        return def;
    }

    @Transactional
    public void delete(Long id) {
        ConnectionDef def = get(id);
        if (jobs.existsByConnectionId(id)) {
            throw new ConflictException("Connection " + id + " is used by one or more jobs");
        }
        connections.delete(def);
    }

    /** Tests a saved connection. */
    public ConnectionTestResult test(Long id) {
        return test(resolver.resolve(get(id)));
    }

    /** Tests connection settings without saving them. */
    public ConnectionTestResult test(ConnectionRequest request) {
        ConnectionDef def = new ConnectionDef();
        apply(def, request);
        def.setPasswordEncrypted(isEmpty(request.password()) ? null : cipher.encrypt(request.password()));
        return test(resolver.resolve(def));
    }

    /** Samples a source collection and proposes a mapping for it. */
    public InferredMapping inferMapping(Long id, String collection, int sampleSize) {
        if (sampleSize < 1 || sampleSize > MAX_SAMPLE_SIZE) {
            throw new InvalidRequestException("sampleSize must be between 1 and " + MAX_SAMPLE_SIZE);
        }
        ConnectionDef def = get(id);
        if (def.getKind() != ConnectionKind.SOURCE) {
            throw new InvalidRequestException("Connection '" + def.getName() + "' is not a source");
        }
        ResolvedConnection connection = resolver.resolve(def);
        List<Map<String, Object>> documents;
        try {
            documents = withTimeout(() -> {
                try (SourceConnector source = sources.open(connection)) {
                    return source.sample(collection, sampleSize);
                }
            });
        } catch (TimeoutException e) {
            throw new ConnectivityException("Timed out after " + timeout.toSeconds() + "s sampling '"
                    + collection + "'", e);
        } catch (Exception e) {
            throw new ConnectivityException("Could not sample '" + collection + "': " + rootMessage(e), e);
        }
        return SchemaInferrer.infer(collection, documents);
    }

    /** Lists the collections or tables reachable through a saved connection. */
    public List<String> listObjects(Long id) {
        ResolvedConnection connection = resolver.resolve(get(id));
        try {
            return withTimeout(() -> probeFor(connection).listObjects(connection));
        } catch (TimeoutException e) {
            throw new ConnectivityException("Timed out after " + timeout.toSeconds() + "s listing objects", e);
        } catch (Exception e) {
            throw new ConnectivityException("Could not list objects: " + rootMessage(e), e);
        }
    }

    private ConnectionTestResult test(ResolvedConnection connection) {
        long start = System.nanoTime();
        try {
            return withTimeout(() -> probeFor(connection).test(connection));
        } catch (TimeoutException e) {
            return ConnectionTestResult.failed("Timed out after " + timeout.toSeconds() + "s", elapsedMs(start));
        } catch (Exception e) {
            log.info("Connection test failed for {}: {}", connection, rootMessage(e));
            return ConnectionTestResult.failed(rootMessage(e), elapsedMs(start));
        }
    }

    private ConnectionProbe probeFor(ResolvedConnection connection) {
        return probes.stream()
                .filter(p -> p.supports(connection.dbType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No probe for " + connection.dbType()));
    }

    /**
     * Runs a blocking driver call on a virtual thread so a hung connect cannot hold the request forever.
     * On timeout the call is interrupted and abandoned; the driver's own connect timeout ends it eventually.
     */
    private <T> T withTimeout(Callable<T> call) throws Exception {
        Future<T> future = executor.submit(call);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static void apply(ConnectionDef def, ConnectionRequest request) {
        def.setName(request.name().trim());
        def.setDbType(request.dbType());
        def.setUri(blankToNull(request.uri()));
        def.setHost(blankToNull(request.host()));
        def.setPort(request.port());
        def.setDatabase(blankToNull(request.database()));
        def.setUsername(blankToNull(request.username()));
        def.setOptions(request.options());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
