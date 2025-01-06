package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.MainPage.df1;
import static com.knowledgepixels.registry.MainPage.df8;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.mongodb.client.model.Aggregates.lookup;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Aggregates.unwind;
import static com.mongodb.client.model.Indexes.ascending;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.knowledgepixels.registry.jelly.NanopubStream;
import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;

public class ListPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		ListPage obj = new ListPage(req, httpResp);
		obj.show();
	}

	private ListPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	protected void show() throws IOException {
		String format;
		String ext = getReq().getExtension();
		final String req = getReq().getFullRequest();
		if ("json".equals(ext)) {
			format = "application/json";
		} else if ("jelly".equals(ext)) {
			format = "application/x-jelly-rdf";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/x-jelly-rdf,application/json,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + req);
			return;
		}
		if (req.matches("/list/[0-9a-f]{64}/([0-9a-f]{64}|\\$)")) {
			String pubkey = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$1");
			String type = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$2");
	//		String url = ServerConf.getInfo().getPublicUrl();

			if ("application/json".equals(format)) {
				// TODO
				//println(ServerConf.getInfo().asJson());
			} else if ("application/x-jelly-rdf".equals(format)) {
				// Return all nanopubs in the list as a single Jelly stream
				List<Bson> pipeline = List.of(
						lookup("nanopubs", "np", "_id", "nanopub"),
						project(new Document("jelly", "$nanopub.jelly")),
						unwind("$jelly")
				);
				var result = collection("list-entries").aggregate(pipeline);
				NanopubStream npStream = NanopubStream.fromMongoCursor(result.cursor());
				npStream.writeToByteStream(getResp().getOutputStream());
			} else {
				MongoCursor<Document> entries = collection("list-entries").find(
						new Document("pubkey", pubkey)
								.append("type", type)
				).sort(ascending("position")).cursor();
				
				printHtmlHeader("List for pubkey " + pubkey.substring(0, 10) + " / type " + Utils.getShortTypeLabel(type)  + " - Nanopub Registry");
				println("<h1>List</h1>");
				println("<h3>Pubkey Hash</h3>");
				println("<p><code>" + pubkey + "</code></p>");
				println("<h3>Type Hash</h3>");
				println("<p><code>" + type + "</code></p>");
				println("<h3>Entries</h3>");
				println("<ol>");
				while (entries.hasNext()) {
					Document d = entries.next();
					println("<li><a href=\"/np/" + d.getString("np") + "\"><code>" + d.getString("np") + "</code></a></li>");
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.matches("/list/[0-9a-f]{64}")) {
			String pubkey = getReq().getFullRequest().replaceFirst("/list/([0-9a-f]{64})", "$1");
//			String url = ServerConf.getInfo().getPublicUrl();
			if ("application/json".equals(format)) {
				// TODO
				//println(ServerConf.getInfo().asJson());
			} else {
				printHtmlHeader("Account list for pubkey " + pubkey.substring(0, 10) + " - Nanopub Registry");
				println("<h1>Account List</h1>");
				println("<h3>Pubkey Hash</h3>");
				println("<p><code>" + pubkey + "</code></p>");
				println("<h3>Entry Lists</h3>");
				println("<ol>");
				MongoCursor<Document> entryLists = collection("lists").find(
						new Document("pubkey", pubkey)
					).cursor();
				while (entryLists.hasNext()) {
					Document d = entryLists.next();
					String type = d.getString("type");
					println("<li><a href=\"/list/" + pubkey + "/" + type + "\"><code>" + type + "</code></a></li>");
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.equals("/list")) {
//			String url = ServerConf.getInfo().getPublicUrl();
			if ("application/json".equals(format)) {
				// TODO
				//println(ServerConf.getInfo().asJson());
			} else {
				printHtmlHeader("List of accounts - Nanopub Registry");
				println("<h1>List of Accounts</h1>");
				println("<h3>Accounts</h3>");
				println("<ol>");
				MongoCursor<Document> accountList = collection("agent-accounts").find().sort(ascending("pubkey")).cursor();
				String previous = null;
				while (accountList.hasNext()) {
					Document d = accountList.next();
					String pubkey = d.getString("pubkey");
					if (!pubkey.equals(previous) && !pubkey.equals("$")) {
						println("<li><a href=\"/list/" + pubkey + "\"><code>" + pubkey + "</code></a> (" + d.get("status") + ")</li>");
					}
					previous = pubkey;
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.equals("/agent") && getReq().getHttpRequest().getParameter("id") != null) {
			String agentId = getReq().getHttpRequest().getParameter("id");
			Document agentDoc = RegistryDB.getOne("agents", new Document("agent", agentId));
			printHtmlHeader("Agent " + agentId + " - Nanopub Registry");
			println("<h1>Agent</h1>");
			println("<h3>ID</h3>");
			println("<p><a href=\"" + agentId + "\"><code>" + agentId + "</code></a></p>");
			println("<h3>Properties</h3>");
			println("<ul>");
			println("<li>Account count: " + agentDoc.get("account-count") + "</li>");
			println("<li>Average path count: " + agentDoc.get("avg-path-count") + "</li>");
			println("<li>Total ratio: " + agentDoc.get("total-ratio") + "</li>");
			println("</ul>");
			println("<h3>Accounts</h3>");
			println("<ul>");
			MongoCursor<Document> accountList = collection("agent-accounts").find(new Document("agent", agentId)).cursor();
			while (accountList.hasNext()) {
				Document d = accountList.next();
				String pubkey = d.getString("pubkey");
				println("<li><a href=\"/list/" + pubkey + "\"><code>" + pubkey + "</code></a> (" + d.get("status") + "), " +
						"nanopub count " + getMaxValue("list-entries", new Document("pubkey", pubkey), "position") + ", " +
						"quota " + d.get("quota") + ", " +
						"ratio " + df8.format(d.get("ratio")) + ", " +
						"path count " + d.get("path-count") +
						"</li>");
			}
			println("</ul>");
			printHtmlFooter();
		} else if (req.equals("/agent")) {
			printHtmlHeader("List of agents - Nanopub Registry");
			println("<h1>List of Agents</h1>");
			println("<h3>Agents</h3>");
			println("<ol>");
			MongoCursor<Document> agentList = collection("agents").find().sort(ascending("agent")).cursor();
			while (agentList.hasNext()) {
				Document d = agentList.next();
				if (d.get("agent").equals("$")) continue;
				String a = d.getString("agent");
				int accountCount = d.getInteger("account-count");
				println("<li><a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + a + "</a> (" + d.get("status") + "), " +
						accountCount + " account" + (accountCount == 1 ? "" : "s") + ", " +
						"ratio " + df8.format(d.get("total-ratio")) + ", " +
						"avg. path count " + df1.format(d.get("avg-path-count")) +
						"</li>");
			}
			println("</ol>");
			printHtmlFooter();
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
