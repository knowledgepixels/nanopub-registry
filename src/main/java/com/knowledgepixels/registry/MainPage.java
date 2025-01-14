package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.mongodb.client.model.Indexes.ascending;
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
			println(RegistryInfo.getLocal().asJson());
		} else {
			String status = getValue("server-info", "status").toString();
			printHtmlHeader("Nanopub Registry - alpha");
			println("<h1>Nanopub Registry - alpha</h1>");
			println("<h3>Server</h3>");
			println("<ul>");
			println("<li><em>setup-id:</em> " + getValue("server-info", "setup-id") + "</li>");
			println("<li><em>status:</em> " + status + "</li>");
			println("<li><em>trust-state-counter:</em> " + getValue("server-info", "trust-state-counter") + "</li>");
			println("<li><em>nanopub-counter:</em> " + getMaxValue("nanopubs", "counter") + "</li>");
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

			println("<h3>Agents</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("agents").countDocuments(mongoSession) + "</p>");
				println("<ul>");
				MongoCursor<Document> agents = collection("agents").find(mongoSession).sort(descending("total-ratio")).limit(10).cursor();
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
				println("<p><a href=\"/agents\">&gt; Full list</a></pi>");
			}

			println("<h3>Accounts</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("accounts").countDocuments(mongoSession) + "</p>");
				println("<ul>");
				MongoCursor<Document> accountList = collection("accounts").find(mongoSession).sort(ascending("pubkey")).limit(10).cursor();
				String previous = null;
				while (accountList.hasNext()) {
					Document d = accountList.next();
					String pubkey = d.getString("pubkey");
					if (!pubkey.equals(previous) && !pubkey.equals("$")) {
						println("<li><a href=\"/list/" + pubkey + "\"><code>" + pubkey + "</code></a> (" + d.get("status") + ")</li>");
					}
					previous = pubkey;
				}
				println("</ul>");
				println("<p><a href=\"/list\">&gt; Full list</a></pi>");
			}

			println("<h3>Nanopubs</h3>");
			println("<p>Count: " + getMaxValue("nanopubs", "counter") + "</p>");
			println("<ul>");
			MongoCursor<Document> nanopubs = collection("nanopubs").find(mongoSession).sort(descending("counter")).limit(10).cursor();
			while (nanopubs.hasNext()) {
				Document d = nanopubs.next();
				println("<li><a href=\"/np/" + d.getString("_id") + "\"><code>" + d.getString("_id") + "</code></a></li>");
			}
			println("</ul>");
			println("<p><a href=\"/nanopubs\">&gt; Latest 1000</a></pi>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
