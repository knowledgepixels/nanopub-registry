package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.isSet;

import java.io.IOException;
import java.text.DecimalFormat;

import org.apache.commons.lang.StringEscapeUtils;

import com.mongodb.client.ClientSession;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

public abstract class Page {

	protected static final DecimalFormat df8 = new DecimalFormat("0.00000000");
	protected static final DecimalFormat df1 = new DecimalFormat("0.0");

	private RoutingContext context;
	protected ClientSession mongoSession;

	private String presentationFormat;
	private String extension;
	private String requestString;

	public Page(ClientSession mongoSession, RoutingContext context) {
		this.mongoSession = mongoSession;
		this.context = context;
		context.response().setChunked(true);

		// TODO See whether we can cache these better on our side. Not sure how efficient the MongoDB caching is for these
		//      kinds of DB queries...
		context.response().putHeader("Nanopub-Registry-Status", getValue(mongoSession, "serverInfo", "status").toString());
		context.response().putHeader("Nanopub-Registry-Setup-Id", getValue(mongoSession, "serverInfo", "setupId").toString());
		context.response().putHeader("Nanopub-Registry-Trust-State-Counter", getValue(mongoSession, "serverInfo", "trustStateCounter").toString());
		context.response().putHeader("Nanopub-Registry-Last-Trust-State-Update", (String) getValue(mongoSession, "serverInfo", "lastTrustStateUpdate"));
		context.response().putHeader("Nanopub-Registry-Trust-State-Hash", (String) getValue(mongoSession, "serverInfo", "trustStateHash"));
		context.response().putHeader("Nanopub-Registry-Load-Counter", getMaxValue(mongoSession, "nanopubs", "counter").toString());
		context.response().putHeader("Nanopub-Registry-Test-Instance", String.valueOf(isSet(mongoSession, "serverInfo", "testInstance")));

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

	public RoutingContext getContext() {
		return context;
	}

	public void println(String s) throws IOException {
		print(s + "\n");
	}

	public void print(String s) throws IOException {
		if (context.request().method() == HttpMethod.HEAD) return;
		context.response().write(s);
	}

	public void setRespContentType(String contentType) {
		if (context.request().method() == HttpMethod.HEAD) return;
		context.response().putHeader("Content-Type", contentType);
	}

	protected abstract void show() throws IOException;

	public void printHtmlHeader(String title) throws IOException {
		println("<!DOCTYPE HTML>");
		println("<html><head>");
		println("<title>" + title + "</title>");
		println("<meta charset=\"utf-8\"/>");
		println("<script type=\"text/javascript\" src=\"/scripts/nanopub.js\"></script>");
		println("<link rel=\"stylesheet\" href=\"/style.css\" type=\"text/css\" media=\"screen\" title=\"Stylesheet\" />");
		println("</head><body>");
	}

	public void printHtmlFooter() throws IOException {
		println("</body></html>");
	}

	public String escapeHtml(String text) {
		return StringEscapeUtils.escapeHtml(text);
	}

	public void setCanonicalLink(String url) {
		context.response().putHeader("Link", "<" + url + ">; rel=\"canonical\"");
	}

	public String getPresentationFormat() {
		return presentationFormat;
	}

	public String getExtension() {
		return extension;
	}

	public String getRequestString() {
		return "/" + requestString;
	}

	public String getFullRequest() {
		return context.request().path();
	}

	/**
	 * Get a parameter from the request.
	 * @param name The name of the parameter.
	 * @param defaultValue The default value of the parameter.
	 * @return The value of the parameter.
	 */
	public String getParam(String name, String defaultValue) {
		String value = context.request().getParam(name);
		if (value == null) value = defaultValue;
		return value;
	}

	public boolean isEmpty() {
		return requestString.isEmpty();
	}

	public boolean hasArtifactCode() {
		return requestString.matches("RA[A-Za-z0-9\\-_]{43}");
	}

	public String getArtifactCode() {
		if (hasArtifactCode()) return requestString;
		return null;
	}

}
