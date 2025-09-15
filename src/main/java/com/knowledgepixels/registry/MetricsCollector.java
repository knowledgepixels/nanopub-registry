package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.knowledgepixels.registry.RegistryDB.*;

public final class MetricsCollector {

    private final AtomicInteger loadCounter = new AtomicInteger(0);
    private final AtomicInteger trustStateCounter = new AtomicInteger(0);
    private final AtomicInteger agentCount = new AtomicInteger(0);
    private final AtomicInteger accountCount = new AtomicInteger(0);

    private final Map<ServerStatus, AtomicInteger> statusStates = new ConcurrentHashMap<>();

    public MetricsCollector(MeterRegistry meterRegistry) {
        // Numeric metrics
        Gauge.builder("registry.load.counter", loadCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.trust.state.counter", trustStateCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.agent.count", agentCount, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.account.count", accountCount, AtomicInteger::get).register(meterRegistry);

        // Status label metrics
        for (final var status : ServerStatus.values()) {
            AtomicInteger stateGauge = new AtomicInteger(0);
            statusStates.put(status, stateGauge);
            Gauge.builder("registry.server.status", stateGauge, AtomicInteger::get)
                    .description("Server status (1 if current)")
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
    }

    public void updateMetrics() {
        try (final var session = RegistryDB.getClient().startSession()) {
            // Update numeric metrics
            extractMaximalIntegerValueFromField(session, "nanopubs", "counter")
                    .ifPresent(loadCounter::set);

            extractIntegerValueFromField(session, "serverInfo", "trustStateCounter")
                    .ifPresent(trustStateCounter::set);

            agentCount.set(countDocumentsInCollection(session, "agents"));
            accountCount.set(countDocumentsInCollection(session, "accounts"));

            // Update status gauge
            final var currentStatus = extractStringValueFromField(session, "serverInfo", "status")
                    .map(ServerStatus::valueOf)
                    .orElse(null);
            for (final var status : ServerStatus.values()) {
                statusStates.get(status).set(status.equals(currentStatus) ? 1 : 0);
            }
        } catch (Exception e) {
            System.err.printf("Error updating metrics: %s%n", e.getMessage());
        }
    }

    private Optional<Integer> extractMaximalIntegerValueFromField(
            ClientSession session,
            String collectionName,
            String fieldName
    ) {
        return Optional
                .ofNullable(getMaxValue(session, collectionName, fieldName))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
    }

    private Optional<Integer> extractIntegerValueFromField(
            ClientSession session,
            String collectionName,
            String fieldName
    ) {
        return Optional
                .ofNullable(getValue(session, collectionName, fieldName))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
    }

    private Optional<String> extractStringValueFromField(
            ClientSession session,
            String collectionName,
            String fieldName
    ) {
        return Optional
                .ofNullable(getValue(session, collectionName, fieldName))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private int countDocumentsInCollection(ClientSession session, String collectionName) {
        return (int) collection(collectionName).countDocuments(session);
    }
}
