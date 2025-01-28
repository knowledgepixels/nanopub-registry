package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.has;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.http.client.methods.HttpGet;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.ApiResponseEntry;

import com.knowledgepixels.registry.jelly.JellyUtils;
import com.knowledgepixels.registry.jelly.MaybeNanopub;
import com.knowledgepixels.registry.jelly.NanopubStream;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public class NanopubLoader {

	private NanopubLoader() {}

	public final static String INTRO_TYPE = Utils.INTRO_TYPE.stringValue();
	public final static String INTRO_TYPE_HASH = Utils.getHash(INTRO_TYPE);
	public final static String ENDORSE_TYPE = Utils.APPROVAL_TYPE.stringValue();
	public final static String ENDORSE_TYPE_HASH = Utils.getHash(ENDORSE_TYPE);

	// TODO Distinguish and support these cases:
	//      1. Simple load: load to all core lists if pubkey is "core-loaded", or load to all lists if pubkey is "full-loaded"
	//      2. Core load: load to all core lists (initialize if needed), or load to all lists if pubkey is "full-loaded"
	//      3. Full load: load to all lists (initialize if needed)

	public static void simpleLoad(String nanopubId) {
		simpleLoad(retrieveNanopub(nanopubId));
	}

	public static void simpleLoad(Nanopub np) {
		String pubkey = RegistryDB.getPubkey(np);
		if (pubkey == null) {
			System.err.println("Ignore (not signed): " + np.getUri());
			return;
		}
		String pubkeyHash = Utils.getHash(pubkey);
		// TODO Do we need to load anything else here, into the other DB collections?
		if (has("lists", new Document("pubkey", pubkeyHash).append("type", "$").append("status", "loaded"))) {
			RegistryDB.loadNanopub(np, pubkeyHash, "$");
		} else if (has("lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH).append("status", "loaded"))) {
			RegistryDB.loadNanopub(np, pubkeyHash, INTRO_TYPE, ENDORSE_TYPE);
		}
	}

	public static void retrieveNanopubs(String type, String pubkeyHash, Consumer<ApiResponseEntry> processFunction) {
		Map<String,String> params = new HashMap<>();
		params.put("pubkeyhash", pubkeyHash);
		ApiResponse resp;
		if (type != null) {
			params.put("type", type);
			resp = ApiCache.retrieveResponse("RAcX8PxM-XGE_T5EvGdSv0ByA6hFLGL8faeqS3sBWNeIY/get-nanopubs-for-pubkey-and-type", params);
		} else {
			resp = ApiCache.retrieveResponse("RAdxUS1loH_wZRz_K4dGiRY63weCJRQijMK55LOO12yZQ/get-nanopubs-for-pubkey", params);
		}
		for (ApiResponseEntry e : resp.getData()) {
			processFunction.accept(e);
		}
	}

	private static final String[] peerUrls = new String[] {
			"https://registry.petapico.org/",
			"https://registry.knowledgepixels.com/",
			"https://registry.np.kpxl.org/"
	};
	private static Random random = new Random();

	public static Stream<MaybeNanopub> retrieveNanopubsFromPeers(String typeHash, String pubkeyHash) {
		String thisServiceUrl = System.getenv("REGISTRY_SERVICE_URL");
		String peerUrl = null;
		do {
			peerUrl = peerUrls[random.nextInt(peerUrls.length)];
		} while (peerUrl.equals(thisServiceUrl));

		String requestUrl = peerUrl + "list/" + pubkeyHash + "/" + typeHash + ".jelly";
		System.err.println("Request: " + requestUrl);
		try {
			InputStream is = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl)).getEntity().getContent();
			return NanopubStream.fromByteStream(is).getAsNanopubs();
		} catch (UnsupportedOperationException | IOException ex) {
			ex.printStackTrace();
		}
		return null;
	}

	public static Nanopub retrieveNanopub(String nanopubId) {
		Nanopub np = retrieveLocalNanopub(nanopubId);
		int tryCount = 0;
		while (np == null) {
			if (tryCount > 10) {
				throw new RuntimeException("Could not load nanopub: " + nanopubId);
			} else if (tryCount > 0) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException ex) {
					ex.printStackTrace();
				}
			}
			System.err.println("Loading " + nanopubId);

			// TODO Reach out to other Nanopub Registries here:
			np = GetNanopub.get(nanopubId);
			if (np != null) {
				RegistryDB.loadNanopub(np);
			} else {
				tryCount = tryCount + 1;
			}
		}
		return np;
	}

	public static Nanopub retrieveLocalNanopub(String nanopubId) {
		String ac = TrustyUriUtils.getArtifactCode(nanopubId);
		MongoCursor<Document> cursor = RegistryDB.get("nanopubs", new Document("_id", ac));
		if (!cursor.hasNext()) return null;
		try {
			// Parse from Jelly, not TriG (it's faster)
			return JellyUtils.readFromDB(((Binary) cursor.next().get("jelly")).getData());
		} catch (RDF4JException | MalformedNanopubException ex) {
			ex.printStackTrace();
			return null;
		}
	}

}
