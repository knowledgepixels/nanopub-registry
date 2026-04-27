package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;

import java.io.IOException;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.Utils.*;
import static com.mongodb.client.model.Projections.exclude;
import static com.mongodb.client.model.Sorts.descending;

/**
 * Serves hash-keyed trust state snapshots.
 *
 * <ul>
 *   <li><code>/trust-state</code> — list of retained snapshots (HTML or JSON)</li>
 *   <li><code>/trust-state/&lt;hash&gt;</code> — single snapshot (HTML or JSON)</li>
 * </ul>
 *
 * <p>Snapshot content is immutable once a hash is known. Consumers use
 * <code>/trust-state/&lt;hash&gt;.json</code> after detecting a hash change (via the
 * {@code Nanopub-Registry-Trust-State-Hash} response header) to load the corresponding
 * snapshot side-by-side with the previous one.
 */
public class TrustStatePage extends Page {

    private static final String SUPPORTED_TYPES = TYPE_JSON + "," + TYPE_HTML;

    public static void show(RoutingContext context) {
        TrustStatePage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new TrustStatePage(s, context);
            page.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            context.response().end();
        }
    }

    private TrustStatePage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();
        String req = getRequestString();
        String ext = getExtension();

        String format;
        if ("json".equals(ext)) {
            format = TYPE_JSON;
        } else if (ext == null || "html".equals(ext)) {
            format = Utils.getMimeType(c, SUPPORTED_TYPES);
        } else {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        if (getPresentationFormat() != null) {
            setRespContentType(getPresentationFormat());
        } else {
            setRespContentType(format);
        }

        if ("/trust-state".equals(req) || "/trust-state/".equals(req)) {
            showList(format);
        } else if (req.matches("/trust-state/[A-Za-z0-9_\\-]+")) {
            String hash = req.substring("/trust-state/".length());
            showDetail(hash, format);
        } else {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
        }
    }

    private void showList(String format) {
        // Metadata only — the accounts array is heavy and not needed in the index.
        try (MongoCursor<Document> it = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(mongoSession)
                .projection(exclude("accounts"))
                .sort(descending("trustStateCounter"))
                .cursor()) {
            if (TYPE_JSON.equals(format)) {
                println("[");
                while (it.hasNext()) {
                    Document d = it.next();
                    Document out = new Document()
                            .append("trustStateHash", d.getString("_id"))
                            .append("trustStateCounter", d.get("trustStateCounter"))
                            .append("createdAt", d.get("createdAt"));
                    print(out.toJson());
                    println(it.hasNext() ? "," : "");
                }
                println("]");
            } else {
                printHtmlHeader("Trust State History - Nanopub Registry");
                println("<h1>Trust State History</h1>");
                println("<p><a href=\"/\">&lt; Home</a></p>");
                println("<h3>Formats</h3>");
                println("<p>");
                println("<a href=\"trust-state.json\">.json</a> |");
                println("<a href=\"trust-state.json.txt\">.json.txt</a>");
                println("</p>");
                println("<h3>Past Trust States</h3>");
                println("<ol>");
                while (it.hasNext()) {
                    Document d = it.next();
                    String hash = d.getString("_id");
                    println("<li>");
                    println("<a href=\"/trust-state/" + hash + "\"><code>" + getLabel(hash) + "</code></a>");
                    print(", counter " + d.get("trustStateCounter"));
                    Object createdAt = d.get("createdAt");
                    if (createdAt != null) {
                        print(", " + createdAt.toString().replaceFirst("\\.[^.]*$", ""));
                    }
                    println("</li>");
                }
                println("</ol>");
                printHtmlFooter();
            }
        }
    }

    private void showDetail(String hash, String format) {
        Document snapshot = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(mongoSession, new Document("_id", hash)).first();
        if (snapshot == null) {
            getContext().response().setStatusCode(404).setStatusMessage("Trust state snapshot not found");
            return;
        }

        if (TYPE_JSON.equals(format)) {
            // Content is immutable by construction: the URL encodes a hash that never rewrites.
            getContext().response().putHeader("Cache-Control", "public, immutable, max-age=31536000");
            Document output = new Document()
                    .append("trustStateHash", snapshot.getString("_id"))
                    .append("trustStateCounter", snapshot.get("trustStateCounter"))
                    .append("createdAt", snapshot.get("createdAt"))
                    .append("accounts", snapshot.get("accounts"));
            print(output.toJson());
            return;
        }

        // HTML detail view
        printHtmlHeader("Trust State " + getLabel(hash) + " - Nanopub Registry");
        println("<h1>Trust State <code>" + getLabel(hash) + "</code></h1>");
        println("<p><a href=\"/trust-state\">&lt; Trust State History</a></p>");
        println("<h3>Formats</h3>");
        println("<p>");
        println("<a href=\"" + hash + ".json\">.json</a> |");
        println("<a href=\"" + hash + ".json.txt\">.json.txt</a>");
        println("</p>");
        println("<h3>Hash</h3>");
        println("<p><code>" + hash + "</code></p>");
        println("<h3>Metadata</h3>");
        println("<ul>");
        println("<li><em>trustStateCounter:</em> " + snapshot.get("trustStateCounter") + "</li>");
        Object createdAt = snapshot.get("createdAt");
        if (createdAt != null) {
            println("<li><em>createdAt:</em> " + createdAt.toString().replaceFirst("\\.[^.]*$", "") + "</li>");
        }
        Object accountsObj = snapshot.get("accounts");
        int accountCount = (accountsObj instanceof List) ? ((List<?>) accountsObj).size() : 0;
        println("<li><em>accountCount:</em> " + accountCount + "</li>");
        println("</ul>");
        println("<h3>Accounts</h3>");
        println("<ol>");
        if (accountsObj instanceof List) {
            for (Object entry : (List<?>) accountsObj) {
                if (!(entry instanceof Document)) continue;
                Document a = (Document) entry;
                String pubkey = a.getString("pubkey");
                String agent = a.getString("agent");
                String name = a.getString("name");
                println("<li>");
                println("<a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a>");
                if (agent != null && !agent.isBlank()) {
                    print(" by <a href=\"/agent?id=" + Utils.urlEncode(agent) + "\">" + Utils.getAgentLabel(agent) + "</a>");
                    if (name != null && !name.isBlank()) {
                        print(" (" + name + ")");
                    }
                }
                print(", status: " + a.get("status"));
                print(", depth: " + a.get("depth"));
                if (a.get("pathCount") != null) print(", pathCount: " + a.get("pathCount"));
                if (a.get("ratio") != null) print(", ratio: " + a.get("ratio"));
                if (a.get("quota") != null) print(", quota: " + a.get("quota"));
                println("</li>");
            }
        }
        println("</ol>");
        printHtmlFooter();
    }

    private static String getLabel(String s) {
        if (s == null) return null;
        if (s.length() < 10) return s;
        return s.substring(0, 10);
    }

}
