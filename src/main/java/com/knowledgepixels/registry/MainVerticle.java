package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.micrometer.PrometheusScrapingHandler;
import io.vertx.micrometer.backends.BackendRegistries;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.isSet;

public class MainVerticle extends AbstractVerticle {

    private final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Promise<Void> startPromise) {
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        server.requestHandler(router);
        server.listen(9292);
        router.route(HttpMethod.GET, "/agent*").handler(c -> {
            // /agent/... | /agents | /agentAccounts
            ListPage.show(c);
        });
        router.route(HttpMethod.GET, "/nanopubs*").handler(c -> ListPage.show(c));
        router.route(HttpMethod.GET, "/list*").handler(c -> ListPage.show(c));
        router.route(HttpMethod.GET, "/pubkeys*").handler(c -> ListPage.show(c));
        router.route(HttpMethod.GET, "/np/").handler(c -> c.response().putHeader("Location", "/").setStatusCode(307).end());
        router.route(HttpMethod.GET, "/np/*").handler(c -> NanopubPage.show(c));
        router.route(HttpMethod.GET, "/get/").handler(c -> c.response().putHeader("Location", "/").setStatusCode(307).end());
        router.route(HttpMethod.GET, "/get/*").handler(c -> NanopubPage.show(c, true));
        router.route(HttpMethod.GET, "/debug/*").handler(c -> DebugPage.show(c));
        router.route(HttpMethod.GET, "/style.css").handler(c -> ResourcePage.show(c, "style.css", "text/css"));

        // Metrics
        final var metricsHttpServer = vertx.createHttpServer();
        final var metricsRouter = Router.router(vertx);
        metricsHttpServer.requestHandler(metricsRouter).listen(9293);

        final var metricsRegistry = (PrometheusMeterRegistry) BackendRegistries.getDefaultNow();
        final var collector = new MetricsCollector(metricsRegistry);
        metricsRouter.route("/metrics").handler(PrometheusScrapingHandler.create(metricsRegistry));

        router.route(HttpMethod.GET, "/*").handler(c -> MainPage.show(c));
        router.route(HttpMethod.HEAD, "/*").handler(c -> MainPage.show(c));

        Handler<RoutingContext> postHandler = c -> {
            c.request().bodyHandler(bh -> {
                vertx.<Void>executeBlocking(() -> {
                    String contentType = c.request().getHeader("Content-Type");
                    Nanopub np = null;
                    try {
                        np = new NanopubImpl(bh.toString(), Rio.getParserFormatForMIMEType(contentType).orElse(RDFFormat.TRIG));
                    } catch (MalformedNanopubException ex) {
                        ex.printStackTrace();
                    }
                    if (np != null) {
                        try (ClientSession s = RegistryDB.getClient().startSession()) {
                            String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
                            if (has(s, Collection.NANOPUBS.toString(), ac)) {
                                logger.info("POST: known nanopub {}", ac);
                            } else {
                                logger.info("POST: new nanopub {}", ac);

                                // Check if this nanopub's types are covered by this registry
                                if (!CoverageFilter.isCovered(np)) {
                                    throw new RuntimeException("Nanopub types not covered by this registry: " + np.getUri());
                                }

                                // TODO Run checks here whether we want to register this nanopub (considering quotas etc.)

                                // Verify signature once, pass through to avoid redundant verification:
                                String pubkey = RegistryDB.getPubkey(np);
                                if (pubkey == null)
                                    throw new RuntimeException("Nanopublication not supported: " + np.getUri());
                                // Load to nanopub store:
                                boolean success = RegistryDB.loadNanopubVerified(s, np, pubkey, null);
                                if (!success)
                                    throw new RuntimeException("Nanopublication not supported: " + np.getUri());
                                // Load to lists, if applicable:
                                NanopubLoader.simpleLoad(s, np, pubkey);
                            }
                        }
                    }
                    return null;
                }).onComplete(ar -> {
                    if (ar.succeeded()) {
                        c.response().setStatusCode(201).end();
                    } else {
                        c.response().setStatusCode(400)
                                .setStatusMessage("Error processing nanopub: " + ar.cause().getMessage()).end();
                    }
                });
            });
        };
        router.route(HttpMethod.POST, "/").handler(postHandler);
        router.route(HttpMethod.POST, "/np/").handler(postHandler);

        // INIT
        vertx.executeBlocking(() -> {
            logger.info("Starting DB initialization...");
            CoverageFilter.init();
            RegistryDB.init();

            new Thread(Task::runTasks).start();

            return null;
        }).onComplete(res -> logger.info("DB initialization finished"));

        // Periodic metrics update
        vertx.setPeriodic(1000, id -> collector.updateMetrics());

        // SHUTDOWN
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("Gracefully shutting down...");
                RegistryDB.getClient().close();
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                logger.info("Graceful shutdown completed");
            } catch (Exception ex) {
                logger.error("Graceful shutdown failed", ex);
            }
        }));

    }

}
