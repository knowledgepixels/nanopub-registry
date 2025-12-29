package com.knowledgepixels.registry;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.mongodb.client.ClientSession;
import eu.neverblink.jelly.core.utils.IoUtils;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang.StringEscapeUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.isSet;
import static com.knowledgepixels.registry.Utils.*;

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
        String ext = getExtension();
        String format = Utils.getType(ext);
        final String req = getRequestString();
        if (format == null) {
            format = Utils.getMimeType(c, SUPPORTED_TYPES_NANOPUB);
        }
        if (format == null) {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        var presentationFormat = getPresentationFormat();
        if (presentationFormat != null) {
            setRespContentType(presentationFormat);
        } else {
            setRespContentType(format);
        }

        if (req.matches("/np/RA[a-zA-Z0-9-_]{43}(\\.[a-z]+)?")) {
            String ac = req.replaceFirst("/np/(RA[a-zA-Z0-9-_]{43})(\\.[a-z]+)?", "$1");
            Document npDoc = collection(Collection.NANOPUBS.toString()).find(new Document("_id", ac)).first();
            if (npDoc == null) {
                if (!isSet(mongoSession, Collection.SERVER_INFO.toString(), "testInstance")) {
                    //getResp().sendError(404, "Not found: " + ac);
                    c.response().setStatusCode(307);
                    c.response().putHeader("Location", "https://np.knowledgepixels.com/" + ac);
                    return;
                } else {
                    c.response().setStatusCode(404).setStatusMessage("Not found: " + getFullRequest());
                    return;
                }
            }
            //		String url = ServerConf.getInfo().getPublicUrl();
            if (TYPE_TRIG.equals(format)) {
                println(npDoc.getString("content"));
            } else if (TYPE_JELLY.equals(format)) {
                if (presentationFormat != null && presentationFormat.startsWith("text")) {
                    // Parse the Jelly frame and return it as Protobuf Text Format Language
                    // https://protobuf.dev/reference/protobuf/textformat-spec/
                    // It's better than bombarding the browser with a binary file.
                    var frame = eu.neverblink.jelly.core.proto.google.v1.RdfStreamFrame
                            .parseFrom(((Binary) npDoc.get("jelly")).getData());
                    println(frame.toString());
                } else {
                    // To return this correctly, we would need to prepend the delimiter byte before the Jelly frame
                    // (the DB stores is non-delimited and the HTTP response must be delimited).
                    BufferOutputStream outputStream = new BufferOutputStream();
                    IoUtils.writeFrameAsDelimited(
                            ((Binary) npDoc.get("jelly")).getData(),
                            outputStream
                    );
                    c.response().write(outputStream.getBuffer());
                }
            } else if (format != null && format.equals(TYPE_NQUADS)) {
                outputNanopub(npDoc, RDFFormat.NQUADS);
            } else if (format != null && format.equals(TYPE_JSONLD)) {
                outputNanopub(npDoc, RDFFormat.JSONLD);
            } else if (format != null && format.equals(TYPE_TRIX)) {
                outputNanopub(npDoc, RDFFormat.TRIX);
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
                println("<a href=\"/np/" + ac + ".jelly.txt\">.jelly.txt</a> |");
                println("<a href=\"/np/" + ac + ".jsonld\">.jsonld</a> |");
                println("<a href=\"/np/" + ac + ".jsonld.txt\">.jsonld.txt</a> |");
                println("<a href=\"/np/" + ac + ".nq\">.nq</a> |");
                println("<a href=\"/np/" + ac + ".nq.txt\">.nq.txt</a> |");
                println("<a href=\"/np/" + ac + ".xml\">.xml</a> |");
                println("<a href=\"/np/" + ac + ".xml.txt\">.xml.txt</a>");
                println("</p>");
                println("<h3>Content</h3>");
                println("<pre>");
                println(StringEscapeUtils.escapeHtml(npDoc.getString("content")));
                println("</pre>");
                printHtmlFooter();
            }
        } else {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
        }
    }

    private void outputNanopub(Document npDoc, RDFFormat rdfFormat) {
        RoutingContext c = getContext();
        try {
            Nanopub np = new NanopubImpl(npDoc.getString("content"), RDFFormat.TRIG);
            c.response().write(NanopubUtils.writeToString(np, rdfFormat), Charsets.UTF_8.toString());
        } catch (RDF4JException | MalformedNanopubException | IOException ex) {
            c.response().setStatusCode(500).setStatusMessage("Failed transforming nanopub: " + getFullRequest());
            ex.printStackTrace();
        }
    }

}
