package com.knowledgepixels.registry;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import net.trustyuri.TrustyUriUtils;

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

public class Utils {

	private Utils() {}  // no instances allowed

	public static String getMimeType(HttpServletRequest req, String supported) {
		List<String> supportedList = Arrays.asList(StringUtils.split(supported, ','));
		String mimeType = supportedList.get(0);
		try {
			mimeType = MIMEParse.bestMatch(supportedList, req.getHeader("Accept"));
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

	public static String getHash(String pubkey) {
		return Hashing.sha256().hashString(pubkey, Charsets.UTF_8).toString();
	}

	public static List<IRI> getInvalidatedNanopubIds(Nanopub np) {
		List<IRI> l = new ArrayList<IRI>();
		for (Statement st : NanopubUtils.getStatements(np)) {
			if (!(st.getObject() instanceof IRI)) continue;
			Resource s = st.getSubject();
			IRI p = st.getPredicate();
			if ((p.equals(RETRACTS) || p.equals(INVALIDATES)) || (p.equals(SUPERSEDES) && s.equals(np.getUri()))) {
				if (TrustyUriUtils.isPotentialTrustyUri(st.getObject().stringValue())) {
					l.add(np.getUri());
				}
			}
		}
		return l;
	}

	private static ValueFactory vf = SimpleValueFactory.getInstance();
	public static final IRI SUPERSEDES = vf.createIRI("http://purl.org/nanopub/x/supersedes");
	public static final IRI RETRACTS = vf.createIRI("http://purl.org/nanopub/x/retracts");
	public static final IRI INVALIDATES = vf.createIRI("http://purl.org/nanopub/x/invalidates");

}
