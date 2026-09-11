package com.testpilot.repository.connector;

import com.testpilot.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RepositoryConnectorRegistry {

    private final Map<RepositoryTransport, RepositoryConnector> connectors;

    public RepositoryConnectorRegistry(List<RepositoryConnector> connectorList) {
        Map<RepositoryTransport, RepositoryConnector> mapped = new EnumMap<>(RepositoryTransport.class);
        connectorList.forEach(connector -> mapped.put(connector.transport(), connector));
        this.connectors = Map.copyOf(mapped);
    }

    public RepositoryConnector require(RepositoryTransport transport) {
        RepositoryConnector connector = connectors.get(transport);
        if (connector == null) {
            throw new InvalidRequestException("Repository transport is not configured: " + transport);
        }
        return connector;
    }
}
