package com.knowledgepixels.registry;

import java.io.IOException;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;

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
			printHtmlHeader("Nanopub Registry");
			println("<h1>Nanopub Registry</h1>");
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
			println("<p>Agents:</p>");
			println("<ul>");
			MongoCursor<Document> agents = collection("agent-accounts").find().cursor();
			while (agents.hasNext()) {
				Document d = agents.next();
				if (d.get("agent").equals("@")) continue;
				println("<li>" + d.get("agent") + " - " + d.get("pubkey") + "</li>");
			}
			println("</ul>");
			println("<p>Nanopubs:</p>");
			println("<ul>");
			println("<li><em>counter:</em> " + getMaxValue("nanopubs", "counter") + "</li>");
			println("</ul>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
