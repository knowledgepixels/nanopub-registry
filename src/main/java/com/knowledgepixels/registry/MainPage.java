package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.mongodb.client.model.Indexes.descending;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.DecimalFormat;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;

public class MainPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		MainPage obj = new MainPage(req, httpResp);
		obj.show();
	}

	private MainPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	static final DecimalFormat df8 = new DecimalFormat("0.00000000");
	static final DecimalFormat df1 = new DecimalFormat("0.0");

	protected void show() throws IOException {
		String format;
		String ext = getReq().getExtension();
		if ("json".equals(ext)) {
			format = "application/json";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/json,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
//		String url = ServerConf.getInfo().getPublicUrl();
		if ("application/json".equals(format)) {
			// TODO
			//println(ServerConf.getInfo().asJson());
		} else {
			printHtmlHeader("Nanopub Registry - alpha");
			println("<h1>Nanopub Registry - alpha</h1>");
			println("<h3>Server Info</h3>");
			println("<ul>");
			println("<li><em>setup-id:</em> " + getValue("server-info", "setup-id") + "</li>");
			println("<li><em>status:</em> " + getValue("server-info", "status") + "</li>");
			println("<li><em>status details:</em> " + getValue("server-info", "status-details") + "</li>");
			println("<li><em>state-counter:</em> " + getValue("server-info", "state-counter") + "</li>");
			println("<li><em>coverage-types:</em> " + getValue("server-info", "coverage-types") + "</li>");
			println("<li><em>coverage-agents:</em> " + getValue("server-info", "coverage-agents") + "</li>");
			println("</ul>");
			println("<h3>Setting</h3>");
			println("<ul>");
			String oSetting = getValue("setting", "original").toString();
			println("<li><em>original:</em> <a href=\"/np/" + oSetting + "\"><code>" + oSetting + "</code></a></li>");
			String cSetting = getValue("setting", "current").toString();
			println("<li><em>current:</em> <a href=\"/np/" + cSetting + "\"><code>" + cSetting + "</code></a></li>");
			println("</ul>");
			println("<h3>Agent accounts</h3>");
			println("<p>Count: " + collection("agent-accounts").countDocuments() + "</p>");
			println("<p><a href=\"/list\">&gt; Full list</a></pi>");
			println("<h3>Agents</h3>");
			println("<p>Count: " + collection("agents").countDocuments() + "</p>");
			println("<p>Top agents:</p>");
			println("<ul>");
			MongoCursor<Document> agents = collection("agents").find().sort(descending("total-ratio")).limit(20).cursor();
			while (agents.hasNext()) {
				Document d = agents.next();
				if (d.get("agent").equals("$")) continue;
				String a = d.getString("agent");
				int accountCount = d.getInteger("account-count");
				println("<li><a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + a + "</a>, " +
						accountCount + " account" + (accountCount == 1 ? "" : "s") + ", " +
						"ratio " + df8.format(d.get("total-ratio")) + ", " +
						"avg. path count " + df1.format(d.get("avg-path-count")) +
						"</li>");
			}
			println("</ul>");
			println("<p><a href=\"/agent\">&gt; Full list</a></pi>");
			println("<h3>Nanopubs:</h3>");
			println("<p>Count: " + getMaxValue("nanopubs", "counter") + "</p>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
