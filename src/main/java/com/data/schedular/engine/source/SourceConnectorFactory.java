package com.data.schedular.engine.source;

import com.data.schedular.engine.source.mongo.MongoSourceConnector;
import com.data.schedular.service.connectivity.ResolvedConnection;
import org.springframework.stereotype.Component;

/** Opens the {@link SourceConnector} matching a connection's database type. */
@Component
public class SourceConnectorFactory {

    public SourceConnector open(ResolvedConnection connection) {
        return switch (connection.dbType()) {
            case MONGODB -> new MongoSourceConnector(connection);
            default -> throw new IllegalArgumentException(connection.dbType() + " is not a supported source");
        };
    }
}
