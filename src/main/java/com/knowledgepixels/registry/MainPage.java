package com.knowledgepixels.registry;

import java.io.IOException;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.mongodb.client.model.Indexes.descending;

public class MainPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		MainPage obj = new MainPage(req, httpResp);
		obj.show();
	}

	public MainPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	public void show() throws IOException {
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
			println("<p>Server Info:</p>");
			println("<ul>");
			println("<li><em>setup-id:</em> " + getValue("server-info", "setup-id") + "</li>");
			println("<li><em>status:</em> " + getValue("server-info", "status") + "</li>");
			println("<li><em>state-counter:</em> " + getValue("server-info", "state-counter") + "</li>");
			println("<li><em>coverage-types:</em> " + getValue("server-info", "coverage-types") + "</li>");
			println("<li><em>coverage-agents:</em> " + getValue("server-info", "coverage-agents") + "</li>");
			println("</ul>");
			println("<p>Setting:</p>");
			println("<ul>");
			println("<li><em>original:</em> " + getValue("setting", "original") + "</li>");
			println("<li><em>current:</em> " + getValue("setting", "current") + "</li>");
			println("</ul>");
			println("<p>Agent accounts:</p>");
			println("<ul>");
			println("<li><em>count:</em> " + collection("agent-accounts").countDocuments() + "</li>");
			println("</ul>");
			println("<p>Top agent accounts:</p>");
			println("<ul>");
			MongoCursor<Document> agentAccounts = collection("agent-accounts").find().sort(descending("ratio")).limit(20).cursor();
			while (agentAccounts.hasNext()) {
				Document d = agentAccounts.next();
				if (d.get("agent").equals("@")) continue;
				println("<li>" + d.get("agent") + " - " + d.getString("pubkey").substring(0, 10) + ", ratio " + d.get("ratio") + ", path count " + d.get("path-count") + "</li>");
			}
			println("</ul>");
			println("<p>Top agents:</p>");
			println("<ul>");
			MongoCursor<Document> agents = collection("agents").find().sort(descending("total-ratio")).limit(20).cursor();
			while (agents.hasNext()) {
				Document d = agents.next();
				if (d.get("agent").equals("@")) continue;
				println("<li>" + d.get("agent") + ", ratio " + d.get("total-ratio") + ", avg. path count " + d.get("avg-path-count") + "</li>");
			}
			println("</ul>");
			println("<p>Trust edges:</p>");
			println("<ul>");
			println("<li><em>count:</em> " + collection("trust-edges").countDocuments() + "</li>");
			println("</ul>");
			println("<p>Nanopubs:</p>");
			println("<ul>");
			println("<li><em>count:</em> " + getMaxValue("nanopubs", "counter") + "</li>");
			println("</ul>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
