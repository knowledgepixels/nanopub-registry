package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;

import java.io.IOException;
import java.text.DecimalFormat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringEscapeUtils;

public abstract class Page {

	protected static final DecimalFormat df8 = new DecimalFormat("0.00000000");
	protected static final DecimalFormat df1 = new DecimalFormat("0.0");

	private ServerRequest req;
	private HttpServletResponse httpResp;

	public Page(ServerRequest req, HttpServletResponse httpResp) {
		this.req = req;
		this.httpResp = httpResp;
		httpResp.setCharacterEncoding("UTF-8");

		// TODO See whether we can cache these better on our side. Not sure how efficient the MongoDB caching is for these
		//      kinds of DB queries...
		httpResp.setHeader("Nanopub-Registry-Status", getValue("serverInfo", "status").toString());
		httpResp.setHeader("Nanopub-Registry-Setup-Id", getValue("serverInfo", "setupId").toString());
		httpResp.setHeader("Nanopub-Registry-Trust-State-Counter", getValue("serverInfo", "trustStateCounter").toString());
		httpResp.setHeader("Nanopub-Registry-Last-Trust-State-Update", (String) getValue("serverInfo", "lastTrustStateUpdate"));
		httpResp.setHeader("Nanopub-Registry-Trust-State-Hash", (String) getValue("serverInfo", "trustStateHash"));
		httpResp.setHeader("Nanopub-Registry-Load-Counter", getMaxValue("nanopubs", "counter").toString());
	}

	public ServerRequest getReq() {
		return req;
	}

	public HttpServletRequest getHttpReq() {
		return req.getHttpRequest();
	}

	public HttpServletResponse getResp() {
		return httpResp;
	}

	public void println(String s) throws IOException {
		httpResp.getOutputStream().println(s);
	}

	public void print(String s) throws IOException {
		httpResp.getOutputStream().print(s);
	}

	protected abstract void show() throws IOException;

	public void printHtmlHeader(String title) throws IOException {
		println("<!DOCTYPE HTML>");
		println("<html><head>");
		println("<title>" + title + "</title>");
		println("<meta charset=\"utf-8\"/>");
		println("<script type=\"text/javascript\" src=\"/scripts/nanopub.js\"></script>");
		println("<link rel=\"stylesheet\" href=\"/style/plain.css\" type=\"text/css\" media=\"screen\" title=\"Stylesheet\" />");
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
		httpResp.addHeader("Link", "<" + url + ">; rel=\"canonical\"");
	}

}
