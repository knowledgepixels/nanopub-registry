package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.has;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
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
import com.mongodb.client.ClientSession;
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

	public static void simpleLoad(ClientSession mongoSession, String nanopubId) {
		simpleLoad(mongoSession, retrieveNanopub(mongoSession, nanopubId));
	}

	public static void simpleLoad(ClientSession mongoSession, Nanopub np) {
		String pubkey = RegistryDB.getPubkey(np);
		if (pubkey == null) {
			System.err.println("Ignore (not signed): " + np.getUri());
			return;
		}
		String pubkeyHash = Utils.getHash(pubkey);
		// TODO Do we need to load anything else here, into the other DB collections?
		if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("status", "loaded"))) {
			RegistryDB.loadNanopub(mongoSession, np, pubkeyHash, "$");
		} else if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH).append("status", "loaded"))) {
			RegistryDB.loadNanopub(mongoSession, np, pubkeyHash, INTRO_TYPE, ENDORSE_TYPE);
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
			"https://registry.np.trustyuri.net/"
	};

	public static Stream<MaybeNanopub> retrieveNanopubsFromPeers(String typeHash, String pubkeyHash) {
		// TODO Move the code of this method to nanopub-java library.

		String thisServiceUrl = System.getenv("REGISTRY_SERVICE_URL");
		List<String> peerUrlsToTry = new ArrayList<>(Arrays.asList(peerUrls));
		Collections.shuffle(peerUrlsToTry);
		while (!peerUrlsToTry.isEmpty()) {
			String peerUrl = peerUrlsToTry.remove(0);
			if (peerUrl.equals(thisServiceUrl)) continue;
	
			String requestUrl = peerUrl + "list/" + pubkeyHash + "/" + typeHash + ".jelly";
			System.err.println("Request: " + requestUrl);
			try {
				CloseableHttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
				int httpStatus = resp.getStatusLine().getStatusCode();
				if (httpStatus < 200 || httpStatus >= 300) {
					System.err.println("Request failed: " + peerUrl + " " + httpStatus);
					EntityUtils.consumeQuietly(resp.getEntity());
					continue;
				}
				Header nrStatus = resp.getFirstHeader("Nanopub-Registry-Status");
				if (nrStatus == null) {
					System.err.println("Nanopub-Registry-Status header not found at: " + peerUrl);
					EntityUtils.consumeQuietly(resp.getEntity());
					continue;
				} else if (!nrStatus.getValue().equals("ready") && !nrStatus.getValue().equals("updating")) {
					System.err.println("Peer in non-ready state: " + peerUrl + " " + nrStatus.getValue());
					EntityUtils.consumeQuietly(resp.getEntity());
					continue;
				}
				InputStream is = resp.getEntity().getContent();
				return NanopubStream.fromByteStream(is).getAsNanopubs();
			} catch (UnsupportedOperationException | IOException ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}

	public static Nanopub retrieveNanopub(ClientSession mongoSession, String nanopubId) {
		Nanopub np = retrieveLocalNanopub(mongoSession, nanopubId);
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
				RegistryDB.loadNanopub(mongoSession, np);
			} else {
				tryCount = tryCount + 1;
			}
		}
		return np;
	}

	public static Nanopub retrieveLocalNanopub(ClientSession mongoSession, String nanopubId) {
		String ac = TrustyUriUtils.getArtifactCode(nanopubId);
		MongoCursor<Document> cursor = RegistryDB.get(mongoSession, "nanopubs", new Document("_id", ac));
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
