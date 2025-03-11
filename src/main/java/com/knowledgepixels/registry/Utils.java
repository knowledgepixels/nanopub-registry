package com.knowledgepixels.registry;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.commonjava.mimeparse.MIMEParse;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.mongodb.client.ClientSession;

import io.vertx.ext.web.RoutingContext;
import net.trustyuri.TrustyUriUtils;

public class Utils {

	private Utils() {}  // no instances allowed

	public static String getMimeType(RoutingContext context, String supported) {
		List<String> supportedList = Arrays.asList(StringUtils.split(supported, ','));
		String mimeType = supportedList.get(0);
		try {
			mimeType = MIMEParse.bestMatch(supportedList, context.request().getHeader("Accept"));
		} catch (Exception ex) {}
		return mimeType;
	}

	public static String urlEncode(Object o) {
		try {
			return URLEncoder.encode((o == null ? "" : o.toString()), "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			throw new RuntimeException(ex);
		}
	}

	public static String getHash(String s) {
		return Hashing.sha256().hashString(s, Charsets.UTF_8).toString();
	}

	public static Set<IRI> getInvalidatedNanopubIds(Nanopub np) {
		Set<IRI> l = new HashSet<IRI>();
		for (Statement st : NanopubUtils.getStatements(np)) {
			if (!(st.getObject() instanceof IRI)) continue;
			Resource s = st.getSubject();
			IRI p = st.getPredicate();
			if ((p.equals(RETRACTS) || p.equals(INVALIDATES)) || (p.equals(SUPERSEDES) && s.equals(np.getUri()))) {
				if (TrustyUriUtils.isPotentialTrustyUri(st.getObject().stringValue())) {
					l.add((IRI) st.getObject());
				}
			}
		}
		return l;
	}

	public static String getEnv(String name, String defaultValue) {
		String value = System.getenv(name);
		if (value == null) value = defaultValue;
		return value;
	}

	public static String getTypeHash(ClientSession mongoSession, Object type) {
		String typeHash = Utils.getHash(type.toString());
		if (type.toString().equals("$")) {
			typeHash = "$";
		} else {
			RegistryDB.recordHash(mongoSession, type.toString());
		}
		return typeHash;
	}

	public static String getAgentLabel(String agentId) {
		agentId = agentId.replaceFirst("^https://orcid\\.org/", "orcid:");
		if (agentId.length() > 55) return agentId.substring(0, 50) + "...";
		return agentId;
	}

	public static boolean isUnloadedStatus(String status) {
		if (status.equals("seen")) return true;  // only exists in "accounts_loading"?
		if (status.equals("skipped")) return true;
		return false;
	}

	public static boolean isCoreLoadedStatus(String status) {
		if (status.equals("visited")) return true;  // only exists in "accounts_loading"?
		if (status.equals("expanded")) return true;  // only exists in "accounts_loading"?
		if (status.equals("processed")) return true;  // only exists in "accounts_loading"?
		if (status.equals("aggregated")) return true;  // only exists in "accounts_loading"?
		if (status.equals("approved")) return true;  // only exists in "accounts_loading"?
		if (status.equals("contested")) return true;
		if (status.equals("toLoad")) return true;  // only exists in "accounts_loading"?
		if (status.equals("loaded")) return true;
		return false;
	}

	public static boolean isFullyLoadedStatus(String status) {
		if (status.equals("loaded")) return true;
		return false;
	}

	private static ValueFactory vf = SimpleValueFactory.getInstance();
	public static final IRI SUPERSEDES = vf.createIRI("http://purl.org/nanopub/x/supersedes");
	public static final IRI RETRACTS = vf.createIRI("http://purl.org/nanopub/x/retracts");
	public static final IRI INVALIDATES = vf.createIRI("http://purl.org/nanopub/x/invalidates");
	public static final IRI APPROVES_OF = vf.createIRI("http://purl.org/nanopub/x/approvesOf");

	public static final IRI INTRO_TYPE = vf.createIRI("http://purl.org/nanopub/x/declaredBy");
	public static final IRI APPROVAL_TYPE = vf.createIRI("http://purl.org/nanopub/x/approvesOf");

	public static final String TYPE_JSON = "application/json";
	public static final String TYPE_JELLY = "application/x-jelly-rdf";
	public static final String TYPE_HTML = "text/html";

	// Content types supported on a ListPage
	public static final String SUPPORTED_TYPES_LIST = TYPE_JSON + "," + TYPE_JELLY + "," + TYPE_HTML;
}
