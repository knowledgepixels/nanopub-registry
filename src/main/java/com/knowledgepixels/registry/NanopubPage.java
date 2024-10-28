package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;

import java.io.IOException;

import org.apache.commons.lang.StringEscapeUtils;
import org.bson.Document;

import jakarta.servlet.http.HttpServletResponse;

public class NanopubPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		NanopubPage obj = new NanopubPage(req, httpResp);
		obj.show();
	}

	private NanopubPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	protected void show() throws IOException {
		String format;
		String ext = getReq().getExtension();
		final String req = getReq().getFullRequest();
		if ("trig".equals(ext)) {
			format = "application/trig";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/trig,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + req);
			return;
		}
		if (req.matches("/np/RA[a-zA-Z0-9-_]{43}(\\.[a-z]+)?")) {
			String ac = req.replaceFirst("/np/(RA[a-zA-Z0-9-_]{43})(\\.[a-z]+)?", "$1");
			Document npDoc = collection("nanopubs").find(new Document("_id", ac)).first();
			if (npDoc == null) {
				getResp().sendError(404, "Not found: " + ac);
				return;
			}
	//		String url = ServerConf.getInfo().getPublicUrl();
			if ("application/trig".equals(format)) {
				println(npDoc.getString("content"));
			} else {
				printHtmlHeader("Nanopublication " + ac + " - Nanopub Registry");
				println("<h1>Nanopublication</h1>");
				println("<h3>ID</h3>");
				String fullId = npDoc.getString("full-id");
				println("<p><a href=\"" + fullId + "\"><code>" + fullId + "</code></a></p>");
				println("<h3>Content</h3>");
				println("<pre>");
				println(StringEscapeUtils.escapeHtml(npDoc.getString("content")));
				println("</pre>");
				printHtmlFooter();
			}
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
