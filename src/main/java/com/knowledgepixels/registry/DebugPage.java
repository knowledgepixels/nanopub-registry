package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.mongodb.client.model.Indexes.ascending;

public class DebugPage extends Page {

    public static void show(RoutingContext context) {
        DebugPage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new DebugPage(s, context);
            page.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private DebugPage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();

        if (getRequestString().matches("/debug/trustPaths")) {
            String counterString = c.request().getParam("trustStateCounter");
            if (counterString == null) {
                print(getTrustPathsTxt(mongoSession));
            } else {
                Long counter = Long.parseLong(counterString);
                print(RegistryDB.getOne(mongoSession, "debug_trustPaths", new Document("trustStateCounter", counter)).getString("trustStateTxt"));
            }
            setRespContentType("text/plain");
        } else if (getRequestString().matches("/debug/endorsements")) {
            MongoCursor<Document> tp = collection("endorsements").find(mongoSession).cursor();
            while (tp.hasNext()) {
                Document d = tp.next();
                println(d.get("agent") + ">" + d.get("pubkey") + " " + d.get("endorsedNanopub") + " " + d.get("source") + " (" + d.get("status") + ")");
            }
            setRespContentType("text/plain");
        } else if (getRequestString().matches("/debug/accounts")) {
            MongoCursor<Document> tp = collection("accounts").find(mongoSession).cursor();
            while (tp.hasNext()) {
                Document d = tp.next();
                println(d.getString("agent") + ">" + d.get("pubkey") + " " + d.get("depth") + " (" + d.get("status") + ")");
            }
        } else if (getRequestString().matches("/debug/tasks")) {
            setRespContentType("text/plain");
            try {
                MongoCursor<Document> tasks = collection(Collection.TASKS.toString()).find(mongoSession)
                        .sort(ascending("not-before")).cursor();
                int count = 0;
                while (tasks.hasNext()) {
                    println(tasks.next().toJson());
                    count++;
                }
                println("Total tasks: " + count);
            } catch (Exception ex) {
                println("Error: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        } else if (getRequestString().matches("/debug/peerState")) {
            setRespContentType("text/plain");
            try {
                long count = collection(Collection.PEER_STATE.toString()).countDocuments(mongoSession);
                println("peerState documents: " + count);
                MongoCursor<Document> ps = collection(Collection.PEER_STATE.toString()).find(mongoSession).cursor();
                while (ps.hasNext()) {
                    println(ps.next().toJson());
                }
            } catch (Exception ex) {
                println("Error: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        } else {
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
