package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.bson.Document;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang.StringEscapeUtils;

import java.io.IOException;
import java.text.DecimalFormat;

import static com.knowledgepixels.registry.RegistryDB.*;

public abstract class Page {

    protected static final DecimalFormat df8 = new DecimalFormat("0.00000000");
    protected static final DecimalFormat df1 = new DecimalFormat("0.0");

    private RoutingContext context;
    protected ClientSession mongoSession;
    protected Document serverInfo;

    private String presentationFormat;
    private String extension;
    private String requestString;

    /**
     * Constructor for the Page class.
     *
     * @param mongoSession The MongoDB client session.
     * @param context      The routing context.
     */
    public Page(ClientSession mongoSession, RoutingContext context) {
        this.mongoSession = mongoSession;
        this.context = context;
        context.response().setChunked(true);

        // Fetch all serverInfo key-value pairs in one query instead of separate getValue calls
        serverInfo = new Document();
        for (Document d : collection(Collection.SERVER_INFO.toString()).find(mongoSession)) {
            serverInfo.put(d.getString("_id"), d.get("value"));
        }
        context.response().putHeader("Nanopub-Registry-Status", serverInfo.get("status") + "");
        context.response().putHeader("Nanopub-Registry-Setup-Id", serverInfo.get("setupId") + "");
        context.response().putHeader("Nanopub-Registry-Trust-State-Counter", serverInfo.get("trustStateCounter") + "");
        context.response().putHeader("Nanopub-Registry-Last-Trust-State-Update", serverInfo.get("lastTrustStateUpdate") + "");
        context.response().putHeader("Nanopub-Registry-Trust-State-Hash", serverInfo.get("trustStateHash") + "");
        Object maxSeqNum = getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "seqNum");
        context.response().putHeader("Nanopub-Registry-SeqNum", maxSeqNum + "");
        context.response().putHeader("Nanopub-Registry-Nanopub-Count", collection(Collection.NANOPUBS.toString()).estimatedDocumentCount() + "");
        // TODO(transition): Remove after all peers upgraded
        context.response().putHeader("Nanopub-Registry-Load-Counter", maxSeqNum + "");
        context.response().putHeader("Nanopub-Registry-Test-Instance", String.valueOf(serverInfo.get("testInstance") != null && (Boolean) serverInfo.get("testInstance")));
        context.response().putHeader("Nanopub-Registry-Coverage-Types", serverInfo.get("coverageTypes") != null ? serverInfo.get("coverageTypes").toString() : "all");
        context.response().putHeader("Nanopub-Registry-Coverage-Agents", serverInfo.get("coverageAgents") != null ? serverInfo.get("coverageAgents").toString() : "viaSetting");

        String r = context.request().path().substring(1);
        if (r.endsWith(".txt")) {
            presentationFormat = "text/plain";
            r = r.replaceFirst("\\.txt$", "");
        } else if (r.endsWith(".html")) {
            presentationFormat = "text/html";
            r = r.replaceFirst("\\.html$", "");
//		} else if (r.endsWith(".gz")) {
//			presentationFormat = "application/x-gzip";
//			r = r.replaceFirst("\\.gz$", "");
        }
        if (r.matches(".*\\.[a-z]{1,10}")) {
            extension = r.replaceFirst("^.*\\.([a-z]{1,10})$", "$1");
            requestString = r.replaceFirst("^(.*)\\.[a-z]{1,10}$", "$1");
        } else {
            requestString = r;
        }
    }

    /**
     * Get the routing context.
     *
     * @return The routing context.
     */
    public RoutingContext getContext() {
        return context;
    }

    /**
     * Print a line to the response.
     *
     * @param s The string to print.
     */
    public void println(String s) {
        print(s + "\n");
    }

    /**
     * Print a string to the response.
     *
     * @param s The string to print.
     */
    public void print(String s) {
        if (context.request().method() == HttpMethod.HEAD) {
            return;
        }
        context.response().write(s);
    }

    /**
     * Set the response content type.
     *
     * @param contentType The content type.
     */
    public void setRespContentType(String contentType) {
        if (context.request().method() == HttpMethod.HEAD) {
            return;
        }
        context.response().putHeader("Content-Type", contentType);
    }

    /**
     * Show the page.
     *
     * @throws IOException
     */
    protected abstract void show() throws IOException;

    /**
     * Print the HTML header.
     *
     * @param title The title of the page.
     */
    public void printHtmlHeader(String title) {
        println("<!DOCTYPE HTML>");
        println("<html><head>");
        println("<title>" + title + "</title>");
        println("<meta charset=\"utf-8\"/>");
        println("<script type=\"text/javascript\" src=\"/scripts/nanopub.js\"></script>");
        println("<link rel=\"stylesheet\" href=\"/style.css\" type=\"text/css\" media=\"screen\" title=\"Stylesheet\" />");
        println("</head><body>");
    }

    /**
     * Print the HTML footer.
     */
    public void printHtmlFooter() {
        println("</body></html>");
    }

    /**
     * Escape HTML special characters in a string.
     *
     * @param text The text to escape.
     * @return The escaped text.
     */
    public String escapeHtml(String text) {
        return StringEscapeUtils.escapeHtml(text);
    }

    /**
     * Set the canonical link for this page.
     *
     * @param url The canonical URL.
     */
    public void setCanonicalLink(String url) {
        context.response().putHeader("Link", "<" + url + ">; rel=\"canonical\"");
    }

    /**
     * Get the presentation format requested, if any.
     *
     * @return The presentation format.
     */
    public String getPresentationFormat() {
        return presentationFormat;
    }

    /**
     * Get the extension requested, if any.
     *
     * @return The extension.
     */
    public String getExtension() {
        return extension;
    }

    /**
     * Get the request string with the leading slash and without extension.
     *
     * @return The request string.
     */
    public String getRequestString() {
        return "/" + requestString;
    }

    /**
     * Get the full request path.
     *
     * @return The full request path.
     */
    public String getFullRequest() {
        return context.request().path();
    }

    /**
     * Get a parameter from the request.
     *
     * @param name         The name of the parameter.
     * @param defaultValue The default value of the parameter.
     * @return The value of the parameter.
     */
    public String getParam(String name, String defaultValue) {
        String value = context.request().getParam(name);
        if (value == null) value = defaultValue;
        return value;
    }

    /**
     * Check whether the request is empty.
     *
     * @return True if the request is empty, false otherwise.
     */
    public boolean isEmpty() {
        return requestString.isEmpty();
    }

    /**
     * Check whether the request contains an artifact code.
     *
     * @return True if the request contains an artifact code, false otherwise.
     */
    public boolean hasArtifactCode() {
        return requestString.matches("RA[A-Za-z0-9\\-_]{43}");
    }

    /**
     * Get the artifact code from the request, if present.
     *
     * @return The artifact code, or null if not present.
     */
    public String getArtifactCode() {
        if (hasArtifactCode()) {
            return requestString;
        }
        return null;
    }

}
