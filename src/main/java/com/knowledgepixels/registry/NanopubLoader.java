package com.knowledgepixels.registry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.ApiResponseEntry;

import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public class NanopubLoader {

	private NanopubLoader() {}

	// TODO Distinguish and support these cases:
	//      1. Simple load: load to all core lists if pubkey is "core-loaded", or load to all lists if pubkey is "full-loaded"
	//      2. Core load: load to all core lists (initialize if needed), or load to all lists if pubkey is "full-loaded"
	//      3. Full load: load to all lists (initialize if needed)

	public static void retrieveNanopubs(String type, String pubkey, Consumer<ApiResponseEntry> processFunction) {
		Map<String,String> params = new HashMap<>();
		params.put("pubkeyhash", pubkey);
		if (type != null) params.put("type", type);
		// TODO This query seems to exclude nanopublications without types:
		ApiResponse resp = ApiCache.retrieveResponse("RAsO4jKUf7combAsoGw5nj1LDbBqmH1bUr_j0AWqO9QMI/get-nanopubs-for-pubkey-and-type", params);
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
			return new NanopubImpl(cursor.next().getString("content"), RDFFormat.TRIG);
		} catch (RDF4JException | MalformedNanopubException ex) {
			ex.printStackTrace();
			return null;
		}
	}

}
