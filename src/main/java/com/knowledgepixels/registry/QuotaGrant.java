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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * A quota set for an account by a nanopublication, as an alternative to the explicit entries
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
 * account — which pubkey of which agent — gets which quota:
 *
 * <pre>
 * sub:assertion {
 *   sub:grant a npx:QuotaGrant ;
 *     npx:forAgent &lt;https://orcid.org/0000-0000-0000-0000&gt; ;
 *     npx:hasPublicKeyHash "403d6743…" ;
 *     npx:hasQuota 20000 .
 * }
 * sub:pubinfo {
 *   this: npx:hasNanopubType npx:QuotaGrant .
 * }
 * </pre>
 *
 * The pubkey is named by its hash ({@code npx:hasPublicKeyHash}) or by the full key
 * ({@code npx:hasPublicKey}, as in a key declaration). One nanopublication may hold several
 * grants, one per subject. The type has to be declared in the pubinfo as shown: it is what
 * puts the nanopublication on the list the grants are read from, and a type given only in
 * the assertion is recognized only while the assertion has a single subject.
 *
 * <p>
 * A grant says how much an account may publish, not whether it may: approval stays with the
 * trust network. The quota grants are read by the trust cycle, which applies them where it
 * records the quotas of the accounts the network reaches: an explicit env entry wins over a
 * grant, and a grant wins over the computed quota. A grant for an account the trust network
 * does not approve has no effect, and takes effect once it does. Where more than one current
 * grant names the same account, the most recently created nanopublication wins. A grant
 * stops counting when its nanopublication is retracted or superseded, which is what the
 * {@code invalidated} flag of its list entry records.
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
     * The agent whose account a grant is for, an IRI.
     */
    public static final IRI FOR_AGENT = Values.iri(NPX.NAMESPACE, "forAgent");

    /**
     * The hash of the pubkey a grant is for.
     */
    public static final IRI HAS_PUBLIC_KEY_HASH = Values.iri(NPX.NAMESPACE, "hasPublicKeyHash");

    /**
     * What a pubkey hash looks like: a SHA-256 in lowercase hex, as lists and accounts are
     * keyed by. A grant for anything else could never apply to an account.
     */
    static final Pattern PUBKEY_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /**
     * The account a grant is for: one pubkey of one agent, which is what the registry keys
     * its accounts by. Naming the agent keeps a grant with the account it was written for,
     * should another agent come to declare the same key.
     *
     * @param agent      the agent IRI
     * @param pubkeyHash the hash of the pubkey
     */
    public record Target(String agent, String pubkeyHash) {

        static final Comparator<Target> ORDER = Comparator.comparing(Target::agent).thenComparing(Target::pubkeyHash);

    }

    private final Target target;
    private final int quota;

    QuotaGrant(Target target, int quota) {
        this.target = target;
        this.quota = quota;
    }

    /**
     * @return the account this grant is for
     */
    public Target getTarget() {
        return target;
    }

    /**
     * @return the agent whose account this grant is for
     */
    public String getAgent() {
        return target.agent();
    }

    /**
     * @return the hash of the pubkey this grant is for
     */
    public String getPubkeyHash() {
        return target.pubkeyHash();
    }

    /**
     * @return the granted quota
     */
    public int getQuota() {
        return quota;
    }

    /**
     * Reads the grants a nanopublication holds: one per assertion subject that names an
     * agent and a pubkey and has a quota. The signature is not checked here, as the caller
     * takes the nanopublication from a list that is already keyed by its signing pubkey.
     *
     * <p>
     * Anything that is not plainly one grant is logged and left out, rather than resolved by
     * the order of the statements, which is not the same on every registry: a subject that
     * has some but not all of the three values (an agent that is not an IRI, or a pubkey or
     * quota that is not a literal, counts as missing), with more than one agent, pubkey or
     * quota, with a quota that is not a non-negative integer or a pubkey hash that is not
     * one, and an account that two subjects give different quotas.
     *
     * @param np the nanopublication
     * @return its grants, ordered by agent and pubkey hash, possibly empty
     */
    public static List<QuotaGrant> extract(Nanopub np) {
        Map<Resource, Set<String>> agents = new HashMap<>();
        Map<Resource, Set<String>> pubkeyHashes = new HashMap<>();
        Map<Resource, Set<String>> quotas = new HashMap<>();
        for (Statement st : np.getAssertion()) {
            Resource subj = st.getSubject();
            IRI pred = st.getPredicate();
            Value obj = st.getObject();
            if (pred.equals(FOR_AGENT) && obj instanceof IRI) {
                agents.computeIfAbsent(subj, k -> new HashSet<>()).add(obj.stringValue());
            } else if (pred.equals(HAS_PUBLIC_KEY_HASH) && obj instanceof Literal) {
                pubkeyHashes.computeIfAbsent(subj, k -> new HashSet<>()).add(obj.stringValue().trim());
            } else if (pred.equals(NPX.HAS_PUBLIC_KEY) && obj instanceof Literal) {
                pubkeyHashes.computeIfAbsent(subj, k -> new HashSet<>()).add(Utils.getHash(obj.stringValue()));
            } else if (pred.equals(HAS_QUOTA) && obj instanceof Literal) {
                quotas.computeIfAbsent(subj, k -> new HashSet<>()).add(obj.stringValue().trim());
            }
        }
        Map<Target, Integer> granted = new TreeMap<>(Target.ORDER);
        Set<Target> conflicting = new HashSet<>();
        Set<Resource> subjects = new HashSet<>(agents.keySet());
        subjects.addAll(pubkeyHashes.keySet());
        subjects.addAll(quotas.keySet());
        for (Resource subj : subjects) {
            Set<String> agentValues = agents.get(subj);
            Set<String> pubkeyValues = pubkeyHashes.get(subj);
            Set<String> quotaValues = quotas.get(subj);
            if (agentValues == null || pubkeyValues == null || quotaValues == null) {
                logger.warn("Ignoring quota grant {} in {}: it lacks {}", subj, np.getUri(),
                        agentValues == null ? "an agent given as an IRI"
                                : pubkeyValues == null ? "a pubkey given as a literal" : "a quota given as a literal");
                continue;
            }
            if (agentValues.size() > 1 || pubkeyValues.size() > 1 || quotaValues.size() > 1) {
                logger.warn("Ignoring quota grant {} in {}: more than one agent, pubkey or quota", subj, np.getUri());
                continue;
            }
            String pubkeyHash = pubkeyValues.iterator().next();
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
            Target target = new Target(agentValues.iterator().next(), pubkeyHash);
            Integer previous = granted.put(target, quota);
            if (previous != null && previous != quota) {
                conflicting.add(target);
            }
        }
        for (Target target : conflicting) {
            logger.warn("Ignoring the quota grants for {} in {}: they give different quotas", target, np.getUri());
            granted.remove(target);
        }
        List<QuotaGrant> grants = new ArrayList<>();
        for (Map.Entry<Target, Integer> e : granted.entrySet()) {
            grants.add(new QuotaGrant(e.getKey(), e.getValue()));
        }
        return grants;
    }

    /**
     * The quotas currently granted, by account: the grants on the {@code npx:QuotaGrant}
     * lists of the configured authorities that have not been invalidated, with the most
     * recently created nanopublication deciding where several name the same account.
     *
     * @param mongoSession the MongoDB client session
     * @return the granted quota per account; empty when no authority is configured
     */
    public static Map<Target, Integer> readGranted(ClientSession mongoSession) {
        Set<String> authorities = AgentFilter.getQuotaAuthorities();
        if (authorities.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Target, Integer> granted = new HashMap<>();
        Map<Target, Ranking> winners = new HashMap<>();
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
                    Ranking ranking = new Ranking(creationTimeOf(np), ac);
                    for (QuotaGrant grant : extract(np)) {
                        Ranking current = winners.get(grant.target);
                        if (current == null || ranking.beats(current)) {
                            winners.put(grant.target, ranking);
                            granted.put(grant.target, grant.quota);
                        }
                    }
                }
            }
        }
        logger.info("Read {} granted quotas from {} quota authorities", granted.size(), authorities.size());
        return granted;
    }

    /**
     * The creation time a grant is ranked by, or null if it has none that can be read. The
     * registry stores a nanopublication whose timestamp does not parse, and reading it
     * throws; a grant like that must not take the trust cycle down with it, so it ranks as
     * one without a timestamp, behind every dated grant for the same account.
     */
    private static Calendar creationTimeOf(Nanopub np) {
        try {
            return SimpleTimestampPattern.getCreationTime(np);
        } catch (RuntimeException ex) {
            logger.warn("Quota grant {} has a malformed timestamp; ranking it as undated", np.getUri());
            return null;
        }
    }

    /**
     * Which of two grants for the same account counts: the later created one, and between
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
