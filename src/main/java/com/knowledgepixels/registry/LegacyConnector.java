package com.knowledgepixels.registry;

import com.google.common.base.Charsets;
import com.mongodb.client.ClientSession;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.nanopub.NanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

// This class is used to connect to the 1st-generation publishing services in the form of nanopub-server.
// This code can be removed once the transition to Nanopub Registry is completed.
public class LegacyConnector {

    private LegacyConnector() {
    }  // no instances allowed

    private static final String[] serverUrls = {"https://np.knowledgepixels.com/", "http://server.np.dumontierlab.com/"};

    private static final Logger log = LoggerFactory.getLogger(LegacyConnector.class);

    // Just to make sure we don't need 1000+ DB requests each time we check for updates:
    private static Map<String, Boolean> loadedCache = new HashMap<>();

    public static void checkForNewNanopubs(ClientSession mongoSession) {
        String baseUrl = serverUrls[Utils.getRandom().nextInt(serverUrls.length)];
        String prev = checkUrl(mongoSession, baseUrl + "nanopubs");
        if (prev != null) {
            checkUrl(mongoSession, baseUrl + prev);
        }
    }

    private static String checkUrl(ClientSession mongoSession, String url) {
        log.info("Checking legacy URL {}", url);
        HttpGet get = new HttpGet(url);
        get.setHeader("Accept", "text/plain");
        HttpResponse resp = null;
        String prev = null;
        try {
            resp = NanopubUtils.getHttpClient().execute(get);
            for (Header h : resp.getHeaders("Link")) {
                if (h.getValue().endsWith("; rel=\"prev\"")) {
                    prev = h.getValue().replaceFirst("^.*<(.+)>.*$", "$1");
                }
            }
            try (BufferedReader i = new BufferedReader(new InputStreamReader(resp.getEntity().getContent(), Charsets.UTF_8))) {
                String npUri;
                while ((npUri = i.readLine()) != null) {
                    if (loadedCache.containsKey(npUri)) continue;
                    // TODO: Here we need to make sure to append to existing lists:
                    NanopubLoader.simpleLoad(mongoSession, npUri);
                    loadedCache.put(npUri, true);
                }
            }
        } catch (IOException ex) {
            if (resp != null) EntityUtils.consumeQuietly(resp.getEntity());
            log.info("Request to {} was not successful: ", url, ex);
        }
        return prev;
    }

}
