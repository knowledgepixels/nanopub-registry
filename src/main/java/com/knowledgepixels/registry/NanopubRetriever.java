package com.knowledgepixels.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.http.HttpResponse;
import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.QueryCall;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public class NanopubRetriever {

	private NanopubRetriever() {}

	public static void retrieveNanopubs(String type, String pubkey, Consumer<String> processFunction) {
		try {
			Map<String,String> params = new HashMap<>();
			params.put("pubkeyhash", pubkey);
			HttpResponse resp = QueryCall.run("RAOxsf3y2qb4gCBnb5MBZw_sywUemOMQu2tOe7pZKoefg/get-nanopubs-for-pubkeyhash", params);
			BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getEntity().getContent()));
			reader.readLine(); // discard first line
			String line = reader.readLine();
			while (line != null) {
				processFunction.accept(line);
				line = reader.readLine();
			}
			reader.close();
		} catch (IOException ex) {
			ex.printStackTrace();
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
		MongoCursor<Document> cursor = RegistryDB.get("nanopubs", new BasicDBObject("_id", ac));
		if (!cursor.hasNext()) return null;
		try {
			return new NanopubImpl(cursor.next().getString("content"), RDFFormat.TRIG);
		} catch (RDF4JException | MalformedNanopubException ex) {
			ex.printStackTrace();
			return null;
		}
	}

}
