package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.has;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.knowledgepixels.registry.jelly.JellyUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.ApiResponseEntry;

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
		Nanopub np = retrieveNanopub(nanopubId);
		String pubkeyHash = Utils.getHash(RegistryDB.getPubkey(np));
		// TODO Do we need to load anything else here, into the other DB collections?
		if (has("lists", new Document("pubkey", pubkeyHash).append("type", "$").append("status", "loaded"))) {
			RegistryDB.loadNanopub(np, pubkeyHash, "$");
		} else if (has("lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH).append("status", "loaded"))) {
			RegistryDB.loadNanopub(np, pubkeyHash, INTRO_TYPE, ENDORSE_TYPE);
		}
	}

	public static void retrieveNanopubs(String type, String pubkey, Consumer<ApiResponseEntry> processFunction) {
		Map<String,String> params = new HashMap<>();
		params.put("pubkeyhash", pubkey);
		ApiResponse resp;
		if (type != null) {
			params.put("type", type);
			resp = ApiCache.retrieveResponse("RAsO4jKUf7combAsoGw5nj1LDbBqmH1bUr_j0AWqO9QMI/get-nanopubs-for-pubkey-and-type", params);
		} else {
			resp = ApiCache.retrieveResponse("RAdxUS1loH_wZRz_K4dGiRY63weCJRQijMK55LOO12yZQ/get-nanopubs-for-pubkey", params);
		}
		for (ApiResponseEntry e : resp.getData()) {
			processFunction.accept(e);
		}
	}

	public static Nanopub retrieveNanopub(String nanopubId) {
		Nanopub np = retrieveLocalNanopub(nanopubId);
		if (np == null) {
			System.err.println("Loading " + nanopubId);
			np = GetNanopub.get(nanopubId);
			RegistryDB.loadNanopub(np);
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
