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
import java.io.InputStream;
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

    private static volatile String version;

    /**
     * Returns the registry's version (from Maven at build time, via a filtered
     * {@code version.properties} resource). Returns {@code "unknown"} if the
     * resource is unavailable.
     */
    public static String getVersion() {
        String v = version;
        if (v != null) {
            return v;
        }
        Properties p = new Properties();
        try (InputStream in = Utils.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            } else {
                logger.warn("version.properties resource not found on classpath; version will be reported as 'unknown'");
            }
        } catch (IOException ex) {
            logger.warn("Could not read version.properties", ex);
        }
        v = p.getProperty("version", "unknown");
        version = v;
        logger.info("Registry version resolved to '{}'", v);
        return v;
    }

    public static String getMimeType(RoutingContext context, String supported) {
        List<String> supportedList = Arrays.asList(StringUtils.split(supported, ','));
        String mimeType = supportedList.getFirst();
        String acceptHeader = context.request().getHeader("Accept");
        if (acceptHeader == null) {
            logger.trace("No Accept header present; defaulting to '{}'", mimeType);
            return mimeType;
        }
        try {
            mimeType = MIMEParse.bestMatch(supportedList, acceptHeader);
            logger.trace("Resolved Accept header '{}' to mime type '{}' (supported: {})", acceptHeader, mimeType, supported);
        } catch (Exception ex) {
            logger.error("Failed to parse Accept header '{}': {}", acceptHeader, ex.getMessage(), ex);
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
            if (!(st.getObject() instanceof IRI)) {
                continue;
            }
            Resource subject = st.getSubject();
            IRI predicate = st.getPredicate();
            if ((predicate.equals(NPX.RETRACTS) || predicate.equals(NPX.INVALIDATES)) || (predicate.equals(NPX.SUPERSEDES) && subject.equals(np.getUri()))) {
                if (TrustyUriUtils.isPotentialTrustyUri(st.getObject().stringValue())) {
                    invalidatedNanopubs.add((IRI) st.getObject());
                }
            }
        }
        if (!invalidatedNanopubs.isEmpty()) {
            logger.debug("Nanopub {} invalidates {} other nanopub(s): {}", np.getUri(), invalidatedNanopubs.size(), invalidatedNanopubs);
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
        logger.debug("Environment reader replaced (likely test setup)");
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
        logger.debug("Reading environment variable '{}'", name);
        String value = ENV_READER.getEnv(name);
        if (value == null) {
            value = defaultValue;
            logger.debug("Environment variable '{}' not set; using default: '{}'", name, defaultValue);
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
            logger.trace("Type '$' (unrestricted) mapped to special hash '$'");
        } else {
            logger.trace("Type '{}' hashed to '{}'; recording in database", type, typeHash);
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
            logger.warn("getAgentLabel called with null or blank agentId");
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
        if (status.equals(EntryStatus.seen.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        return status.equals(EntryStatus.skipped.getValue());
    }

    /**
     * Check if the given status indicates a core loaded entry.
     *
     * @param status the status to check
     * @return true if the status indicates a core loaded entry, false otherwise
     */
    public static boolean isCoreLoadedStatus(String status) {
        if (status.equals(EntryStatus.visited.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        if (status.equals(EntryStatus.expanded.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        if (status.equals(EntryStatus.processed.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        if (status.equals(EntryStatus.aggregated.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        if (status.equals(EntryStatus.approved.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
        if (status.equals(EntryStatus.contested.getValue())) {
            return true;
        }
        if (status.equals(EntryStatus.toLoad.getValue())) {
            return true;  // only exists in "accounts_loading"?
        }
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
        String type = extensionTypeMap.get(extension);
        if (type == null) {
            logger.debug("No known MIME type for extension '{}'", extension);
        }
        return type;
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
                logger.debug("Resolving peer URLs from REGISTRY_PEER_URLS env var");
                for (String peerUrl : envPeerUrls.trim().split("\\s+")) {
                    if (thisRegistryUrl.equals(peerUrl)) {
                        logger.debug("Excluding self ('{}') from peer URL list", peerUrl);
                        continue;
                    }
                    peerUrls.add(peerUrl);
                }
            } else {
                logger.debug("REGISTRY_PEER_URLS not set; resolving peer URLs from registry setting's bootstrap services");
                NanopubSetting setting;
                try {
                    setting = getSetting();
                } catch (MalformedNanopubException | IOException ex) {
                    logger.error("Failed to load registry setting from file", ex);
                    throw new RuntimeException(ex);
                }
                for (IRI iri : setting.getBootstrapServices()) {
                    String peerUrl = iri.stringValue();
                    if (thisRegistryUrl.equals(peerUrl)) {
                        logger.debug("Excluding self ('{}') from peer URL list", peerUrl);
                        continue;
                    }
                    peerUrls.add(peerUrl);
                }
            }
            logger.info("Resolved {} peer URL(s): {}", peerUrls.size(), peerUrls);
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
                    logger.info("Loading registry setting from '{}'", settingPath);
                    try {
                        settingNp = new NanopubSetting(new NanopubImpl(new File(settingPath)));
                        logger.info("Registry setting loaded successfully from '{}'", settingPath);
                    } catch (RDF4JException | MalformedNanopubException | IOException ex) {
                        logger.error("Failed to load registry setting from '{}'", settingPath, ex);
                        throw ex;
                    }
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
        if (peerUrls.isEmpty()) {
            logger.warn("getRandomPeer called but no peer URLs are configured");
        }
        String peer = peerUrls.get(random.nextInt(peerUrls.size()));
        logger.trace("Selected random peer: '{}'", peer);
        return peer;
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
        logger.debug("Retrieving JSON list from '{}'", url);
        try {
            List<String> result = g.fromJson(new InputStreamReader(new URI(url).toURL().openStream()), listType);
            logger.debug("Retrieved {} entries from '{}'", result == null ? 0 : result.size(), url);
            return result;
        } catch (JsonIOException | JsonSyntaxException | IOException | URISyntaxException ex) {
            logger.warn("Failed to retrieve or parse JSON list from '{}': {}", url, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Extracts the {@code foaf:name} literal asserted on the intro's user IRI.
     * Returns {@code null} when the assertion declares no such name. When multiple
     * {@code foaf:name} literals are asserted on the same agent, the lexicographic
     * minimum is returned for deterministic behaviour across rebuilds.
     */
    public static String extractIntroName(org.nanopub.extra.setting.IntroNanopub agentIntro) {
        IRI agentIri = agentIntro.getUser();
        if (agentIri == null) {
            logger.debug("Intro nanopub {} has no user IRI; cannot extract name", agentIntro.getNanopub().getUri());
            return null;
        }
        String chosen = null;
        for (Statement st : agentIntro.getNanopub().getAssertion()) {
            if (!st.getSubject().equals(agentIri)) {
                continue;
            }
            if (!st.getPredicate().equals(org.eclipse.rdf4j.model.vocabulary.FOAF.NAME)) {
                continue;
            }
            if (!(st.getObject() instanceof org.eclipse.rdf4j.model.Literal)) {
                continue;
            }
            String candidate = st.getObject().stringValue();
            if (chosen == null || candidate.compareTo(chosen) < 0) {
                chosen = candidate;
            }
        }
        return chosen;
    }

}
