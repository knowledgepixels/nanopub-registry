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
import org.eclipse.rdf4j.model.util.Values;
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

/**
 * Utility class for the Nanopub Registry.
 */
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

    /**
     * Get the IDs of nanopublications invalidated by the given nanopublication.
     *
     * @param np the nanopublication to check
     * @return a set of IRI IDs of invalidated nanopublications
     */
    public static Set<IRI> getInvalidatedNanopubIds(Nanopub np) {
        Set<IRI> invalidatedNanopubs = new HashSet<>();
        for (Statement st : NanopubUtils.getStatements(np)) {
            if (!(st.getObject() instanceof IRI)) continue;
            Resource subject = st.getSubject();
            IRI predicate = st.getPredicate();
            if ((predicate.equals(NPX.RETRACTS) || predicate.equals(NPX.INVALIDATES)) || (predicate.equals(NPX.SUPERSEDES) && subject.equals(np.getUri()))) {
                if (TrustyUriUtils.isPotentialTrustyUri(st.getObject().stringValue())) {
                    invalidatedNanopubs.add((IRI) st.getObject());
                }
            }
        }
        return invalidatedNanopubs;
    }

    private static ReadsEnvironment ENV_READER = new ReadsEnvironment(System::getenv);

    /**
     * Set the environment reader (used for testing purposes).
     *
     * @param reader the environment reader to set
     */
    public static void setEnvReader(ReadsEnvironment reader) {
        ENV_READER = reader;
    }

    /**
     * Get an environment variable, returning a default value if not set.
     *
     * @param name         the name of the environment variable
     * @param defaultValue the default value to return if the variable is not set
     * @return the value of the environment variable, or the default value if not set
     */
    public static String getEnv(String name, String defaultValue) {
        logger.info("Retrieving environment variable: {}", name);
        String value = ENV_READER.getEnv(name);
        if (value == null) {
            value = defaultValue;
            logger.info("The variable: {} is not set. Using default value: {}", name, defaultValue);
        }
        return value;
    }

    /**
     * Get the type hash for a given type, recording it in the database if necessary.
     *
     * @param mongoSession the MongoDB client session
     * @param type         the type to get the hash for
     * @return the type hash
     */
    public static String getTypeHash(ClientSession mongoSession, Object type) {
        String typeHash = Utils.getHash(type.toString());
        if (type.toString().equals("$")) {
            typeHash = "$";
        } else {
            RegistryDB.recordHash(mongoSession, type.toString());
        }
        return typeHash;
    }

    /**
     * Get a label for an agent ID, truncating if necessary.
     *
     * @param agentId the agent ID
     * @return the agent label
     */
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

    /**
     * Check if the given status indicates an unloaded entry.
     *
     * @param status the status to check
     * @return true if the status indicates an unloaded entry, false otherwise
     */
    public static boolean isUnloadedStatus(String status) {
        if (status.equals(EntryStatus.seen.getValue())) return true;  // only exists in "accounts_loading"?
        return status.equals(EntryStatus.skipped.getValue());
    }

    /**
     * Check if the given status indicates a core loaded entry.
     *
     * @param status the status to check
     * @return true if the status indicates a core loaded entry, false otherwise
     */
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

    /**
     * Check if the given status indicates a fully loaded entry.
     *
     * @param status the status to check
     * @return true if the status indicates a fully loaded entry, false otherwise
     */
    public static boolean isFullyLoadedStatus(String status) {
        return status.equals(EntryStatus.loaded.getValue());
    }

    public static final IRI APPROVES_OF = Values.iri("http://purl.org/nanopub/x/approvesOf");

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

    /**
     * Get the type corresponding to a given file extension.
     *
     * @param extension the file extension
     * @return the corresponding MIME type, or null if not found
     */
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

    /**
     * Get the list of peer registry URLs.
     *
     * @return the list of peer registry URLs
     */
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

    private static volatile NanopubSetting settingNp;

    /**
     * Get the nanopublication setting.
     *
     * @return the nanopublication setting
     * @throws RDF4JException            if there is an error retrieving the setting
     * @throws MalformedNanopubException if the setting nanopublication is malformed
     * @throws IOException               if there is an I/O error
     */
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

    /**
     * Get a random peer registry URL.
     *
     * @return a random peer registry URL
     * @throws RDF4JException if there is an error retrieving the peer URLs
     */
    public static String getRandomPeer() throws RDF4JException {
        List<String> peerUrls = getPeerUrls();
        return peerUrls.get(random.nextInt(peerUrls.size()));
    }

    private static final Random random = new Random();

    /**
     * Get the random number generator.
     *
     * @return the random number generator
     */
    public static Random getRandom() {
        return random;
    }

    private static final Gson g = new Gson();
    private static Type listType = new TypeToken<List<String>>() {
    }.getType();

    /**
     * Retrieve a list of strings from a JSON URL.
     *
     * @param url the URL to retrieve the JSON from
     * @return the list of strings
     * @throws JsonIOException     if there is an error reading the JSON
     * @throws JsonSyntaxException if the JSON syntax is invalid
     * @throws IOException         if there is an I/O error
     * @throws URISyntaxException  if the URL syntax is invalid
     */
    public static List<String> retrieveListFromJsonUrl(String url) throws JsonIOException, JsonSyntaxException, IOException, URISyntaxException {
        return g.fromJson(new InputStreamReader(new URI(url).toURL().openStream()), listType);
    }

}
