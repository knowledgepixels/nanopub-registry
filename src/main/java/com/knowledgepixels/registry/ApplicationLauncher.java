package com.knowledgepixels.registry;

import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class ApplicationLauncher extends Launcher {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationLauncher.class);

    public static void main(String[] args) {
        logger.info("Starting application with args={}", Arrays.toString(args));
        new ApplicationLauncher().dispatch(args);
    }

    @Override
    public void beforeStartingVertx(VertxOptions options) {
        logger.info("Configuring Vert.x metrics: Micrometer + Prometheus enabled, JVM metrics enabled");
        options.setMetricsOptions(
                // Enable Micrometer metrics
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setJvmMetricsEnabled(true)
                        .setEnabled(true)
        );
        logger.debug("VertxOptions after metrics configuration: {}", options);
    }

}
