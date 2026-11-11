package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;
import org.nanopub.Nanopub;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * A quota set for a pubkey by a nanopublication, as an alternative to the explicit entries
 * in {@code REGISTRY_COVERAGE_AGENTS} (issue #220).
 *
 * <p>
 * The env var works, but every change to it means editing the configuration of every host
 * and recreating its container. A grant is published once, signed with one of the keys
 * this registry lists in {@code REGISTRY_QUOTA_AUTHORITIES}, and reaches every registry
 * that trusts that key through the ordinary sync.
 *
 * <p>
 * A grant nanopublication is typed {@code npx:QuotaGrant} and says in its assertion which
 * pubkey gets which quota:
 *
 * <pre>
 * sub:grant a npx:QuotaGrant ;
 *     npx:hasPublicKeyHash "403d6743…" ;
 *     npx:hasQuota 20000 .
 * </pre>
 *
 * The pubkey is named by its hash ({@code npx:hasPublicKeyHash}) or by the full key
 * ({@code npx:hasPublicKey}, as in a key declaration). One nanopublication may hold several
 * grants, one per subject.
 *
 * <p>
 * The quota grants are read by the trust cycle, which applies them where it records the
 * quotas: an explicit env entry wins over a grant, and a grant wins over the computed
 * quota. For a pubkey the trust network has not approved, a grant doubles as approval: the
 * pubkey becomes an account with that quota, as it does for an explicit entry. Where more
 * than one current grant names the same pubkey, the most recently created nanopublication
 * wins. A grant stops counting when its nanopublication is retracted or superseded, which
 * is what the {@code invalidated} flag of its list entry records.
 */
public final class QuotaGrant {

    private static final Logger logger = LoggerFactory.getLogger(QuotaGrant.class);

    /**
     * The nanopublication type of a quota grant.
     */
    public static final IRI QUOTA_GRANT = Values.iri(NPX.NAMESPACE, "QuotaGrant");

    /**
     * The hash of {@link #QUOTA_GRANT}, under which the grants sit on the authorities' type lists.
     */
    public static final String QUOTA_GRANT_TYPE_HASH = Utils.getHash(QUOTA_GRANT.stringValue());

    /**
     * The granted quota, a non-negative integer literal.
     */
    public static final IRI HAS_QUOTA = Values.iri(NPX.NAMESPACE, "hasQuota");

    /**
     * The hash of the pubkey a grant is for.
     */
    public static final IRI HAS_PUBLIC_KEY_HASH = Values.iri(NPX.NAMESPACE, "hasPublicKeyHash");

    /**
     * What a pubkey hash looks like: a SHA-256 in lowercase hex, as lists and accounts are
     * keyed by. A grant for anything else could never apply to a pubkey, and would only seed
     * an account nothing can be loaded for.
     */
    static final Pattern PUBKEY_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final String pubkeyHash;
    private final int quota;

    QuotaGrant(String pubkeyHash, int quota) {
        this.pubkeyHash = pubkeyHash;
        this.quota = quota;
    }

    /**
     * @return the hash of the pubkey this grant is for
     */
    public String getPubkeyHash() {
        return pubkeyHash;
    }

    /**
     * @return the granted quota
     */
    public int getQuota() {
        return quota;
    }

    /**
     * Reads the grants a nanopublication holds: one per assertion subject that has a quota
     * and names a pubkey. The signature is not checked here, as the caller takes the
     * nanopublication from a list that is already keyed by its signing pubkey.
     *
     * <p>
     * Anything that is not plainly one grant is logged and left out, rather than resolved by
     * the order of the statements, which is not the same on every registry: a subject with
     * only one of the two values, with more than one pubkey or quota, with a quota that is
     * not a non-negative integer or a pubkey hash that is not one, and a pubkey that two
     * subjects give different quotas.
     *
     * @param np the nanopublication
     * @return its grants, ordered by pubkey hash, possibly empty
     */
    public static List<QuotaGrant> extract(Nanopub np) {
        Map<Resource, Set<String>> pubkeyHashes = new HashMap<>();
        Map<Resource, Set<String>> quotas = new HashMap<>();
        for (Statement st : np.getAssertion()) {
            Resource subj = st.getSubject();
            IRI pred = st.getPredicate();
            Value obj = st.getObject();
            if (pred.equals(HAS_PUBLIC_KEY_HASH) && obj instanceof Literal) {
                pubkeyHashes.computeIfAbsent(subj, k -> new HashSet<>()).add(obj.stringValue().trim());
            } else if (pred.equals(NPX.HAS_PUBLIC_KEY) && obj instanceof Literal) {
                pubkeyHashes.computeIfAbsent(subj, k -> new HashSet<>()).add(Utils.getHash(obj.stringValue()));
            } else if (pred.equals(HAS_QUOTA) && obj instanceof Literal) {
                quotas.computeIfAbsent(subj, k -> new HashSet<>()).add(obj.stringValue().trim());
            }
        }
        Map<String, Integer> granted = new TreeMap<>();
        Set<String> conflicting = new HashSet<>();
        for (Map.Entry<Resource, Set<String>> e : pubkeyHashes.entrySet()) {
            Resource subj = e.getKey();
            Set<String> quotaValues = quotas.get(subj);
            if (quotaValues == null) {
                logger.warn("Ignoring quota grant {} in {}: it names a pubkey but no quota", subj, np.getUri());
                continue;
            }
            if (e.getValue().size() > 1 || quotaValues.size() > 1) {
                logger.warn("Ignoring quota grant {} in {}: more than one pubkey or quota", subj, np.getUri());
                continue;
            }
            String pubkeyHash = e.getValue().iterator().next();
            String quotaValue = quotaValues.iterator().next();
            if (!PUBKEY_HASH_PATTERN.matcher(pubkeyHash).matches()) {
                logger.warn("Ignoring quota grant {} in {}: '{}' is not a pubkey hash", subj, np.getUri(), pubkeyHash);
                continue;
            }
            int quota;
            try {
                quota = Integer.parseInt(quotaValue);
                if (quota < 0) {
                    throw new NumberFormatException("negative");
                }
            } catch (NumberFormatException ex) {
                logger.warn("Ignoring quota grant {} in {}: '{}' is not a non-negative integer", subj, np.getUri(), quotaValue);
                continue;
            }
            Integer previous = granted.put(pubkeyHash, quota);
            if (previous != null && previous != quota) {
                conflicting.add(pubkeyHash);
            }
        }
        for (String pubkeyHash : conflicting) {
            logger.warn("Ignoring the quota grants for {} in {}: they give different quotas", pubkeyHash, np.getUri());
            granted.remove(pubkeyHash);
        }
        List<QuotaGrant> grants = new ArrayList<>();
        for (Map.Entry<String, Integer> e : granted.entrySet()) {
            grants.add(new QuotaGrant(e.getKey(), e.getValue()));
        }
        return grants;
    }

    /**
     * The quotas currently granted, by pubkey hash: the grants on the {@code npx:QuotaGrant}
     * lists of the configured authorities that have not been invalidated, with the most
     * recently created nanopublication deciding where several name the same pubkey.
     *
     * @param mongoSession the MongoDB client session
     * @return the granted quota per pubkey hash; empty when no authority is configured
     */
    public static Map<String, Integer> readGranted(ClientSession mongoSession) {
        Set<String> authorities = AgentFilter.getQuotaAuthorities();
        if (authorities.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> granted = new HashMap<>();
        Map<String, Ranking> winners = new HashMap<>();
        for (String authority : authorities) {
            Document filter = new Document("pubkey", authority)
                    .append("type", QUOTA_GRANT_TYPE_HASH)
                    .append("invalidated", new Document("$ne", true));
            try (MongoCursor<Document> entries = collection("listEntries").find(mongoSession, filter).cursor()) {
                while (entries.hasNext()) {
                    String ac = entries.next().getString("np");
                    Nanopub np = NanopubLoader.retrieveLocalNanopub(mongoSession, ac);
                    if (np == null) {
                        logger.warn("Quota grant {} of authority {} is listed but not stored; skipping it", ac, authority);
                        continue;
                    }
                    Ranking ranking = new Ranking(SimpleTimestampPattern.getCreationTime(np), ac);
                    for (QuotaGrant grant : extract(np)) {
                        Ranking current = winners.get(grant.pubkeyHash);
                        if (current == null || ranking.beats(current)) {
                            winners.put(grant.pubkeyHash, ranking);
                            granted.put(grant.pubkeyHash, grant.quota);
                        }
                    }
                }
            }
        }
        logger.info("Read {} granted quotas from {} quota authorities", granted.size(), authorities.size());
        return granted;
    }

    /**
     * Which of two grants for the same pubkey counts: the later created one, and between
     * equal or missing timestamps the one with the greater artifact code, so that the answer
     * is the same on every registry whatever order the grants arrived in.
     */
    private record Ranking(Calendar created, String artifactCode) {

        boolean beats(Ranking other) {
            long t = created == null ? Long.MIN_VALUE : created.getTimeInMillis();
            long o = other.created == null ? Long.MIN_VALUE : other.created.getTimeInMillis();
            if (t != o) {
                return t > o;
            }
            return artifactCode.compareTo(other.artifactCode) > 0;
        }
    }

}
