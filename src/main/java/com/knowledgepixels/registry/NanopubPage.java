package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;

import java.io.IOException;

import org.apache.commons.lang.StringEscapeUtils;
import org.bson.Document;
import org.bson.types.Binary;

import com.mongodb.client.ClientSession;

import eu.ostrzyciel.jelly.core.IoUtils$;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamFrame$;
import io.vertx.ext.web.RoutingContext;

public class NanopubPage extends Page {

	public static void show(RoutingContext context) {
		NanopubPage page;
		try (ClientSession s = RegistryDB.getClient().startSession()) {
			s.startTransaction();
			page = new NanopubPage(s, context);
			page.show();
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			context.response().end();
			// TODO Clean-up here?
		}
	}

	private NanopubPage(ClientSession mongoSession, RoutingContext context) {
		super(mongoSession, context);
	}

	protected void show() throws IOException {
		RoutingContext c = getContext();
		String format;
		String ext = getExtension();
		final String req = getRequestString();
		if ("trig".equals(ext)) {
			format = "application/trig";
		} else if ("jelly".equals(ext)) {
			format = "application/x-jelly-rdf";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/x-jelly-rdf,application/trig,text/html";
			format = Utils.getMimeType(c, suppFormats);
		} else {
			c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
			return;
		}

		var presentationFormat = getPresentationFormat();
		if (presentationFormat != null) {
			c.response().putHeader("Content-Type", presentationFormat);
		} else {
			c.response().putHeader("Content-Type", format);
		}

		if (req.matches("/np/RA[a-zA-Z0-9-_]{43}(\\.[a-z]+)?")) {
			String ac = req.replaceFirst("/np/(RA[a-zA-Z0-9-_]{43})(\\.[a-z]+)?", "$1");
			Document npDoc = collection("nanopubs").find(new Document("_id", ac)).first();
			if (npDoc == null) {
				//getResp().sendError(404, "Not found: " + ac);
				c.response().setStatusCode(307);
				c.response().putHeader("Location", "https://np.knowledgepixels.com/" + ac);
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

					// Does this work???
					BufferOutputStream outputStream = new BufferOutputStream();
					IoUtils$.MODULE$.writeFrameAsDelimited(
							((Binary) npDoc.get("jelly")).getData(),
							outputStream
					);
					c.response().write(outputStream.getBuffer());
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
			c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
			return;
		}
	}

}
