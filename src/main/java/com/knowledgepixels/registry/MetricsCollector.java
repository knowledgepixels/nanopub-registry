package com.knowledgepixels.registry;

import com.knowledgepixels.registry.store.RegistryReader;
import com.knowledgepixels.registry.store.RegistryStores;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MetricsCollector {

    private final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);
    private final AtomicInteger loadCounter = new AtomicInteger(0);
    private final AtomicInteger nanopubCount = new AtomicInteger(0);
    private final AtomicInteger trustStateCounter = new AtomicInteger(0);
    private final AtomicInteger agentCount = new AtomicInteger(0);
    private final AtomicInteger accountCount = new AtomicInteger(0);

    private final Map<ServerStatus, AtomicInteger> statusStates = new ConcurrentHashMap<>();

    public MetricsCollector(MeterRegistry meterRegistry) {
        // Numeric metrics
        Gauge.builder("registry.load.counter", loadCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.nanopub.count", nanopubCount, AtomicInteger::get).register(meterRegistry);
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
        try (final RegistryReader reader = RegistryStores.get().reader()) {
            // Update numeric metrics
            Optional.ofNullable(reader.maxCounter()).map(Long::intValue).ifPresent(loadCounter::set);
            nanopubCount.set((int) reader.nanopubCount());

            extractIntegerValueFromField(reader, "trustStateCounter")
                    .ifPresent(trustStateCounter::set);

            agentCount.set((int) reader.agentCount());
            accountCount.set((int) reader.accountCount());

            // Update status gauge
            final var currentStatus = extractStringValueFromField(reader, "status")
                    .map(ServerStatus::valueOf)
                    .orElse(null);
            for (final var status : ServerStatus.values()) {
                statusStates.get(status).set(status.equals(currentStatus) ? 1 : 0);
            }
        } catch (Exception e) {
            logger.error("Error updating metrics: {}", e.getMessage());
        }
    }

    private Optional<Integer> extractIntegerValueFromField(RegistryReader reader, String fieldName) {
        return Optional
                .ofNullable(reader.serverInfoValue(fieldName))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
    }

    private Optional<String> extractStringValueFromField(RegistryReader reader, String fieldName) {
        return Optional
                .ofNullable(reader.serverInfoValue(fieldName))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

}
