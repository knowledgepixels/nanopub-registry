package com.knowledgepixels.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

public class NanopubRetriever {

	private NanopubRetriever() {}

	public static List<String> retrieveNanopubs(String type, String pubkeyHash) {
		List<String> values = new ArrayList<String>();
		try {
			String callUrl = "https://query.np.trustyuri.net/repo/type/" + type + "?" +
					"query=prefix%20npa%3A%20%3Chttp%3A%2F%2Fpurl.org%2Fnanopub%2Fadmin%2F%3E%20select%20%3Fnp%20where%20%7B%20graph%20npa%3Agraph%20%7B%20%3Fnp%20npa%3AhasValidSignatureForPublicKeyHash%20%22" +
					pubkeyHash +
					"%22%20.%20%7D%20%7D";
			HttpGet get = new HttpGet(callUrl);
			get.setHeader(HttpHeaders.ACCEPT, "application/json");
			HttpResponse resp = HttpClientBuilder.create().build().execute(get);
			InputStream in = resp.getEntity().getContent();
			String respString = IOUtils.toString(in, StandardCharsets.UTF_8);
			JSONArray resultsArray = new JSONObject(respString).getJSONObject("results").getJSONArray("bindings");
			for (int i = 0; i < resultsArray.length(); i++) {
				JSONObject resultObject = resultsArray.getJSONObject(i);
				String uri = resultObject.getJSONObject("np").getString("value");
				values.add(uri);
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		return values;
	}

}
