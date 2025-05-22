package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.isSet;
import static com.knowledgepixels.registry.RegistryDB.has;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.micrometer.PrometheusScrapingHandler;
import io.vertx.micrometer.backends.BackendRegistries;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.PublishNanopub;

import com.mongodb.client.ClientSession;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import net.trustyuri.TrustyUriUtils;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		server.requestHandler(router);
		server.listen(9292);
		router.route(HttpMethod.GET, "/agent*").handler(c -> {
			// /agent/... | /agents | /agentAccounts
			ListPage.show(c);
		});
		router.route(HttpMethod.GET, "/nanopubs*").handler(c -> {
			ListPage.show(c);
		});
		router.route(HttpMethod.GET, "/list*").handler(c -> {
			ListPage.show(c);
		});
		router.route(HttpMethod.GET, "/np/").handler(c -> {
			c.response().putHeader("Location", "/").setStatusCode(307).end();
		});
		router.route(HttpMethod.GET, "/np/*").handler(c -> {
			NanopubPage.show(c);
		});
		router.route(HttpMethod.GET, "/debug/*").handler(c -> {
			DebugPage.show(c);
		});
		router.route(HttpMethod.GET, "/style.css").handler(c -> {
			ResourcePage.show(c, "style.css", "text/css");
		});

		// Metrics
		final var metricsRegistry = (PrometheusMeterRegistry) BackendRegistries.getDefaultNow();
		final var collector = new MetricsCollector(metricsRegistry);
		router.route("/metrics").handler(PrometheusScrapingHandler.create(metricsRegistry));

		router.route(HttpMethod.GET, "/*").handler(c -> {
			MainPage.show(c);
		});
		router.route(HttpMethod.HEAD, "/*").handler(c -> {
			MainPage.show(c);
		});

		Handler<RoutingContext> postHandler = c -> {
			c.request().bodyHandler(bh -> {
				try {
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
							if (has(s, "nanopubs", ac)) {
								System.err.println("POST: known nanopub " + ac);
							} else {
								System.err.println("POST: new nanopub " + ac);

								// TODO Run checks here whether we want to register this nanopub (considering quotas etc.)

								// Load to nanopub store:
								RegistryDB.loadNanopub(s, np);
								// Load to lists, if applicable:
								NanopubLoader.simpleLoad(s, np);

								if (!isSet(s, "serverInfo", "testInstance")) {
									// Here we publish it also to the first-generation services, so they know about it too:
									// TODO Remove this at some point
									try {
										new PublishNanopub().publishNanopub(np, "https://np.knowledgepixels.com/");
									} catch (IOException ex) {
										ex.printStackTrace();
									}
								}
							}
						}
					}
					c.response().setStatusCode(201);
				} catch (Exception ex) {
					c.response().setStatusCode(400).setStatusMessage("Error processing nanopub: " + ex.getMessage());
				} finally {
					c.response().end();
				}
			});
		};
		router.route(HttpMethod.POST, "/").handler(postHandler);
		router.route(HttpMethod.POST, "/np/").handler(postHandler);

		// INIT
		vertx.executeBlocking(() -> {

			RegistryDB.init();

			new Thread(() -> {
				Task.runTasks();
			}).start();

			return null;
		}).onComplete(res -> {
			System.err.println("DB initialization finished");
		});

		// Periodic metrics update
		vertx.setPeriodic(1000, id -> collector.updateMetrics());

		// SHUTDOWN
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.err.println("Gracefully shutting down...");
				RegistryDB.getClient().close();
				vertx.close() .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
				System.err.println("Graceful shutdown completed");
			} catch (Exception ex) {
				System.err.println("Graceful shutdown failed");
				ex.printStackTrace();
			}
		}));

	}

	public String getResourceAsString(String file) {
		InputStream is = getClass().getClassLoader().getResourceAsStream("com/knowledgepixels/query/" + file);
		try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
			String fileContent = s.hasNext() ? s.next() : "";
			return fileContent;
		}
	}

}
