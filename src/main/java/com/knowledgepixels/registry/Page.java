package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
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

        // TODO See whether we can cache these better on our side. Not sure how efficient the MongoDB caching is for these
        //      kinds of DB queries...
        context.response().putHeader("Nanopub-Registry-Status", (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        context.response().putHeader("Nanopub-Registry-Setup-Id", (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId"));
        context.response().putHeader("Nanopub-Registry-Trust-State-Counter", (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateCounter"));
        context.response().putHeader("Nanopub-Registry-Last-Trust-State-Update", (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate"));
        context.response().putHeader("Nanopub-Registry-Trust-State-Hash", (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash"));
        context.response().putHeader("Nanopub-Registry-Load-Counter", (String) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "counter"));
        context.response().putHeader("Nanopub-Registry-Test-Instance", String.valueOf(isSet(mongoSession, Collection.SERVER_INFO.toString(), "testInstance")));

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
