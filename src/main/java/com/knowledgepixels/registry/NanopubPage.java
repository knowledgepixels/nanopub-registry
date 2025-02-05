package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;

import java.io.IOException;

import eu.ostrzyciel.jelly.core.IoUtils$;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamFrame$;
import org.apache.commons.lang.StringEscapeUtils;
import org.bson.Document;

import jakarta.servlet.http.HttpServletResponse;
import org.bson.types.Binary;

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
		final String req = getReq().getRequestString();
		if ("trig".equals(ext)) {
			format = "application/trig";
		} else if ("jelly".equals(ext)) {
			format = "application/x-jelly-rdf";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/x-jelly-rdf,application/trig,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + req);
			return;
		}

		var presentationFormat = getReq().getPresentationFormat();
		if (presentationFormat != null) {
			getResp().setContentType(presentationFormat);
		} else {
			getResp().setContentType(format);
		}

		if (req.matches("/np/RA[a-zA-Z0-9-_]{43}(\\.[a-z]+)?")) {
			String ac = req.replaceFirst("/np/(RA[a-zA-Z0-9-_]{43})(\\.[a-z]+)?", "$1");
			Document npDoc = collection("nanopubs").find(new Document("_id", ac)).first();
			if (npDoc == null) {
				//getResp().sendError(404, "Not found: " + ac);
				getResp().setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
				getResp().setHeader("Location", "https://np.knowledgepixels.com/" + ac);
				return;
			}
	//		String url = ServerConf.getInfo().getPublicUrl();
			if ("application/trig".equals(format)) {
				println(npDoc.getString("content"));
			} else if ("application/x-jelly-rdf".equals(format)) {
				if (presentationFormat != null && presentationFormat.startsWith("text")) {
					// Parse the Jelly frame and return it as Protobuf Text Format Language
					// https://protobuf.dev/reference/protobuf/textformat-spec/
					// It's better than bombarding the browser with a binary file.
					var frame = RdfStreamFrame$.MODULE$.parseFrom(((Binary) npDoc.get("jelly")).getData());
					println(frame.toProtoString());
				} else {
					// To return this correctly, we would need to prepend the delimiter byte before the Jelly frame
					// (the DB stores is non-delimited and the HTTP response must be delimited).
					IoUtils$.MODULE$.writeFrameAsDelimited(
							((Binary) npDoc.get("jelly")).getData(),
							getResp().getOutputStream()
					);
				}
			} else {
				printHtmlHeader("Nanopublication " + ac + " - Nanopub Registry");
				println("<h1>Nanopublication</h1>");
				println("<p><a href=\"/\">&lt; Home</a></p>");
				println("<h3>ID</h3>");
				String fullId = npDoc.getString("fullId");
				println("<p><a href=\"" + fullId + "\"><code>" + fullId + "</code></a></p>");
				println("<h3>Formats</h3>");
				println("<p>");
				println("<a href=\"/np/" + ac + ".trig\">.trig</a> |");
				println("<a href=\"/np/" + ac + ".trig.txt\">.trig.txt</a> |");
				println("<a href=\"/np/" + ac + ".jelly\">.jelly</a> |");
				println("<a href=\"/np/" + ac + ".jelly.txt\">.jelly.txt</a>");
				println("</p>");
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
	}

}
