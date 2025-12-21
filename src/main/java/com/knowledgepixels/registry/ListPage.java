package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.nanopub.jelly.NanopubStream;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.unhash;
import static com.knowledgepixels.registry.Utils.*;
import static com.mongodb.client.model.Aggregates.*;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.descending;
import static com.mongodb.client.model.Projections.exclude;
import static com.mongodb.client.model.Projections.include;

public class ListPage extends Page {

    private static final Gson gson = new Gson();

    public static void show(RoutingContext context) {
        ListPage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new ListPage(s, context);
            page.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private ListPage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext context = getContext();
        String format;
        String ext = getExtension();
        final String req = getRequestString();
        if ("json".equals(ext)) {
            format = TYPE_JSON;
        } else if ("jelly".equals(ext)) {
            format = TYPE_JELLY;
        } else if (ext == null || "html".equals(ext)) {
            format = Utils.getMimeType(context, SUPPORTED_TYPES_LIST);
        } else {
            context.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        if (getPresentationFormat() != null) {
            setRespContentType(getPresentationFormat());
        } else {
            setRespContentType(format);
        }

        if (req.matches("/list/[0-9a-f]{64}/([0-9a-f]{64}|\\$)")) {
            String pubkey = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$1");
            String type = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$2");

            if (TYPE_JELLY.equals(format)) {
                // Return all nanopubs in the list as a single Jelly stream
                List<Bson> pipeline = List.of(match(new Document("pubkey", pubkey).append("type", type)), sort(ascending("position")), // TODO: is this needed?
                        lookup("nanopubs", "np", "_id", "nanopub"), project(new Document("jelly", "$nanopub.jelly")), unwind("$jelly"));
                // TODO: try with resource should be used for all DB access, really, like here
                try (var result = collection("listEntries").aggregate(mongoSession, pipeline).cursor()) {
                    NanopubStream npStream = NanopubStream.fromMongoCursor(result);
                    BufferOutputStream outputStream = new BufferOutputStream();
                    npStream.writeToByteStream(outputStream);
                    context.response().write(outputStream.getBuffer());
                }
            } else {
                MongoCursor<Document> c = collection("listEntries").find(mongoSession, new Document("pubkey", pubkey).append("type", type)).projection(exclude("_id")).sort(ascending("position")).cursor();

                if (TYPE_JSON.equals(format)) {
                    println("[");
                    while (c.hasNext()) {
                        Document d = c.next();
                        // Transforming long to int, so the JSON output looks nice:
                        // TODO Make this scale beyond the int range
                        d.replace("position", d.getLong("position").intValue());
                        print(d.toJson());
                        println(c.hasNext() ? "," : "");
                    }
                    println("]");
                } else {
                    printHtmlHeader("List for pubkey " + getLabel(pubkey) + " / type " + getLabel(type) + " - Nanopub Registry");
                    println("<h1>List</h1>");
                    println("<p><a href=\"/list/" + pubkey + "\">&lt; Pubkey</a></p>");
                    println("<h3>Formats</h3>");
                    println("<p>");
                    println("<a href=\"/list/" + pubkey + "/" + type + ".json\">.json</a> |");
                    println("<a href=\"/list/" + pubkey + "/" + type + ".json.txt\">.json.txt</a>");
                    println("</p>");
                    println("<h3>Pubkey Hash</h3>");
                    println("<p><code>" + pubkey + "</code></p>");
                    println("<h3>Type Hash</h3>");
                    println("<p><code>" + type + "</code></p>");
                    println("<h3>Entries</h3>");
                    println("<ol>");
                    while (c.hasNext()) {
                        Document d = c.next();
                        println("<li><a href=\"/np/" + d.getString("np") + "\"><code>" + getLabel(d.getString("np")) + "</code></a></li>");
                    }
                    println("</ol>");
                    printHtmlFooter();
                }
            }
        } else if (req.matches("/list/[0-9a-f]{64}")) {
            String pubkey = req.replaceFirst("/list/([0-9a-f]{64})", "$1");
            MongoCursor<Document> c = collection("lists").find(mongoSession, new Document("pubkey", pubkey)).projection(exclude("_id")).cursor();
            if (TYPE_JSON.equals(format)) {
                println("[");
                while (c.hasNext()) {
                    print(c.next().toJson());
                    println(c.hasNext() ? "," : "");
                }
                println("]");
            } else {
                printHtmlHeader("Accounts for Pubkey " + getLabel(pubkey) + " - Nanopub Registry");
                println("<h1>Accounts for Pubkey " + getLabel(pubkey) + "</h1>");
                println("<p><a href=\"/list\">&lt; Account List</a></p>");
                println("<h3>Formats</h3>");
                println("<p>");
                println("<a href=\"/list/" + pubkey + ".json\">.json</a> |");
                println("<a href=\"/list/" + pubkey + ".json.txt\">.json.txt</a>");
                println("</p>");
                println("<h3>Pubkey Hash</h3>");
                println("<p><code>" + pubkey + "</code></p>");
                println("<h3>Entry Lists</h3>");
                println("<ol>");
                while (c.hasNext()) {
                    Document d = c.next();
                    String type = d.getString("type");
                    println("<li>");
                    println("<a href=\"/list/" + pubkey + "/" + type + "\"><code>" + getLabel(type) + "</code></a> ");
                    if (type.equals("$")) {
                        println("(all types)");
                    } else {
                        println("(type " + unhash(type) + ")");
                    }
                    println("</li>");
                }
                println("</ol>");
                printHtmlFooter();
            }
        } else if (req.equals("/list")) {
            try (var c = collection("accounts").find(mongoSession).sort(ascending("pubkey")).projection(exclude("_id")).cursor()) {
                if (TYPE_JSON.equals(format)) {
                    println("[");
                    while (c.hasNext()) {
                        print(c.next().toJson());
                        println(c.hasNext() ? "," : "");
                    }
                    println("]");
                } else {
                    printHtmlHeader("Account List - Nanopub Registry");
                    println("<h1>Account List</h1>");
                    println("<p><a href=\"/\">&lt; Home</a></p>");
                    println("<h3>Formats</h3>");
                    println("<p>");
                    println("<a href=\"list.json\">.json</a> |");
                    println("<a href=\"list.json.txt\">.json.txt</a>");
                    println("</p>");
                    println("<h3>Accounts</h3>");
                    println("<ol>");
                    while (c.hasNext()) {
                        Document d = c.next();
                        String pubkey = d.getString("pubkey");
                        if (!pubkey.equals("$")) {
                            println("<li>");
                            println("<a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a>");
                            String a = d.getString("agent");
                            print(" by <a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>");
                            print(", status: " + d.get("status"));
                            print(", depth: " + d.get("depth"));
                            if (d.get("pathCount") != null) {
                                print(", pathCount: " + d.get("pathCount"));
                            }
                            if (d.get("ratio") != null) {
                                print(", ratio: " + df8.format(d.get("ratio")));
                            }
                            if (d.get("quota") != null) {
                                print(", quota: " + d.get("quota"));
                            }
                            println("");
                            println("</li>");
                        }
                    }
                    println("</ol>");
                    printHtmlFooter();
                }
            }
        } else if (req.equals("/agent") && context.request().getParam("id") != null) {
            String agentId = context.request().getParam("id");
            if (TYPE_JSON.equals(format)) {
                print(AgentInfo.get(mongoSession, agentId).asJson());
            } else {
                Document agentDoc = RegistryDB.getOne(mongoSession, "agents", new Document("agent", agentId));
                printHtmlHeader("Agent " + Utils.getAgentLabel(agentId) + " - Nanopub Registry");
                println("<h1>Agent " + Utils.getAgentLabel(agentId) + "</h1>");
                println("<p><a href=\"/agents\">&lt; Agent List</a></p>");
                println("<h3>Formats</h3>");
                println("<p>");
                println("<a href=\"agent.json?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json</a> |");
                println("<a href=\"agent.json.txt?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json.txt</a>");
                println("</p>");
                println("<h3>ID</h3>");
                println("<p><a href=\"" + agentId + "\"><code>" + agentId + "</code></a></p>");
                println("<h3>Properties</h3>");
                println("<ul>");
                println("<li>Average path count: " + agentDoc.get("avgPathCount") + "</li>");
                println("<li>Total ratio: " + agentDoc.get("totalRatio") + "</li>");
                println("</ul>");
                println("<h3>Accounts</h3>");
                println("<p>Count: " + agentDoc.get("accountCount") + "</p>");
                println("<p><a href=\"agentAccounts?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">&gt; agentAccounts</a></p>");
                printHtmlFooter();
            }
        } else if (req.equals("/agentAccounts") && context.request().getParam("id") != null) {
            String agentId = context.request().getParam("id");
            MongoCursor<Document> c = collection("accounts").find(mongoSession, new Document("agent", agentId)).projection(exclude("_id")).cursor();
            if (TYPE_JSON.equals(format)) {
                println("[");
                while (c.hasNext()) {
                    print(c.next().toJson());
                    println(c.hasNext() ? "," : "");
                }
                println("]");
            } else {
                printHtmlHeader("Accounts of Agent " + Utils.getAgentLabel(agentId) + " - Nanopub Registry");
                println("<h1>Accounts of Agent " + Utils.getAgentLabel(agentId) + "</h1>");
                println("<p><a href=\"/agent?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">&lt; Agent</a></p>");
                println("<h3>Formats</h3>");
                println("<p>");
                println("<a href=\"agentAccounts.json?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json</a> |");
                println("<a href=\"agentAccounts.json.txt?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json.txt</a>");
                println("</p>");
                println("<h3>Account List</h3>");
                println("<ul>");
                while (c.hasNext()) {
                    Document d = c.next();
                    String pubkey = d.getString("pubkey");
                    //				Object iCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", INTRO_TYPE_HASH), "position");
                    //				Object eCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", ENDORSE_TYPE), "position");
                    //				Object fCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", "$"), "position");
                    println("<li><a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a> (" + d.get("status") + "), " + "quota " + d.get("quota") + ", " + "ratio " + df8.format(d.get("ratio")) + ", " + "path count " + d.get("pathCount") + "</li>");
                }
                println("</ul>");
                printHtmlFooter();
            }
        } else if (req.equals("/agents")) {
            MongoCursor<Document> c = collection("agents").find(mongoSession).sort(descending("totalRatio")).projection(exclude("_id")).cursor();
            if (TYPE_JSON.equals(format)) {
                println("[");
                while (c.hasNext()) {
                    print(c.next().toJson());
                    println(c.hasNext() ? "," : "");
                }
                println("]");
            } else {
                printHtmlHeader("Agent List - Nanopub Registry");
                println("<h1>Agent List</h1>");
                println("<p><a href=\"/\">&lt; Home</a></p>");
                println("<h3>Formats</h3>");
                println("<p>");
                println("<a href=\"agents.json\">.json</a> |");
                println("<a href=\"agents.json.txt\">.json.txt</a>");
                println("</p>");
                println("<h3>Agents</h3>");
                println("<ol>");
                while (c.hasNext()) {
                    Document d = c.next();
                    if (d.get("agent").equals("$")) continue;
                    String a = d.getString("agent");
                    int accountCount = d.getInteger("accountCount");
                    println("<li><a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>, " + accountCount + " account" + (accountCount == 1 ? "" : "s") + ", " + "ratio " + df8.format(d.get("totalRatio")) + ", " + "avg. path count " + df1.format(d.get("avgPathCount")) + "</li>");
                }
                println("</ol>");
                printHtmlFooter();
            }
        } else if (req.equals("/nanopubs")) {
            if (TYPE_JELLY.equals(format)) {
                // Return all nanopubs from after counter X (-1 by default)
                long afterCounter;
                try {
                    afterCounter = Long.parseLong(getParam("afterCounter", "-1"));
                } catch (NumberFormatException ex) {
                    context.response().setStatusCode(400).setStatusMessage("Invalid afterCounter parameter.");
                    return;
                }
                // TODO: something is aborting the Mongo transaction here after a while,
                //  find out what exactly.
                var pipeline = collection("nanopubs").find(mongoSession).filter(gt("counter", afterCounter)).sort(ascending("counter"))
                        // Only include the needed fields to save bandwidth to the DB
                        .projection(include("jelly", "counter"));

                try (var result = pipeline.cursor()) {
                    NanopubStream npStream = NanopubStream.fromMongoCursorWithCounter(result);
                    BufferOutputStream outputStream = new BufferOutputStream();
                    npStream.writeToByteStream(outputStream);
                    context.response().write(outputStream.getBuffer());
                }
            } else {
                // Return latest nanopubs

                MongoCursor<Document> c = collection("nanopubs").find(mongoSession).sort(descending("counter")).limit(1000).cursor();
                if (TYPE_JSON.equals(format)) {
                    println("[");
                    while (c.hasNext()) {
                        print(gson.toJson(c.next().getString("_id")));
                        println(c.hasNext() ? "," : "");
                    }
                    println("]");
                } else {
                    printHtmlHeader("Latest nanopubs - Nanopub Registry");
                    println("<h1>Nanopubs</h1>");
                    println("<p><a href=\"/\">&lt; Home</a></p>");
                    println("<h3>Latest Nanopubs JSON (max. 1000)</h3>");
                    println("<p>");
                    println("<a href=\"nanopubs.json\">.json</a> |");
                    println("<a href=\"nanopubs.json.txt\">.json.txt</a>");
                    println("</p>");
                    println("<h3>All Nanopubs (Jelly)</h3>");
                    println("<p><a href=\"nanopubs.jelly\">.jelly</a></p>");
                    println("<h3>Latest Nanopubs List (max. 1000)</h3>");
                    println("<ol>");
                    while (c.hasNext()) {
                        String npId = c.next().getString("_id");
                        println("<li><a href=\"/np/" + npId + "\"><code>" + getLabel(npId) + "</code></a></li>");
                    }
                    println("</ol>");
                    printHtmlFooter();
                }
            }
        } else if (req.equals("/pubkeys")) {
            try (var c = collection("lists").distinct(mongoSession, "pubkey", String.class).cursor()) {
                if (TYPE_JSON.equals(format)) {
                    println("[");
                    while (c.hasNext()) {
                        print(gson.toJson(c.next()));
                        println(c.hasNext() ? "," : "");
                    }
                    println("]");
                } else {
                    printHtmlHeader("Pubkey List - Nanopub Registry");
                    println("<h1>Pubkey List</h1>");
                    println("<p><a href=\"/\">&lt; Home</a></p>");
                    println("<h3>Formats</h3>");
                    println("<p>");
                    println("<a href=\"pubkeys.json\">.json</a> |");
                    println("<a href=\"pubkeys.json.txt\">.json.txt</a>");
                    println("</p>");
                    println("<h3>Pubkeys</h3>");
                    println("<ol>");
                    while (c.hasNext()) {
                        String pubkey = c.next();
                        if (!pubkey.equals("$")) {
                            println("<li>");
                            println("<a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a>");
                            println("</li>");
                        }
                    }
                    println("</ol>");
                    printHtmlFooter();
                }
            }
        } else {
            context.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
        }
    }

    private static String getLabel(Object obj) {
        if (obj == null) return null;
        if (obj.toString().length() < 10) return obj.toString();
        return obj.toString().substring(0, 10);
    }

}
