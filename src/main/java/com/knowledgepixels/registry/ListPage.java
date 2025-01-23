package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.mongodb.client.model.Aggregates.lookup;
import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Aggregates.sort;
import static com.mongodb.client.model.Aggregates.unwind;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.descending;
import static com.mongodb.client.model.Projections.exclude;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.google.gson.Gson;
import com.knowledgepixels.registry.jelly.NanopubStream;
import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;

public class ListPage extends Page {

	private static Gson gson = new Gson();

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
		final String req = getReq().getRequestString();
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

		if (getReq().getPresentationFormat() != null) {
			getResp().setContentType(getReq().getPresentationFormat());
		} else {
			getResp().setContentType(format);
		}

		if (req.matches("/list/[0-9a-f]{64}/([0-9a-f]{64}|\\$)")) {
			String pubkey = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$1");
			String type = req.replaceFirst("/list/([0-9a-f]{64})/([0-9a-f]{64}|\\$)", "$2");

			if ("application/x-jelly-rdf".equals(format)) {
				// Return all nanopubs in the list as a single Jelly stream
				List<Bson> pipeline = List.of(
						match(new Document("pubkey", pubkey).append("type", type)),
						sort(ascending("position")), // TODO: is this needed?
						lookup("nanopubs", "np", "_id", "nanopub"),
						project(new Document("jelly", "$nanopub.jelly")),
						unwind("$jelly")
				);
				var result = collection("listEntries").aggregate(mongoSession, pipeline);
				NanopubStream npStream = NanopubStream.fromMongoCursor(result.cursor());
				npStream.writeToByteStream(getResp().getOutputStream());
			} else {
				MongoCursor<Document> c = collection("listEntries")
						.find(mongoSession, new Document("pubkey", pubkey).append("type", type))
						.projection(exclude("_id"))
						.sort(ascending("position"))
						.cursor();

				if ("application/json".equals(format)) {
					println("[");
					while (c.hasNext()) {
						Document d = c.next();
						// Transforming long to int, so the JSON output looks nice:
						// TODO Make this scale beyond the int range
						d.replace("position", d.getLong("position").intValue());
						print(d.toJson());
						println(c.hasNext() ? "," : "");
					}
					println("]");
				} else {
					printHtmlHeader("List for pubkey " + getLabel(pubkey) + " / type " + getLabel(type)  + " - Nanopub Registry");
					println("<h1>List</h1>");
					println("<p><a href=\"/list/" + pubkey + "\">&lt; Pubkey</a></p>");
					println("<h3>Formats</h3>");
					println("<p>");
					println("<a href=\"/list/" + pubkey + "/" + type + ".json\">.json</a> |");
					println("<a href=\"/list/" + pubkey + "/" + type + ".json.txt\">.json.txt</a>");
					println("</p>");
					println("<h3>Pubkey Hash</h3>");
					println("<p><code>" + pubkey + "</code></p>");
					println("<h3>Type Hash</h3>");
					println("<p><code>" + type + "</code></p>");
					println("<h3>Entries</h3>");
					println("<ol>");
					while (c.hasNext()) {
						Document d = c.next();
						println("<li><a href=\"/np/" + d.getString("np") + "\"><code>" + getLabel(d.getString("np")) + "</code></a></li>");
					}
					println("</ol>");
					printHtmlFooter();
				}
			}
		} else if (req.matches("/list/[0-9a-f]{64}")) {
			String pubkey = req.replaceFirst("/list/([0-9a-f]{64})", "$1");
			MongoCursor<Document> c = collection("lists").find(mongoSession, new Document("pubkey", pubkey)).projection(exclude("_id")).cursor();
			if ("application/json".equals(format)) {
				println("[");
				while (c.hasNext()) {
					print(c.next().toJson());
					println(c.hasNext() ? "," : "");
				}
				println("]");
			} else {
				printHtmlHeader("Accounts for Pubkey " + getLabel(pubkey) + " - Nanopub Registry");
				println("<h1>Accounts for Pubkey " + getLabel(pubkey) + "</h1>");
				println("<p><a href=\"/list\">&lt; Account List</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"/list/" + pubkey + ".json\">.json</a> |");
				println("<a href=\"/list/" + pubkey + ".json.txt\">.json.txt</a>");
				println("</p>");
				println("<h3>Pubkey Hash</h3>");
				println("<p><code>" + pubkey + "</code></p>");
				println("<h3>Entry Lists</h3>");
				println("<ol>");
				while (c.hasNext()) {
					Document d = c.next();
					String type = d.getString("type");
					println("<li><a href=\"/list/" + pubkey + "/" + type + "\"><code>" + getLabel(type) + "</code></a></li>");
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.equals("/list")) {
			MongoCursor<Document> c = collection("accounts").find(mongoSession).sort(ascending("pubkey")).projection(exclude("_id")).cursor();
			if ("application/json".equals(format)) {
				println("[");
				while (c.hasNext()) {
					print(c.next().toJson());
					println(c.hasNext() ? "," : "");
				}
				println("]");
			} else {
				printHtmlHeader("Account List - Nanopub Registry");
				println("<h1>Account List</h1>");
				println("<p><a href=\"/\">&lt; Home</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"list.json\">.json</a> |");
				println("<a href=\"list.json.txt\">.json.txt</a>");
				println("</p>");
				println("<h3>Accounts</h3>");
				println("<ol>");
				while (c.hasNext()) {
					Document d = c.next();
					String pubkey = d.getString("pubkey");
					if (!pubkey.equals("$")) {
						println("<li>");
						println("<a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a>");
						String a = d.getString("agent");
						println(" by <a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>,");
						println(" status: " + d.get("status") + ",");
						println(" depth: " + d.get("depth") + ",");
						println(" pathCount: " + d.get("pathCount") + ",");
						println(" ratio: " + df8.format(d.get("ratio")) + ",");
						println(" quota: " + d.get("quota"));
						println("</li>");
					}
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.equals("/agent") && getReq().getHttpRequest().getParameter("id") != null) {
			String agentId = getReq().getHttpRequest().getParameter("id");
			if ("application/json".equals(format)) {
				print(AgentInfo.get(agentId).asJson());
			} else {
				Document agentDoc = RegistryDB.getOne("agents", new Document("agent", agentId));
				printHtmlHeader("Agent " + Utils.getAgentLabel(agentId) + " - Nanopub Registry");
				println("<h1>Agent " + Utils.getAgentLabel(agentId) + "</h1>");
				println("<p><a href=\"/agents\">&lt; Agent List</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"agent.json?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json</a> |");
				println("<a href=\"agent.json.txt?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json.txt</a>");
				println("</p>");
				println("<h3>ID</h3>");
				println("<p><a href=\"" + agentId + "\"><code>" + agentId + "</code></a></p>");
				println("<h3>Properties</h3>");
				println("<ul>");
				println("<li>Average path count: " + agentDoc.get("avgPathCount") + "</li>");
				println("<li>Total ratio: " + agentDoc.get("totalRatio") + "</li>");
				println("</ul>");
				println("<h3>Accounts</h3>");
				println("<p>Count: " + agentDoc.get("accountCount") + "</p>");
				println("<p><a href=\"agentAccounts?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">&gt; agentAccounts</a></p>");
				printHtmlFooter();
			}
		} else if (req.equals("/agentAccounts") && getReq().getHttpRequest().getParameter("id") != null) {
			String agentId = getReq().getHttpRequest().getParameter("id");
			MongoCursor<Document> c = collection("accounts").find(mongoSession, new Document("agent", agentId)).projection(exclude("_id")).cursor();
			if ("application/json".equals(format)) {
				println("[");
				while (c.hasNext()) {
					print(c.next().toJson());
					println(c.hasNext() ? "," : "");
				}
				println("]");
			} else {
				printHtmlHeader("Accounts of Agent " + Utils.getAgentLabel(agentId) + " - Nanopub Registry");
				println("<h1>Accounts of Agent " + Utils.getAgentLabel(agentId) + "</h1>");
				println("<p><a href=\"/agent?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">&lt; Agent</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"agentAccounts.json?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json</a> |");
				println("<a href=\"agentAccounts.json.txt?id=" + URLEncoder.encode(agentId, "UTF-8") + "\">.json.txt</a>");
				println("</p>");
				println("<h3>Account List</h3>");
				println("<ul>");
				while (c.hasNext()) {
					Document d = c.next();
					String pubkey = d.getString("pubkey");
	//				Object iCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", INTRO_TYPE_HASH), "position");
	//				Object eCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", ENDORSE_TYPE), "position");
	//				Object fCount = getMaxValue("listEntries", new Document("pubkey", pubkey).append("type", "$"), "position");
					println("<li><a href=\"/list/" + pubkey + "\"><code>" + getLabel(pubkey) + "</code></a> (" + d.get("status") + "), " +
							"quota " + d.get("quota") + ", " +
							"ratio " + df8.format(d.get("ratio")) + ", " +
							"path count " + d.get("pathCount") +
							"</li>");
				}
				println("</ul>");
				printHtmlFooter();
			}
		} else if (req.equals("/agents")) {
			MongoCursor<Document> c = collection("agents").find(mongoSession).sort(descending("totalRatio")).projection(exclude("_id")).cursor();
			if ("application/json".equals(format)) {
				println("[");
				while (c.hasNext()) {
					print(c.next().toJson());
					println(c.hasNext() ? "," : "");
				}
				println("]");
			} else {
				printHtmlHeader("Agent List - Nanopub Registry");
				println("<h1>Agent List</h1>");
				println("<p><a href=\"/\">&lt; Home</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"agents.json\">.json</a> |");
				println("<a href=\"agents.json.txt\">.json.txt</a>");
				println("</p>");
				println("<h3>Agents</h3>");
				println("<ol>");
				while (c.hasNext()) {
					Document d = c.next();
					if (d.get("agent").equals("$")) continue;
					String a = d.getString("agent");
					int accountCount = d.getInteger("accountCount");
					println("<li><a href=\"/agent?id=" + URLEncoder.encode(a, "UTF-8") + "\">" + Utils.getAgentLabel(a) + "</a>, " +
							accountCount + " account" + (accountCount == 1 ? "" : "s") + ", " +
							"ratio " + df8.format(d.get("totalRatio")) + ", " +
							"avg. path count " + df1.format(d.get("avgPathCount")) +
							"</li>");
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else if (req.equals("/latestNanopubs")) {
			MongoCursor<Document> c = collection("nanopubs").find(mongoSession).sort(descending("counter")).limit(1000).cursor();
			if ("application/json".equals(format)) {
				println("[");
				while (c.hasNext()) {
					print(gson.toJson(c.next().getString("_id")));
					println(c.hasNext() ? "," : "");
				}
				println("]");
			} else {
				printHtmlHeader("Latest nanopubs - Nanopub Registry");
				println("<h1>List of Nanopubs</h1>");
				println("<p><a href=\"/\">&lt; Home</a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"latestNanopubs.json\">.json</a> |");
				println("<a href=\"latestNanopubs.json.txt\">.json.txt</a>");
				println("</p>");
				println("<h3>Latest Nanopubs (max. 1000)</h3>");
				println("<ol>");
				while (c.hasNext()) {
					String npId = c.next().getString("_id");
					println("<li><a href=\"/np/" + npId + "\"><code>" + getLabel(npId) + "</code></a></li>");
				}
				println("</ol>");
				printHtmlFooter();
			}
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
	}

	private static String getLabel(Object obj) {
		if (obj == null) return null;
		if (obj.toString().length() < 10) return obj.toString();
		return obj.toString().substring(0, 10);
	}

}
