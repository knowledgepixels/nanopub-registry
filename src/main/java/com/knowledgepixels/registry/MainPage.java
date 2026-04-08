package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.*;

public class MainPage extends Page {

    public static void show(RoutingContext context) {
        MainPage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new MainPage(s, context);
            page.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private MainPage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();
        String format;
        String ext = getExtension();
        if ("json".equals(ext)) {
            format = "application/json";
        } else if (ext == null || "html".equals(ext)) {
            String suppFormats = "application/json,text/html";
            format = Utils.getMimeType(c, suppFormats);
        } else {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        if (getPresentationFormat() != null) {
            setRespContentType(getPresentationFormat());
        } else {
            setRespContentType(format);
        }

        if ("application/json".equals(format)) {
            println(RegistryInfo.getLocal(mongoSession).asJson());
        } else {
            String status = serverInfo.get("status") != null ? serverInfo.get("status").toString() : "launching";
            printHtmlHeader("Nanopub Registry");
            println("<h1>Nanopub Registry</h1>");
            if (serverInfo.get("testInstance") != null && (Boolean) serverInfo.get("testInstance")) {
                println("<p style=\"color: red\">This is a test instance.</p>");
            }
            println("<h3>Formats</h3>");
            println("<p>");
            println("<a href=\".json\">.json</a> |");
            println("<a href=\".json.txt\">.json.txt</a>");
            println("</p>");
            println("<h3>Server</h3>");
            println("<ul>");
            println("<li><em>setupId:</em> " + serverInfo.get("setupId") + "</li>");
            println("<li><em>coverageTypes:</em> " + (serverInfo.get("coverageTypes") != null ? serverInfo.get("coverageTypes") : "all") + "</li>");
            println("<li><em>coverageAgents:</em> " + (serverInfo.get("coverageAgents") != null ? serverInfo.get("coverageAgents") : "viaSetting") + "</li>");
            println("<li><em>optionalLoadEnabled:</em> " + !"false".equals(System.getenv("REGISTRY_ENABLE_OPTIONAL_LOAD")) + "</li>");
            println("<li><em>trustCalculationEnabled:</em> " + !"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION")) + "</li>");
            println("<li><em>status:</em> " + status + "</li>");
            println("<li><em>loadCounter:</em> " + RegistryDB.getCommittedCounter(mongoSession) + "</li>");
            println("<li><em>nanopubCount:</em> " + collection(Collection.NANOPUBS.toString()).estimatedDocumentCount() + "</li>");
            println("<li><em>trustStateCounter:</em> " + serverInfo.get("trustStateCounter") + "</li>");
            Object lastTimeUpdate = serverInfo.get("lastTrustStateUpdate");
            if (lastTimeUpdate != null) {
                println("<li><em>lastTrustStateUpdate:</em> " + lastTimeUpdate.toString().replaceFirst("\\.[^.]*$", "") + "</li>");
            } else {
                println("<li><em>lastTrustStateUpdate:</em> null</li>");
            }
            Object trustStateHash = serverInfo.get("trustStateHash");
            if (trustStateHash != null) trustStateHash = trustStateHash.toString().substring(0, 10);
            println("<li><em>trustStateHash:</em> " + trustStateHash + "</li>");
            String oSetting = getValue(mongoSession, Collection.SETTING.toString(), "original").toString();
            println("<li><em>originalSetting:</em> <a href=\"/np/" + oSetting + "\"><code>" + oSetting.substring(0, 10) + "</code></a></li>");
            String cSetting = getValue(mongoSession, Collection.SETTING.toString(), "current").toString();
            println("<li><em>currentSetting:</em> <a href=\"/np/" + cSetting + "\"><code>" + cSetting.substring(0, 10) + "</code></a></li>");
            println("</ul>");

            if (!"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
                println("<h3>Agents</h3>");
                if (status.equals("launching") || status.equals("coreLoading")) {
                    println("<p><em>(loading...)</em></p>");
                } else {
                    println("<p>Count: " + collection(Collection.AGENTS.toString()).countDocuments(mongoSession) + "</p>");
                    println("<p><a href=\"/agents\">&gt; agents</a></pi>");
                }
            }

            println("<h3>Accounts</h3>");
            if (status.equals("launching") || status.equals("coreLoading")) {
                println("<p><em>(loading...)</em></p>");
            } else {
                println("<p>Count: " + collection(Collection.ACCOUNTS.toString()).countDocuments(mongoSession) + "</p>");
                println("<p><a href=\"/list\">&gt; accounts</a></pi>");
            }

            println("<h3>Nanopubs</h3>");
            println("<p>Count: " + collection(Collection.NANOPUBS.toString()).estimatedDocumentCount() + "</p>");
            println("<p><a href=\"/nanopubs\">&gt; nanopubs</a></pi>");
            printHtmlFooter();
        }
    }

}
