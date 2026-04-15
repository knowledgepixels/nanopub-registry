package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;

import java.io.IOException;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * Serves hash-keyed trust state snapshots at <code>/trust-state/&lt;hash&gt;.json</code>.
 *
 * <p>Content is immutable once a hash is known. Consumers use this endpoint after
 * detecting a hash change (via the {@code Nanopub-Registry-Trust-State-Hash} response
 * header) to load the corresponding snapshot side-by-side with the previous one.
 */
public class TrustStatePage extends Page {

    public static void show(RoutingContext context) {
        TrustStatePage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new TrustStatePage(s, context);
            page.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            context.response().end();
        }
    }

    private TrustStatePage(ClientSession mongoSession, RoutingContext context) {
        super(mongoSession, context);
    }

    protected void show() throws IOException {
        RoutingContext c = getContext();
        String req = getRequestString();
        String ext = getExtension();

        // Only the .json extension is supported.
        if (!"json".equals(ext)) {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        // Path must be /trust-state/<hash>. The hash character set is intentionally
        // permissive (404 on lookup miss is the authoritative check).
        if (!req.matches("/trust-state/[A-Za-z0-9_\\-]+")) {
            c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
            return;
        }

        String hash = req.substring("/trust-state/".length());
        Document snapshot = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(mongoSession, new Document("_id", hash)).first();
        if (snapshot == null) {
            c.response().setStatusCode(404).setStatusMessage("Trust state snapshot not found");
            return;
        }

        setRespContentType("application/json");
        // Content is immutable by construction: the URL encodes a hash that never rewrites.
        c.response().putHeader("Cache-Control", "public, immutable, max-age=31536000");

        // Serialize as an envelope so consumers can verify the hash and sort by counter
        // without reparsing the URL. _id (= hash) is renamed to trustStateHash in the output.
        Document output = new Document()
                .append("trustStateHash", snapshot.getString("_id"))
                .append("trustStateCounter", snapshot.get("trustStateCounter"))
                .append("createdAt", snapshot.get("createdAt"))
                .append("accounts", snapshot.get("accounts"));
        print(output.toJson());
    }

}
