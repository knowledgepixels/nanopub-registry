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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.isSet;
import static com.knowledgepixels.registry.Utils.*;

public class NanopubPage extends Page {

    private static final Logger logger = LoggerFactory.getLogger(NanopubPage.class);

    static final String NANODASH_BASE_URL_DEFAULT = "https://nanodash.knowledgepixels.com/";

    static String getNanodashBaseUrl() {
        return Utils.getEnv("REGISTRY_NANODASH_BASE_URL", NANODASH_BASE_URL_DEFAULT);
    }

    private final boolean forwardHtml;

    public static void show(RoutingContext context) {
        show(context, false);
    }

    public static void show(RoutingContext context, boolean forwardHtml) {
        NanopubPage page;
        logger.info("Received nanopub request: {} forwardHtml={}", context.request().path(), forwardHtml);
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new NanopubPage(s, context, forwardHtml);
            page.show();
        } catch (IOException ex) {
            logger.warn("Failed to show nanopub for request {}: {} ({})", context.request().path(), ex.getMessage(), ex.getClass().getSimpleName(), ex);
        } finally {
            logger.debug("Ending response for nanopub request: {}", context.request().path());
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private NanopubPage(ClientSession mongoSession, RoutingContext context, boolean forwardHtml) {
        super(mongoSession, context);
        this.forwardHtml = forwardHtml;
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();
        String ext = getExtension();
        String format = Utils.getType(ext);
        final String req = getRequestString();

        logger.debug("Preparing nanopub response for request: {} (ext={}, resolvedFormat={})", getFullRequest(), ext, format);

        if (format == null) {
            format = Utils.getMimeType(c, SUPPORTED_TYPES_NANOPUB);
            logger.debug("Resolved format from Accept header: {}", format);
        }
        if (format == null) {
            logger.warn("Invalid nanopub request (could not determine format): {}", getFullRequest());
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        var presentationFormat = getPresentationFormat();
        if (presentationFormat != null) {
            setRespContentType(presentationFormat);
            logger.debug("Overriding response content type with presentation format: {}", presentationFormat);
        } else {
            setRespContentType(format);
            logger.debug("Set response content type: {}", format);
        }

        if (req.matches("/(np|get)/RA[a-zA-Z0-9-_]{43}(\\.[a-z]+)?")) {
            String ac = req.replaceFirst("/(np|get)/(RA[a-zA-Z0-9-_]{43})(\\.[a-z]+)?", "$2");
            logger.debug("Lookup nanopub id: {}", ac);
            Document npDoc = collection(Collection.NANOPUBS.toString()).find(new Document("_id", ac)).first();
            if (npDoc == null) {
                if (!isSet(mongoSession, Collection.SERVER_INFO.toString(), "testInstance")) {
                    //getResp().sendError(404, "Not found: " + ac);
                    logger.info("Nanopub {} not found locally; redirecting to external resolver", ac);
                    c.response().setStatusCode(307);
                    c.response().putHeader("Location", "https://np.knowledgepixels.com/" + ac);
                    return;
                } else {
                    logger.warn("Nanopub {} not found (test instance): {}", ac, getFullRequest());
                    c.response().setStatusCode(404).setStatusMessage("Not found: " + getFullRequest());
                    return;
                }
            }
            //		String url = ServerConf.getInfo().getPublicUrl();
            if (TYPE_TRIG.equals(format)) {
                logger.info("Serving nanopub {} as TRIG", ac);
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
                    logger.info("Served nanopub {} as Jelly frame (bytes written)", ac);
                }
            } else if (format != null && format.equals(TYPE_NQUADS)) {
                logger.info("Serving nanopub {} as N-Quads", ac);
                outputNanopub(npDoc, RDFFormat.NQUADS);
            } else if (format != null && format.equals(TYPE_JSONLD)) {
                logger.info("Serving nanopub {} as JSON-LD", ac);
                outputNanopub(npDoc, RDFFormat.JSONLD);
            } else if (format != null && format.equals(TYPE_TRIX)) {
                logger.info("Serving nanopub {} as TriX", ac);
                outputNanopub(npDoc, RDFFormat.TRIX);
            } else if (forwardHtml && isHtmlRequested(c)) {
                String fullId = npDoc.getString("fullId");
                logger.info("Forwarding HTML request for nanopub {} to Nanodash (url={})", ac, getNanodashBaseUrl());
                c.response().setStatusCode(302);
                c.response().putHeader("Location", getNanodashBaseUrl() + "explore?id=" + Utils.urlEncode(fullId));
                return;
            } else if (forwardHtml) {
                // Non-HTML default for /get/ path (e.g. Accept: */*): serve as trig
                logger.info("Forwarding non-HTML /get/ request for {} as TRIG", ac);
                setRespContentType(TYPE_TRIG);
                println(npDoc.getString("content"));
            } else {
                logger.info("Rendering HTML detail view for nanopub {}", ac);
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
            logger.warn("Invalid nanopub request path: {}", getFullRequest());
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
        }
    }

    private static boolean isHtmlRequested(RoutingContext c) {
        String accept = c.request().getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private void outputNanopub(Document npDoc, RDFFormat rdfFormat) {
        RoutingContext c = getContext();
        try {
            Nanopub np = new NanopubImpl(npDoc.getString("content"), RDFFormat.TRIG);
            c.response().write(NanopubUtils.writeToString(np, rdfFormat), Charsets.UTF_8.toString());
            logger.info("Transformed nanopub {} to {}", npDoc.getString("_id"), rdfFormat);
        } catch (RDF4JException | MalformedNanopubException | IOException ex) {
            logger.warn("Failed transforming nanopub {} to {}: {} ({})", npDoc.getString("_id"), rdfFormat, ex.getMessage(), ex.getClass().getSimpleName(), ex);
            c.response().setStatusCode(500).setStatusMessage("Failed transforming nanopub: " + getFullRequest());
        }
    }

}
