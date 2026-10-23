package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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
import org.nanopub.extra.server.NanopubServerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.knowledgepixels.registry.RegistryDB.has;

public class MainVerticle extends AbstractVerticle {

    private final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Promise<Void> startPromise) {
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        server.requestHandler(router);

        server.listen(9292).onComplete(ar -> {
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
        metricsHttpServer.requestHandler(metricsRouter).listen(9293).onComplete(ar -> {
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

                AtomicReference<String> npUri = new AtomicReference<>();
                vertx.<Void>executeBlocking(() -> {
                    Nanopub np;
                    try {
                        np = new NanopubImpl(bh.toString(), Rio.getParserFormatForMIMEType(contentType).orElse(RDFFormat.TRIG));
                    } catch (MalformedNanopubException ex) {
                        logger.warn("Malformed nanopub received on {}: {}", c.request().path(), ex.getMessage(), ex);
                        throw new NanopubRejectedException(NanopubRejectedException.BAD_REQUEST,
                                "body is not a well-formed nanopublication: " + ex.getMessage());
                    } catch (Exception ex) {
                        logger.warn("Failed to parse nanopub on {}: {}", c.request().path(), ex.getMessage(), ex);
                        throw new NanopubRejectedException(NanopubRejectedException.BAD_REQUEST,
                                "body could not be read as "
                                        + (contentType == null ? "RDF" : contentType) + ": " + ex.getMessage());
                    }
                    npUri.set(np.getUri().stringValue());
                    try (ClientSession s = RegistryDB.getClient().startSession()) {
                        String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
                        if (has(s, Collection.NANOPUBS.toString(), ac)) {
                            logger.info("POST: known nanopub {}", ac);
                        } else {
                            logger.info("POST: new nanopub {}", ac);

                            // Check if this nanopub's types are covered by this registry
                            if (!CoverageFilter.isCovered(np)) {
                                throw new NanopubRejectedException(NanopubRejectedException.FORBIDDEN,
                                        "none of its types are covered by this registry");
                            }

                            // Protected nanopubs are only accepted on local/private instances:
                            if (!Utils.isLocalInstance() && NanopubServerUtils.isProtectedNanopub(np)) {
                                throw new NanopubRejectedException(NanopubRejectedException.FORBIDDEN,
                                        "it is protected and cannot be published to this registry");
                            }

                            // The artifact code has to verify against the content. A valid
                            // signature does not cover this, as it is computed with the artifact
                            // code normalized out (see RegistryDB.hasValidTrustyArtifactCode).
                            if (!RegistryDB.hasValidTrustyArtifactCode(np)) {
                                throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                                        "artifact code does not verify against the content");
                            }

                            // Verify signature once, pass through to avoid redundant verification:
                            String pubkey = RegistryDB.getPubkey(np);
                            if (pubkey == null) {
                                throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                                        "no valid public key / signature found");
                            }

                            // Check agent/quota restrictions
                            PublishPolicy.check(s, np, Utils.getHash(pubkey));

                            // Noted before storing, since the trigger only fires on nanopubs
                            // this registry does not have yet:
                            TrustUpdateTrigger.noteIncoming(s, np);

                            // Load to nanopub store. This reports the reason it refused,
                            // which the publisher cannot get out of our log:
                            RegistryDB.loadNanopubOrReject(s, np, pubkey, null);
                            // Load to lists, if applicable:
                            NanopubLoader.simpleLoad(s, np, pubkey);
                        }
                    }
                    return null;
                }).onComplete(ar -> {
                    if (ar.succeeded()) {
                        c.response().setStatusCode(201).end();
                        logger.info("POST {} processed successfully (201)", c.request().path());
                    } else {
                        Throwable cause = ar.cause();
                        logger.warn("POST {} processing failed: {}", c.request().path(), cause == null ? "unknown error" : cause.getMessage(), cause);
                        respondRejected(c, cause, npUri.get());
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

    /**
     * Answers a refused POST with the reason in the <em>body</em>, and a status saying what kind
     * of refusal it is (see {@link NanopubRejectedException}).
     * <p>
     * The reason phrase is kept as a summary for HTTP/1.1 clients that show it, but it cannot be
     * the only copy: HTTP/2 has no reason phrases, so anything in front of this registry speaking
     * h2 to the client would drop it and leave the publisher with a bare status.
     */
    static void respondRejected(RoutingContext c, Throwable cause, String nanopubUri) {
        int statusCode = 400;
        String reason = "unknown error";
        long retryAfterSeconds = 0;
        if (cause instanceof NanopubRejectedException rejected) {
            statusCode = rejected.getStatusCode();
            reason = rejected.getMessage();
            retryAfterSeconds = rejected.getRetryAfterSeconds();
        } else if (cause != null && cause.getMessage() != null) {
            reason = cause.getMessage();
        }
        String detail = nanopubUri == null
                ? "Nanopub rejected: " + reason
                : "Nanopub " + nanopubUri + " rejected: " + reason;
        c.response().setStatusCode(statusCode)
                .setStatusMessage(toReasonPhrase(detail))
                .putHeader("Content-Type", "text/plain; charset=utf-8");
        if (retryAfterSeconds > 0) {
            // Without this a 429 leaves the client to guess, and nanopub-java backs off on its
            // own schedule rather than on the one the window actually runs to.
            c.response().putHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        c.response().end(detail + "\n");
    }

    /**
     * Makes a message safe and sensible to put on the status line: no control characters (a stray
     * CR or LF would split the response, and a parser error quotes the offending input), and short
     * enough not to strain a client's header limits. The body carries the message in full.
     */
    static String toReasonPhrase(String message) {
        String phrase = message.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return phrase.length() > 200 ? phrase.substring(0, 197) + "..." : phrase;
    }

}
