package com.knowledgepixels.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.function.Consumer;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public class NanopubRetriever {

	private NanopubRetriever() {}

	public static void retrieveNanopubs(String type, String pubkey, Consumer<String> processFunction) {
		try {
			String pubkeyQueryPart;
			if (pubkey == null) {
				pubkeyQueryPart = "%3Fpubkey";
			} else {
				pubkeyQueryPart = "%22" + pubkey + "%22";
			}
			String callUrl = "https://query.np.trustyuri.net/repo/type/" + Utils.getHash(type) + "?" +
					"query=prefix%20npa%3A%20%3Chttp%3A%2F%2Fpurl.org%2Fnanopub%2Fadmin%2F%3E%20select%20%3Fnp%20where%20%7B%20graph%20npa%3Agraph%20%7B%20%3Fnp%20npa%3AhasValidSignatureForPublicKeyHash%20" +
					pubkeyQueryPart +
					"%20.%20%7D%20%7D";
			HttpGet get = new HttpGet(callUrl);
			get.setHeader(HttpHeaders.ACCEPT, "text/csv");
			HttpResponse resp = HttpClientBuilder.create().build().execute(get);
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
