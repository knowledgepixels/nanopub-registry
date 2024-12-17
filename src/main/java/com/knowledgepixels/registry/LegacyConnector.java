package com.knowledgepixels.registry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Random;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.nanopub.NanopubUtils;

import com.google.common.base.Charsets;

// This class is used to connect to the 1st-generation publishing services in the form of nanopub-server.
// This code can be removed once the transition to Nanopub Registry is completed.
public class LegacyConnector {

	private LegacyConnector() {}  // no instances allowed

	private static final String[] serverUrls = { "https://np.knowledgepixels.com/", "https://server.np.trustyuri.net/", "http://server.np.dumontierlab.com/" };
	private static final Random random = new Random();

	public static void checkForNewNanopubs() {
		String baseUrl = serverUrls[random.nextInt(serverUrls.length)];
		String prev = checkUrl(baseUrl + "nanopubs");
		if (prev != null) {
			checkUrl(baseUrl + prev);
		}
	}

	private static String checkUrl(String url) {
		HttpGet get = new HttpGet(url);
		get.setHeader("Accept", "text/plain");
		HttpResponse resp = null;
		String prev = null;
		try {
			resp = NanopubUtils.getHttpClient().execute(get);
			for (Header h : resp.getHeaders("Link")) {
				System.err.println(h.getName() + " " + h.getValue());
				if (h.getValue().endsWith("; rel=\"prev\"")) {
					prev = h.getValue().replaceFirst("^.*<(.+)>.*$", "$1");
				}
			}
			try (BufferedReader i = new BufferedReader(new InputStreamReader(resp.getEntity().getContent(), Charsets.UTF_8))) {
				while (i.ready()) {
					// TODO: Here we need to make sure to append to existing lists:
					NanopubRetriever.retrieveNanopub(i.readLine());
				}
			};
		} catch (Exception ex) {
			if (resp != null) EntityUtils.consumeQuietly(resp.getEntity());
			System.err.println("Request to " + url + " was not successful: " + ex.getMessage());
		}
		return prev;
	}

}
