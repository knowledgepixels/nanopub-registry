package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.mongodb.client.model.Indexes.ascending;

import java.io.IOException;

import org.bson.Document;

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
		if ("json".equals(ext)) {
			format = "application/json";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/json,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
		if (!getReq().getFullRequest().matches("/list/[0-9a-f]{64}/[0-9a-f]{64}")) {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
		String pubkey = getReq().getFullRequest().replaceFirst("/list/([0-9a-f]{64})/[0-9a-f]{64}", "$1");
		String type = getReq().getFullRequest().replaceFirst("/list/[0-9a-f]{64}/([0-9a-f]{64})", "$1");
//		String url = ServerConf.getInfo().getPublicUrl();
		if ("application/json".equals(format)) {
			// TODO
			//println(ServerConf.getInfo().asJson());
		} else {
			printHtmlHeader("List for pubkey " + pubkey.substring(0, 10) + " / type " + type.substring(0, 10)  + " - Nanopub Registry");
			println("<h1>List</h1>");
			println("<h3>Pubkey Hash</h3>");
			println("<p><code>" + pubkey + "</code></p>");
			println("<h3>Type Hash</h3>");
			println("<p><code>" + type + "</code></p>");
			println("<h3>Entries</h3>");
			println("<ol>");
			MongoCursor<Document> entries = collection("list-entries").find(
					new Document("pubkey", pubkey)
						.append("type", type)
				).sort(ascending("position")).cursor();
			while (entries.hasNext()) {
				Document d = entries.next();
				println("<li><code>" + d.getString("np") + "</code></li>");
			}
			println("</ol>");
			printHtmlFooter();
		}
//		if (url != null && !url.isEmpty()) {
//			setCanonicalLink(url);
//		}
		getResp().setContentType(format);
	}

}
