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
			String status = getValue("serverInfo", "status").toString();
			printHtmlHeader("Nanopub Registry - alpha");
			println("<h1>Nanopub Registry - alpha</h1>");
			println("<h3>Server</h3>");
			println("<ul>");
			println("<li><em>setupId:</em> " + getValue("serverInfo", "setupId") + "</li>");
			println("<li><em>status:</em> " + status + "</li>");
			println("<li><em>trustStateCounter:</em> " + getValue("serverInfo", "trustStateCounter") + "</li>");
			println("<li><em>loadCounter:</em> " + getMaxValue("nanopubs", "counter") + "</li>");
			println("<li><em>coverageTypes:</em> " + getValue("serverInfo", "coverageTypes") + "</li>");
			println("<li><em>coverageAgents:</em> " + getValue("serverInfo", "coverageAgents") + "</li>");
			println("</ul>");
			println("<h3>Setting</h3>");
			println("<ul>");
			String oSetting = getValue("setting", "original").toString();
			println("<li><em>original:</em> <a href=\"/np/" + oSetting + "\"><code>" + oSetting.substring(0, 10) + "</code></a></li>");
			String cSetting = getValue("setting", "current").toString();
			println("<li><em>current:</em> <a href=\"/np/" + cSetting + "\"><code>" + cSetting.substring(0, 10) + "</code></a></li>");
			println("</ul>");

			println("<h3>Agents</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("agents").countDocuments(mongoSession) + "</p>");
				println("<ul>");
				MongoCursor<Document> agents = collection("agents").find(mongoSession).sort(descending("totalRatio")).limit(10).cursor();
				while (agents.hasNext()) {
					Document d = agents.next();
					if (d.get("agent").equals("$")) continue;
					String a = d.getString("agent");
					int accountCount = d.getInteger("accountCount");
					println("<li><a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>, " +
							accountCount + " account" + (accountCount == 1 ? "" : "s") + ", " +
							"ratio " + df8.format(d.get("totalRatio")) + ", " +
							"avg. path count " + df1.format(d.get("avgPathCount")) +
							"</li>");
				}
				println("</ul>");
				println("<p><a href=\"/agents\">&gt; All</a></pi>");
			}

			println("<h3>Accounts</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("accounts").countDocuments(mongoSession) + "</p>");
				println("<ul>");
				MongoCursor<Document> accountList = collection("accounts").find(mongoSession).sort(ascending("pubkey")).limit(11).cursor();
				while (accountList.hasNext()) {
					Document d = accountList.next();
					String pubkey = d.getString("pubkey");
					if (!pubkey.equals("$")) {
						println("<li>");
						println("<a href=\"/list/" + pubkey + "\"><code>" + pubkey.substring(0, 10) + "</code></a>");
						String a = d.getString("agent");
						println(" by <a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>");
						println(" (" + d.get("status") + ") ");
						println("</li>");
					}
				}
				println("</ul>");
				println("<p><a href=\"/list\">&gt; All</a></pi>");
			}

			println("<h3>Nanopubs</h3>");
			println("<p>Count: " + getMaxValue("nanopubs", "counter") + "</p>");
			println("<ul>");
			MongoCursor<Document> nanopubs = collection("nanopubs").find(mongoSession).sort(descending("counter")).limit(10).cursor();
			while (nanopubs.hasNext()) {
				Document d = nanopubs.next();
				println("<li><a href=\"/np/" + d.getString("_id") + "\"><code>" + d.getString("_id").substring(0, 10) + "</code></a></li>");
			}
			println("</ul>");
			println("<p><a href=\"/latestNanopubs\">&gt; Latest</a></pi>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
