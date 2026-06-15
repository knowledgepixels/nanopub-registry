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

public class MainVerticle extends AbstractVerticle {

    private final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Promise<Void> startPromise) {
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        server.requestHandler(router);

        server.listen(9292, ar -> {
            if (ar.succeeded()) {
                logger.info("HTTP server started and listening on port 9292");
            } else {
                logger.error("Failed to start HTTP server on port 9292", ar.cause());
            }
        });

        router.route(HttpMethod.GET, "/agent*").handler(c -> {
            // /agent/... | /agents | /agentAccounts
            logger.debug("Routing GET /agent* -> ListPage for {}", c.request().path());
            ListPage.show(c);
        });
        router.route(HttpMethod.GET, "/nanopubs*").handler(c -> {
            logger.debug("Routing GET /nanopubs* -> ListPage for {}", c.request().path());
            ListPage.show(c);
        });
        router.route(HttpMethod.GET, "/list*").handler(c -> {
            logger.debug("Routing GET /list* -> ListPage for {}", c.request().path());
            ListPage.show(c);
        });
        router.route(HttpMethod.GET, "/pubkeys*").handler(c -> {
            logger.debug("Routing GET /pubkeys* -> ListPage for {}", c.request().path());
            ListPage.show(c);
        });
        router.route(HttpMethod.GET, "/np/").handler(c -> {
            logger.debug("Redirecting /np/ to / for {}", c.request().remoteAddress());
            c.response().putHeader("Location", "/").setStatusCode(307).end();
        });
        router.route(HttpMethod.GET, "/np/*").handler(c -> {
            logger.debug("Routing GET /np/* -> NanopubPage for {}", c.request().path());
            NanopubPage.show(c);
        });
        router.route(HttpMethod.GET, "/get/").handler(c -> {
            logger.debug("Redirecting /get/ to / for {}", c.request().remoteAddress());
            c.response().putHeader("Location", "/").setStatusCode(307).end();
        });
        router.route(HttpMethod.GET, "/get/*").handler(c -> {
            logger.debug("Routing GET /get/* -> NanopubPage (forwardHtml=true) for {}", c.request().path());
            NanopubPage.show(c, true);
        });
        router.route(HttpMethod.GET, "/debug/*").handler(c -> {
            logger.debug("Routing GET /debug/* -> DebugPage for {}", c.request().path());
            DebugPage.show(c);
        });
        router.route(HttpMethod.GET, "/trust-state*").handler(c -> {
            logger.debug("Routing GET /trust-state* -> TrustStatePage for {}", c.request().path());
            TrustStatePage.show(c);
        });
        router.route(HttpMethod.GET, "/style.css").handler(c -> {
            logger.debug("Routing GET /style.css -> ResourcePage for {}", c.request().path());
            ResourcePage.show(c, "style.css", "text/css");
        });

        // Metrics
        final var metricsHttpServer = vertx.createHttpServer();
        final var metricsRouter = Router.router(vertx);
        metricsHttpServer.requestHandler(metricsRouter).listen(9293, ar -> {
            if (ar.succeeded()) {
                logger.info("Metrics HTTP server started and listening on port 9293");
            } else {
                logger.error("Failed to start metrics HTTP server on port 9293", ar.cause());
            }
        });

        final var metricsRegistry = (PrometheusMeterRegistry) BackendRegistries.getDefaultNow();
        if (metricsRegistry == null) {
            logger.warn("PrometheusMeterRegistry not available (metrics will not be exposed)");
        } else {
            logger.debug("PrometheusMeterRegistry retrieved for metrics exposure");
        }
        final var collector = new MetricsCollector(metricsRegistry);
        metricsRouter.route("/metrics").handler(PrometheusScrapingHandler.create(metricsRegistry));

        router.route(HttpMethod.GET, "/*").handler(c -> {
            logger.debug("Routing GET /* -> MainPage for {}", c.request().path());
            MainPage.show(c);
        });
        router.route(HttpMethod.HEAD, "/*").handler(c -> {
            logger.debug("Routing HEAD /* -> MainPage for {}", c.request().path());
            MainPage.show(c);
        });

        Handler<RoutingContext> postHandler = c -> {
            logger.info("Received POST {}", c.request().path());
            c.request().bodyHandler(bh -> {
                String contentType = c.request().getHeader("Content-Type");
                int bodySize = bh.length();
                logger.debug("POST content-type={} body-size={} for {}", contentType, bodySize, c.request().remoteAddress());

                vertx.<Void>executeBlocking(() -> {
                    Nanopub np = null;
                    try {
                        np = new NanopubImpl(bh.toString(), Rio.getParserFormatForMIMEType(contentType).orElse(RDFFormat.TRIG));
                    } catch (MalformedNanopubException ex) {
                        logger.warn("Malformed nanopub received on {}: {}", c.request().path(), ex.getMessage(), ex);
                    } catch (Exception ex) {
                        logger.warn("Failed to parse nanopub on {}: {}", c.request().path(), ex.getMessage(), ex);
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

                                // Verify signature once, pass through to avoid redundant verification:
                                String pubkey = RegistryDB.getPubkey(np);
                                if (pubkey == null) {
                                    throw new RuntimeException("Nanopublication not supported: " + np.getUri());
                                }

                                // Check agent/quota restrictions
                                String pubkeyHash = Utils.getHash(pubkey);
                                if (!AgentFilter.isAllowed(s, pubkeyHash)) {
                                    throw new RuntimeException("Pubkey not authorized on this registry: " + pubkeyHash);
                                }
                                if (AgentFilter.isOverQuota(s, pubkeyHash)) {
                                    throw new RuntimeException("Quota exceeded for pubkey: " + pubkeyHash);
                                }

                                // Load to nanopub store:
                                boolean success = RegistryDB.loadNanopubVerified(s, np, pubkey, null);
                                if (!success) {
                                    throw new RuntimeException("Nanopublication not supported: " + np.getUri());
                                }
                                // Load to lists, if applicable:
                                NanopubLoader.simpleLoad(s, np, pubkey);
                            }
                        }
                    } else {
                        logger.debug("No nanopub parsed from POST body for {}", c.request().path());
                    }
                    return null;
                }).onComplete(ar -> {
                    if (ar.succeeded()) {
                        c.response().setStatusCode(201).end();
                        logger.info("POST {} processed successfully (201)", c.request().path());
                    } else {
                        Throwable cause = ar.cause();
                        logger.warn("POST {} processing failed: {}", c.request().path(), cause == null ? "unknown error" : cause.getMessage(), cause);
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
            AgentFilter.init();
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

        logger.info("Route registration completed");

    }

}
