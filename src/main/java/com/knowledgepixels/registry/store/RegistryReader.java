package com.knowledgepixels.registry.store;

import org.bson.Document;

import java.util.List;
import java.util.Map;

/**
 * Every read the registry's public HTTP API performs, as one interface, so that the pages
 * can be served from MongoDB or from a static, read-only backend alike.
 *
 * <p>Rows are {@link Document}s on purpose: the JSON the API serves is the relaxed
 * {@code Document.toJson()} rendering, and keeping the row type keeps that output
 * identical across backends. Numeric fields keep the Java types MongoDB hands back
 * ({@code Long} for positions and counters, {@code Integer} for the small counts,
 * {@code Double} for ratios), since the pages cast them.
 *
 * <p>A reader is bound to one request: it owns whatever session or connection it opened
 * and releases it on {@link #close()}.
 */
public interface RegistryReader extends AutoCloseable {

    // --- serverInfo and setting --------------------------------------------------------

    /**
     * All {@code serverInfo} entries, keyed by name (status, setupId, testInstance,
     * trustStateCounter, lastTrustStateUpdate, trustStateHash, coverageTypes, coverageAgents).
     */
    Map<String, Object> serverInfo();

    /**
     * One {@code serverInfo} entry, or null if it is not set.
     */
    Object serverInfoValue(String key);

    /**
     * Whether this registry is a test instance.
     */
    boolean isTestInstance();

    /**
     * One {@code setting} entry ({@code original} or {@code current}), or null.
     */
    Object settingValue(String key);

    // --- nanopubs ----------------------------------------------------------------------

    /**
     * The stored nanopub with the given artifact code, or null. The document carries
     * {@code _id}, {@code fullId}, {@code counter}, {@code pubkey}, {@code content} (TriG)
     * and {@code jelly} (a {@link org.bson.types.Binary}).
     */
    Document getNanopub(String artifactCode);

    /**
     * The highest load counter, or null when no nanopub is stored.
     */
    Long maxCounter();

    /**
     * The number of stored nanopubs; an estimate is acceptable (used for headers and metrics).
     */
    long nanopubCount();

    /**
     * The exact number of stored nanopubs (used for the registry info JSON).
     */
    long nanopubCountExact();

    /**
     * All artifact codes, latest first.
     */
    CloseableIterator<String> nanopubIdsByCounterDesc();

    /**
     * All artifact codes in ascending order, starting after {@code afterId} ("" for all).
     */
    CloseableIterator<String> nanopubIdsByIdAsc(String afterId);

    /**
     * The {@code limit} most recently loaded artifact codes, latest first.
     */
    List<String> latestNanopubIds(int limit);

    /**
     * All nanopubs with a counter greater than {@code afterCounter}, in counter order, as a
     * Jelly stream carrying the counter in the frame metadata.
     */
    JellyStream nanopubsAfterCounter(long afterCounter);

    // --- lists -------------------------------------------------------------------------

    /**
     * The lists of a pubkey (documents with {@code pubkey}, {@code type}, {@code maxPosition},
     * {@code status}).
     */
    List<Document> listsForPubkey(String pubkeyHash);

    /**
     * One list document, or null.
     */
    Document getList(String pubkeyHash, String typeHash);

    /**
     * All pubkey hashes that have lists.
     */
    List<String> pubkeys();

    /**
     * The entries of a list in position order (documents with {@code pubkey}, {@code type},
     * {@code position}, {@code np}, {@code checksum}, {@code invalidated}).
     */
    CloseableIterator<Document> listEntries(String pubkeyHash, String typeHash);

    /**
     * The position of the entry with the given checksum, or null.
     */
    Long listEntryPosition(String pubkeyHash, String typeHash, String checksum);

    /**
     * The nanopubs of the list entries after {@code afterPosition} (-1 for all), in position
     * order, as a Jelly stream.
     */
    JellyStream listEntriesAfterPosition(String pubkeyHash, String typeHash, long afterPosition);

    // --- trust state -------------------------------------------------------------------

    /**
     * All accounts, sorted by pubkey.
     */
    List<Document> accounts();

    /**
     * The accounts of an agent.
     */
    List<Document> accountsByAgent(String agentId);

    /**
     * The account of a pubkey that carries a quota (status loaded, toLoad or capped), or null.
     */
    Document quotaBearingAccount(String pubkeyHash);

    long accountCount();

    /**
     * All agents, highest total ratio first.
     */
    List<Document> agents();

    /**
     * One agent document, or null.
     */
    Document getAgent(String agentId);

    long agentCount();

    /**
     * The retained trust state snapshots without their account and edge arrays
     * ({@code _id} = hash, {@code trustStateCounter}, {@code createdAt}), newest first.
     */
    List<Document> trustStateSnapshots();

    /**
     * One full snapshot ({@code _id}, {@code trustStateCounter}, {@code createdAt},
     * {@code accounts}, optionally {@code edges}), or null.
     */
    Document trustStateSnapshot(String hash);

    long trustStateSnapshotCount();

    /**
     * The value a hash was computed from (a type URI or a pubkey), or null.
     */
    String unhash(String hash);

    @Override
    void close();

}
