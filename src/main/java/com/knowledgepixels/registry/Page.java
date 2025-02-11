package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;

import java.io.IOException;
import java.text.DecimalFormat;

import org.apache.commons.lang.StringEscapeUtils;

import io.vertx.ext.web.RoutingContext;

public abstract class Page {

	protected static final DecimalFormat df8 = new DecimalFormat("0.00000000");
	protected static final DecimalFormat df1 = new DecimalFormat("0.0");

	private RoutingContext context;

	private String presentationFormat;
	private String extension;
	private String requestString;

	public Page(RoutingContext context) {
		this.context = context;
		context.response().setChunked(true);

		// TODO See whether we can cache these better on our side. Not sure how efficient the MongoDB caching is for these
		//      kinds of DB queries...
		context.response().putHeader("Nanopub-Registry-Status", getValue("serverInfo", "status").toString());
		context.response().putHeader("Nanopub-Registry-Setup-Id", getValue("serverInfo", "setupId").toString());
		context.response().putHeader("Nanopub-Registry-Trust-State-Counter", getValue("serverInfo", "trustStateCounter").toString());
		context.response().putHeader("Nanopub-Registry-Last-Trust-State-Update", (String) getValue("serverInfo", "lastTrustStateUpdate"));
		context.response().putHeader("Nanopub-Registry-Trust-State-Hash", (String) getValue("serverInfo", "trustStateHash"));
		context.response().putHeader("Nanopub-Registry-Load-Counter", getMaxValue("nanopubs", "counter").toString());

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
		context.response().write(s);
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

	public void printAltLinks(String artifactCode) throws IOException {
		print("<a href=\"" + artifactCode + "\">get</a> <span class=\"small\">(");
		print("<a href=\"" + artifactCode + ".trig\" type=\"application/x-trig\">trig</a>, ");
		print("<a href=\"" + artifactCode + ".nq\" type=\"text/x-nquads\">nq</a>, ");
		print("<a href=\"" + artifactCode + ".xml\" type=\"application/trix\">xml</a>, ");
		print("<a href=\"" + artifactCode + ".jsonld\" type=\"application/ld+json\">jsonld</a>, ");
		print("<a href=\"" + artifactCode + ".trig.txt\" type=\"text/plain\">trig.txt</a>, ");
		print("<a href=\"" + artifactCode + ".nq.txt\" type=\"text/plain\">nq.txt</a>, ");
		print("<a href=\"" + artifactCode + ".xml.txt\" type=\"text/plain\">xml.txt</a>, ");
		print("<a href=\"" + artifactCode + ".jsonld.txt\" type=\"text/plain\">jsonld.txt</a>");
		print(")</span>");
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
