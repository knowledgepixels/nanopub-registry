package com.knowledgepixels.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.FailedApiCallException;
import org.nanopub.extra.services.QueryAccess;

// TODO Code copied and adjusted (made synchronous) from Nanodash; should be moved to nanopub-java at some point
public class ApiCache {

	private ApiCache() {}  // no instances allowed

	private transient static Map<String,ApiResponse> cachedResponses = new HashMap<>();
	private transient static Map<String,Long> lastRefresh = new HashMap<>();


	private static ApiResponse get(String queryId, Map<String,String> params) {
		try {
			return QueryAccess.get(queryId, params);
		} catch (FailedApiCallException ex) {
			ex.printStackTrace();
		}
		return null;
	}

	private static void updateResponse(String queryName, Map<String,String> params) {
		Map<String,String> nanopubParams = new HashMap<>();
		if (params != null) {
			for (String k : params.keySet()) nanopubParams.put(k, params.get(k));
		}
		ApiResponse response = get(queryName, nanopubParams);
		String cacheId = getCacheId(queryName, params);
		cachedResponses.put(cacheId, response);
		lastRefresh.put(cacheId, System.currentTimeMillis());
	}

	public static synchronized ApiResponse retrieveResponse(final String queryName, final Map<String,String> params) {
		long timeNow = System.currentTimeMillis();
		String cacheId = getCacheId(queryName, params);
		boolean needsRefresh = true;
		if (cachedResponses.containsKey(cacheId)) {
			long cacheAge = timeNow - lastRefresh.get(cacheId);
			needsRefresh = cacheAge > 60 * 60 * 1000;
		}
		if (needsRefresh) {
			updateResponse(queryName, params);
		}
		return cachedResponses.get(cacheId);
	}

	private static String paramsToString(Map<String,String> params) {
		if (params == null) return "";
		List<String> keys = new ArrayList<>(params.keySet());
		Collections.sort(keys);
		String s = "";
		for (String k : keys) s += " " + k + "=" + params.get(k);
		return s;
	}

	public static String getCacheId(String queryName, Map<String,String> params) {
		return queryName + " " + paramsToString(params);
	}

}
