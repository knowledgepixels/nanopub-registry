package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MetricsCollector}, which mirrors DB state into Prometheus gauges.
 * The gauges are read back through the registry, so this also pins the metric names
 * that dashboards and alerts are built on.
 */
class MetricsCollectorTest {

    /**
     * Micrometer gauges only weakly reference the state they read, so a collector that
     * is merely a local would be collectable and its gauges would report NaN. Holding it
     * in a field keeps it alive for the whole test method.
     */
    private MetricsCollector collector;

    private static double gauge(MeterRegistry registry, String name) {
        Gauge g = registry.find(name).gauge();
        assertNotNull(g, "gauge '" + name + "' is registered");
        return g.value();
    }

    private static double statusGauge(MeterRegistry registry, ServerStatus status) {
        Gauge g = registry.find("registry.server.status").tag("status", status.name()).gauge();
        assertNotNull(g, "status gauge for '" + status + "' is registered");
        return g.value();
    }

    @Test
    void registersAGaugePerMetricAndPerServerStatus() {
        MeterRegistry registry = new SimpleMeterRegistry();
        collector = new MetricsCollector(registry);

        assertEquals(0.0, gauge(registry, "registry.load.counter"));
        assertEquals(0.0, gauge(registry, "registry.nanopub.count"));
        assertEquals(0.0, gauge(registry, "registry.trust.state.counter"));
        assertEquals(0.0, gauge(registry, "registry.agent.count"));
        assertEquals(0.0, gauge(registry, "registry.account.count"));
        for (ServerStatus status : ServerStatus.values()) {
            assertEquals(0.0, statusGauge(registry, status), "no status is current before the first update");
        }
    }

    @Test
    void updateMetricsMirrorsDatabaseState() {
        MeterRegistry registry = new SimpleMeterRegistry();
        collector = new MetricsCollector(registry);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            dbMock.when(() -> RegistryDB.getMaxValue(db.session, Collection.NANOPUBS.toString(), "counter")).thenReturn(17L);
            when(db.collection(Collection.NANOPUBS.toString()).estimatedDocumentCount()).thenReturn(42L);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SERVER_INFO.toString(), "trustStateCounter")).thenReturn(5L);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SERVER_INFO.toString(), "status")).thenReturn("ready");
            when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(3L);
            when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(9L);

            collector.updateMetrics();
        }

        assertEquals(17.0, gauge(registry, "registry.load.counter"));
        assertEquals(42.0, gauge(registry, "registry.nanopub.count"));
        assertEquals(5.0, gauge(registry, "registry.trust.state.counter"));
        assertEquals(3.0, gauge(registry, "registry.agent.count"));
        assertEquals(9.0, gauge(registry, "registry.account.count"));

        assertEquals(1.0, statusGauge(registry, ServerStatus.ready), "the current status reads 1");
        assertEquals(0.0, statusGauge(registry, ServerStatus.updating), "every other status reads 0");
        assertEquals(0.0, statusGauge(registry, ServerStatus.launching));
    }

    @Test
    void updateMetricsLeavesGaugesAloneWhenValuesAreAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        collector = new MetricsCollector(registry);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            // An empty database: no counter, no trust state, no status yet.
            dbMock.when(() -> RegistryDB.getMaxValue(db.session, Collection.NANOPUBS.toString(), "counter")).thenReturn(null);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SERVER_INFO.toString(), "trustStateCounter")).thenReturn(null);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SERVER_INFO.toString(), "status")).thenReturn(null);
            when(db.collection(Collection.NANOPUBS.toString()).estimatedDocumentCount()).thenReturn(0L);
            when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(0L);

            collector.updateMetrics();
        }

        assertEquals(0.0, gauge(registry, "registry.load.counter"));
        assertEquals(0.0, gauge(registry, "registry.trust.state.counter"));
        for (ServerStatus status : ServerStatus.values()) {
            assertEquals(0.0, statusGauge(registry, status), "an unknown status marks nothing as current");
        }
    }

    @Test
    void updateMetricsSwallowsDatabaseFailures() {
        MeterRegistry registry = new SimpleMeterRegistry();
        collector = new MetricsCollector(registry);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // The collector runs on a 1-second Vert.x timer; a DB blip must not kill the loop.
            dbMock.when(RegistryDB::getClient).thenThrow(new IllegalStateException("mongo is down"));

            collector.updateMetrics();
        }

        assertEquals(0.0, gauge(registry, "registry.load.counter"), "gauges keep their previous value");
    }

}
