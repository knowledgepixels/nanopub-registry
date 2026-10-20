package com.knowledgepixels.registry;

import io.vertx.core.VertxOptions;
import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class ApplicationLauncher extends VertxApplication implements VertxApplicationHooks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationLauncher.class);

    public ApplicationLauncher(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        logger.info("Starting application with args={}", Arrays.toString(args));
        new ApplicationLauncher(args).launch();
    }

    @Override
    public void beforeStartingVertx(HookContext context) {
        logger.info("Configuring Vert.x metrics: Micrometer + Prometheus enabled, JVM metrics enabled");
        VertxOptions options = context.vertxOptions();
        options.setMetricsOptions(
                // Enable Micrometer metrics
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setJvmMetricsEnabled(true)
                        .addLabels(Label.HTTP_ROUTE)
                        .setEnabled(true)
        );
        logger.debug("VertxOptions after metrics configuration: {}", options);
    }

}
