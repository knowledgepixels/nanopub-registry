package com.knowledgepixels.registry;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.github.jsonldjava.shaded.com.google.common.reflect.TypeToken;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.mongodb.client.ClientSession;
import io.vertx.ext.web.RoutingContext;
import net.trustyuri.TrustyUriUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.mimeparse.MIMEParse;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.setting.NanopubSetting;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Utils {

    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

    private Utils() {
    }  // no instances allowed

    public static String getMimeType(RoutingContext context, String supported) {
        List<String> supportedList = Arrays.asList(StringUtils.split(supported, ','));
        String mimeType = supportedList.getFirst();
        try {
            mimeType = MIMEParse.bestMatch(supportedList, context.request().getHeader("Accept"));
        } catch (Exception ex) {
            logger.error("Error parsing Accept header.", ex);
        }
        return mimeType;
    }

    public static String urlEncode(Object o) {
        return URLEncoder.encode((o == null ? "" : o.toString()), StandardCharsets.UTF_8);
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
            if ((p.equals(NPX.RETRACTS) || p.equals(NPX.INVALIDATES)) || (p.equals(NPX.SUPERSEDES) && s.equals(np.getUri()))) {
                if (TrustyUriUtils.isPotentialTrustyUri(st.getObject().stringValue())) {
                    l.add((IRI) st.getObject());
                }
            }
        }
        return l;
    }

    private static ReadsEnvironment ENV_READER = new ReadsEnvironment(System::getenv);

    static void setEnvReader(ReadsEnvironment reader) {
        ENV_READER = reader;
    }

    public static String getEnv(String name, String defaultValue) {
        String value = ENV_READER.getEnv(name);
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
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("Agent ID cannot be null or blank");
        }
        agentId = agentId.replaceFirst("^https://orcid\\.org/", "orcid:");
        if (agentId.length() > 55) {
            return agentId.substring(0, 50) + "...";
        }
        return agentId;
    }

    public static boolean isUnloadedStatus(String status) {
        if (status.equals(EntryStatus.seen.getValue())) return true;  // only exists in "accounts_loading"?
        return status.equals(EntryStatus.skipped.getValue());
    }

    public static boolean isCoreLoadedStatus(String status) {
        if (status.equals(EntryStatus.visited.getValue())) return true;  // only exists in "accounts_loading"?
        if (status.equals(EntryStatus.expanded.getValue())) return true;  // only exists in "accounts_loading"?
        if (status.equals(EntryStatus.processed.getValue())) return true;  // only exists in "accounts_loading"?
        if (status.equals(EntryStatus.aggregated.getValue())) return true;  // only exists in "accounts_loading"?
        if (status.equals(EntryStatus.approved.getValue())) return true;  // only exists in "accounts_loading"?
        if (status.equals(EntryStatus.contested.getValue())) return true;
        if (status.equals(EntryStatus.toLoad.getValue())) return true;  // only exists in "accounts_loading"?
        return status.equals(EntryStatus.loaded.getValue());
    }

    public static boolean isFullyLoadedStatus(String status) {
        return status.equals(EntryStatus.loaded.getValue());
    }

    private static ValueFactory vf = SimpleValueFactory.getInstance();
    public static final IRI APPROVES_OF = vf.createIRI("http://purl.org/nanopub/x/approvesOf");

    public static final String TYPE_JSON = "application/json";
    public static final String TYPE_TRIG = "application/trig";
    public static final String TYPE_JELLY = "application/x-jelly-rdf";
    public static final String TYPE_JSONLD = "application/ld+json";
    public static final String TYPE_NQUADS = "application/n-quads";
    public static final String TYPE_TRIX = "application/trix";
    public static final String TYPE_HTML = "text/html";

    // Content types supported on a ListPage
    public static final String SUPPORTED_TYPES_LIST = TYPE_JSON + "," + TYPE_JELLY + "," + TYPE_HTML;
    // Content types supported on a NanopubPage
    public static final String SUPPORTED_TYPES_NANOPUB = TYPE_TRIG + "," + TYPE_JELLY + "," + TYPE_JSONLD + "," + TYPE_NQUADS + "," + TYPE_TRIX + "," + TYPE_HTML;

    private static Map<String, String> extensionTypeMap;

    public static String getType(String extension) {
        if (extension == null) {
            return null;
        }
        if (extensionTypeMap == null) {
            extensionTypeMap = new HashMap<>();
            extensionTypeMap.put("trig", TYPE_TRIG);
            extensionTypeMap.put("jelly", TYPE_JELLY);
            extensionTypeMap.put("jsonld", TYPE_JSONLD);
            extensionTypeMap.put("nq", TYPE_NQUADS);
            extensionTypeMap.put("xml", TYPE_TRIX);
            extensionTypeMap.put("html", TYPE_HTML);
            extensionTypeMap.put("json", TYPE_JSON);
        }
        return extensionTypeMap.get(extension);
    }

    private static List<String> peerUrls;

    public static List<String> getPeerUrls() {
        if (peerUrls == null) {
            peerUrls = new ArrayList<>();
            String envPeerUrls = getEnv("REGISTRY_PEER_URLS", "");
            String thisRegistryUrl = getEnv("REGISTRY_SERVICE_URL", "");
            if (!envPeerUrls.isEmpty()) {
                for (String peerUrl : envPeerUrls.split(";")) {
                    if (thisRegistryUrl.equals(peerUrl)) {
                        continue;
                    }
                    peerUrls.add(peerUrl);
                }
            } else {
                NanopubSetting setting;
                try {
                    setting = getSetting();
                } catch (MalformedNanopubException | IOException ex) {
                    logger.error("Error loading registry setting: {}", ex.getMessage());
                    throw new RuntimeException(ex);
                }
                for (IRI iri : setting.getBootstrapServices()) {
                    String peerUrl = iri.stringValue();
                    if (thisRegistryUrl.equals(peerUrl)) {
                        continue;
                    }
                    peerUrls.add(peerUrl);
                }
            }
        }
        return peerUrls;
    }

    //private static final String SETTING_FILE_PATH = Utils.getEnv("REGISTRY_SETTING_FILE", "/data/setting.trig");
    private static volatile NanopubSetting settingNp;

    public static NanopubSetting getSetting() throws RDF4JException, MalformedNanopubException, IOException {
        if (settingNp == null) {
            synchronized (Utils.class) {
                if (settingNp == null) {
                    String settingPath = getEnv("REGISTRY_SETTING_FILE", "/data/setting.trig");
                    settingNp = new NanopubSetting(new NanopubImpl(new File(settingPath)));
                }
            }
        }
        return settingNp;
    }

    public static String getRandomPeer() throws RDF4JException {
        List<String> peerUrls = getPeerUrls();
        return peerUrls.get(random.nextInt(peerUrls.size()));
    }

    private static final Random random = new Random();

    public static Random getRandom() {
        return random;
    }

    private static final Gson g = new Gson();
    private static Type listType = new TypeToken<List<String>>() {
    }.getType();

    public static List<String> retrieveListFromJsonUrl(String url) throws JsonIOException, JsonSyntaxException, IOException, URISyntaxException {
        return g.fromJson(new InputStreamReader(new URI(url).toURL().openStream()), listType);
    }

}
