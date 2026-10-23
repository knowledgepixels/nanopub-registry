package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.nanopub.Nanopub;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * Decides whether this registry accepts a POSTed nanopublication from a given pubkey.
 *
 * <p>
 * With quota enforcement on, an approved pubkey publishes up to its quota and no further.
 * An unapproved one is not simply turned away, because that would make the trust network
 * impossible to enter: an agent becomes approved by having their introduction endorsed, and
 * an introduction no registry accepts cannot be endorsed. Two ways in are therefore kept
 * open, in this order:
 *
 * <ol>
 *   <li><em>An introduction an approved agent has already endorsed</em> is accepted in all
 *       situations, without touching any budget. The endorsement names the one artifact code
 *       it approves of, so this grants exactly one specific nanopublication — nothing a
 *       flooder can reuse.</li>
 *   <li><em>Anything else from an unapproved pubkey</em> is accepted while the registry's
 *       shared {@link UnapprovedPublishBudget} has room in the current window. This is the
 *       ordinary path: a new agent publishes a few nanopublications before anyone has
 *       approved them, and the trust network catches up.</li>
 * </ol>
 *
 * <p>
 * Under a flood the budget runs out and path 2 closes until the window resets, which leaves
 * path 1: create the introduction, pass its URI to someone already approved, have them
 * endorse it, then publish. That detour is the cost of a storm, and it is the reason path 1
 * does not depend on the budget.
 */
public final class PublishPolicy {

    private static final Logger logger = LoggerFactory.getLogger(PublishPolicy.class);

    /**
     * The pubkey recorded on the endorsements that come from the setting nanopublication
     * itself, rather than from an agent this registry has loaded.
     */
    private static final String SETTING_PUBKEY = "$";

    private PublishPolicy() {
    }

    /**
     * Checks whether a nanopublication may be published here, and throws the refusal if not.
     *
     * @param mongoSession the MongoDB client session
     * @param np           the nanopublication being published
     * @param pubkeyHash   the hash of the pubkey it is signed with, already verified
     * @throws NanopubRejectedException if this registry does not accept it
     */
    public static void check(ClientSession mongoSession, Nanopub np, String pubkeyHash) {
        // False exactly when enforcement is on and this pubkey has no quota, so with
        // enforcement off both branches below fall through and nothing is refused.
        if (!AgentFilter.isAllowed(mongoSession, pubkeyHash)) {
            if (isEndorsedIntro(mongoSession, np, pubkeyHash)) {
                logger.info("Accepting introduction {} from unapproved pubkey {}: an approved agent has endorsed it",
                        np.getUri(), pubkeyHash);
                return;
            }
            UnapprovedPublishBudget.Decision decision = UnapprovedPublishBudget.tryConsume(mongoSession);
            if (!decision.granted()) {
                String notApproved = "pubkey " + pubkeyHash + " is not approved on this registry";
                String theWayIn = "have an approved agent endorse an introduction for this pubkey";
                if (decision.retryAfterSeconds() > 0) {
                    // The allowance is spent for this window only, so the same nanopublication
                    // offered later gets in: ask for patience rather than send the client away.
                    throw new NanopubRejectedException(NanopubRejectedException.TOO_MANY_REQUESTS,
                            notApproved + " and the shared allowance for unapproved publishing is spent"
                                    + " for now; either retry later or " + theWayIn,
                            decision.retryAfterSeconds());
                }
                // No allowance at all on this instance, so waiting would not help.
                throw new NanopubRejectedException(NanopubRejectedException.FORBIDDEN,
                        notApproved + ", which takes nothing from unapproved pubkeys; " + theWayIn);
            }
            logger.info("Accepting {} from unapproved pubkey {} out of the shared allowance",
                    np.getUri(), pubkeyHash);
            return;
        }
        if (AgentFilter.isOverQuota(mongoSession, pubkeyHash)) {
            // A lifetime cap on this registry, not a rate limit, so 403 (try another
            // registry) rather than 429 (come back later).
            throw new NanopubRejectedException(NanopubRejectedException.FORBIDDEN,
                    "quota exceeded for pubkey " + pubkeyHash);
        }
    }

    /**
     * Whether this nanopublication is an introduction for the pubkey it is signed with, which
     * an approved agent has already endorsed.
     *
     * <p>
     * Both halves matter. The nanopublication has to declare the signing pubkey, so that an
     * endorsement of someone's introduction cannot be spent by a third party. And the
     * endorsement has to come from a pubkey that currently has a quota, which is what being
     * approved amounts to.
     *
     * <p>
     * The status of the endorsement row is deliberately not looked at. A row is marked
     * {@code discarded} when the nanopublication it endorses could not be resolved to an
     * agent introduction — which is precisely the state of an endorsement published ahead of
     * the introduction it is for, the case this path exists to serve.
     */
    static boolean isEndorsedIntro(ClientSession mongoSession, Nanopub np, String pubkeyHash) {
        String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        if (artifactCode == null) {
            return false;
        }
        IntroNanopub intro = new IntroNanopub(np);
        if (intro.getUser() == null) {
            logger.debug("Nanopub {} is not an agent introduction; no endorsement can apply to it", np.getUri());
            return false;
        }
        boolean declaresSigningKey = false;
        for (KeyDeclaration kd : intro.getKeyDeclarations()) {
            // A declaration without a key hashes to nothing; Utils.getHash would throw on it.
            if (kd.getPublicKeyString() == null) {
                continue;
            }
            if (pubkeyHash.equals(Utils.getHash(kd.getPublicKeyString()))) {
                declaresSigningKey = true;
                break;
            }
        }
        if (!declaresSigningKey) {
            logger.debug("Introduction {} does not declare the pubkey {} it is signed with", np.getUri(), pubkeyHash);
            return false;
        }
        try (MongoCursor<Document> endorsements = collection("endorsements")
                .find(mongoSession, new Document("endorsedNanopub", artifactCode)).cursor()) {
            while (endorsements.hasNext()) {
                String endorser = endorsements.next().getString("pubkey");
                if (endorser == null) {
                    continue;
                }
                if (SETTING_PUBKEY.equals(endorser)) {
                    // Endorsed by this registry's own setting, which is where trust is rooted.
                    return true;
                }
                if (AgentFilter.getQuota(mongoSession, endorser) >= 0) {
                    return true;
                }
                logger.debug("Introduction {} is endorsed by {}, which is not approved here", np.getUri(), endorser);
            }
        }
        return false;
    }

}
