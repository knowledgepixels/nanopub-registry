package com.knowledgepixels.registry;

import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationLauncherTest {

    @Test
    void metricsOptionsAreEnabledBeforeStartingVertx() {
        ApplicationLauncher launcher = new ApplicationLauncher();
        VertxOptions options = new VertxOptions();

        launcher.beforeStartingVertx(options);

        MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
        assertNotNull(metricsOptions);
        assertTrue(metricsOptions.isEnabled());
        assertTrue(metricsOptions.isJvmMetricsEnabled());
        assertTrue(metricsOptions.getPrometheusOptions().isEnabled());
    }

}