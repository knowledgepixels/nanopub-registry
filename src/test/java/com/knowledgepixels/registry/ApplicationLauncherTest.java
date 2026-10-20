package com.knowledgepixels.registry;

import io.vertx.core.VertxOptions;
import io.vertx.launcher.application.HookContext;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationLauncherTest {

    @Test
    void metricsOptionsAreEnabledBeforeStartingVertx() {
        ApplicationLauncher launcher = new ApplicationLauncher(new String[0]);
        VertxOptions options = new VertxOptions();
        HookContext context = mock(HookContext.class);
        when(context.vertxOptions()).thenReturn(options);

        launcher.beforeStartingVertx(context);

        MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
        assertNotNull(metricsOptions);
        assertTrue(metricsOptions.isEnabled());
        assertTrue(metricsOptions.isJvmMetricsEnabled());
        assertTrue(metricsOptions.getPrometheusOptions().isEnabled());
        assertTrue(metricsOptions.getLabels().contains(Label.HTTP_ROUTE));
    }

}