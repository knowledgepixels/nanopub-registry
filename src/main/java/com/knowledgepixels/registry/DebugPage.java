package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.mongodb.client.model.Indexes.ascending;

public class DebugPage extends Page {

    private static final Logger logger = LoggerFactory.getLogger(DebugPage.class);

    public static void show(RoutingContext context) {
        DebugPage page;
        logger.info("Received debug request: {}", context.request().path());
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new DebugPage(s, context);
            page.show();
        } catch (IOException ex) {
            logger.warn("Failed to show debug page for request {}: {} ({})", context.request().path(), ex.getMessage(), ex.getClass().getSimpleName(), ex);
        } finally {
            logger.debug("Ending response for debug request: {}", context.request().path());
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private DebugPage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();
        logger.debug("Preparing debug response for {}", getFullRequest());

        if (getRequestString().matches("/debug/trustPaths")) {
            String counterString = c.request().getParam("trustStateCounter");
            if (counterString == null) {
                logger.info("Serving current trustPaths for {}", getFullRequest());
                print(getTrustPathsTxt(mongoSession));
            } else {
                Long counter = Long.parseLong(counterString);
                logger.info("Serving trustPaths for trustStateCounter={} request={}", counter, getFullRequest());
                print(RegistryDB.getOne(mongoSession, "debug_trustPaths", new Document("trustStateCounter", counter)).getString("trustStateTxt"));
            }
            setRespContentType("text/plain");
        } else if (getRequestString().matches("/debug/endorsements")) {
            MongoCursor<Document> tp = collection("endorsements").find(mongoSession).cursor();
            int count = 0;
            while (tp.hasNext()) {
                Document d = tp.next();
                println(d.get("agent") + ">" + d.get("pubkey") + " " + d.get("endorsedNanopub") + " " + d.get("source") + " (" + d.get("status") + ")");
                count++;
            }
            setRespContentType("text/plain");
            logger.info("Listed {} endorsements for {}", count, getFullRequest());
        } else if (getRequestString().matches("/debug/accounts")) {
            MongoCursor<Document> tp = collection("accounts").find(mongoSession).cursor();
            int count = 0;
            while (tp.hasNext()) {
                Document d = tp.next();
                println(d.getString("agent") + ">" + d.get("pubkey") + " " + d.get("depth") + " (" + d.get("status") + ")");
                count++;
            }
            logger.info("Listed {} accounts for {}", count, getFullRequest());
        } else if (getRequestString().matches("/debug/tasks")) {
            setRespContentType("text/plain");
            try {
                String currentTask = Task.getCurrentTaskName();
                if (currentTask != null) {
                    long elapsed = System.currentTimeMillis() - Task.getCurrentTaskStartTime();
                    println("Currently running: " + currentTask + " (for " + elapsed + "ms)");
                } else {
                    println("Currently running: (none)");
                }
                println("");
                MongoCursor<Document> tasks = collection(Collection.TASKS.toString()).find(mongoSession)
                        .sort(ascending("not-before")).cursor();
                int count = 0;
                while (tasks.hasNext()) {
                    println(tasks.next().toJson());
                    count++;
                }
                println("Total queued tasks: " + count);
                logger.info("Listed {} queued tasks for {}", count, getFullRequest());
            } catch (Exception ex) {
                logger.warn("Failed while listing tasks for {}: {} ({})", getFullRequest(), ex.getMessage(), ex.getClass().getSimpleName(), ex);
                println("Error: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        } else if (getRequestString().matches("/debug/peerState")) {
            setRespContentType("text/plain");
            try {
                long count = collection(Collection.PEER_STATE.toString()).countDocuments(mongoSession);
                println("peerState documents: " + count);
                MongoCursor<Document> ps = collection(Collection.PEER_STATE.toString()).find(mongoSession).cursor();
                int listed = 0;
                while (ps.hasNext()) {
                    println(ps.next().toJson());
                    listed++;
                }
                logger.info("Listed {} peerState documents for {}", listed, getFullRequest());
            } catch (Exception ex) {
                logger.warn("Failed while listing peerState for {}: {} ({})", getFullRequest(), ex.getMessage(), ex.getClass().getSimpleName(), ex);
                println("Error: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        } else {
            logger.warn("Invalid debug request path: {}", getFullRequest());
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
        }
    }

    public static String getTrustPathsTxt(ClientSession mongoSession) {
        String s = "";
        MongoCursor<Document> tp = collection("trustPaths").find(mongoSession).sort(ascending("_id")).cursor();
        while (tp.hasNext()) {
            Document d = tp.next();
            String path = d.getString("_id");
            path = path.replace(" ", " > ");
            if (d.getString("type").equals("extended")) {
                path = path.replaceFirst(" > ([^ ]+)$", " ~ $1");
            }
            s += path + "\n";
        }
        return s;
    }

}
