# Nanopub Registry Design

## General Considerations

### Principles

- Registries discriminate which nanopublications to store based on the creator/pubkey and their types
- Registries are configured with quotas per creator/pubkey
- Registries publicly communicate which creator/pubkey and which types they cover, and the applied quotas
- Registries regularly check other registries, and load any nanopublications that have a matching creator/pubkey/type and have available quota


### General Behavior

Content store:

- Each registry stores and provides the content of its nanopublications with a key-value-based lookup with the artifact code (RA...) as key

Identifier lists:

- Each registry keeps various public add-only lists of identifiers that refer to nanopublications in its store
- Each list covers just one pubkey, and can cover either one specific type (e.g. BiodivNanopubs) or all types
- To efficiently synchronize them, these lists keep at each position a checksum of the set of contained identifiers up to that point, which can be used to identify identical sets even when list ordering is different
- Each lists provides index-based lookup on positions (get n-th position in list), identifier (get position of RA... in list), and checksum (get position with checksum XX...)

Invalidations (retractions, new versions):

- If a nanopublication invalidates another nanopublication, then the types of the latter also apply to the former, so an invalidating nanopublication is added also to all the lists where the invalidated nanopublication belongs
- Registries provide backlink lookup to find such invalidating nanopublications for a given nanopublication identifier

Trusted agents:

- Trust is rooted in a setting nanopublication provided by the registry configuration
- The setting nanopublication provides a list of initial trusted agents (as ID/pubkey pairs)
- For the determination of trusted agents and their quotas, a few core types (introductions and endorsements of other agents) can be loaded for a given pubkey
- For endorsed agents, the core types can be loaded too
- This process is repeated as long as the link of an endorsed agent back to the initial trusted agents is strong enough (based on number of steps and number of other endorsements at each step)
- Based on the endorsement network of the partially loaded agents, it can be determined which agents should be considered trusted (based on the strength and number of endorsement chains)

Quotas:

- Quotas are computed per account from trust path ratios (stored as a `quota` field on each entry in the `accounts` collection)
- For any trusted agent, if the number of nanopublications found on other registries is smaller than the agent's quota, all nanopublications are loaded


### Complexity

- Storing nanopublications in the key-value-based content store is _linear_ with respect to the number of nanopubliations
- Retrieval time of a single nanopublication by ID is _constant_
- Checking for other registries that host the nanopublications of a particular creator/pubkey/type is _linear_ with respect to the number of registries in the network, and _constant_ with proper local caching
- Loading all nanopublications of a given creator/pubkey/type from a registry is _linear_ with respect to the number of nanopublications to be loaded
- Checking whether a nanopublication list of given creator/pubkey/type is different from that of another registry (except for ordering) is _constant_ due to checksum
- Determining the difference of the list of given creator/pubkey/type with that of another registry is _linear_ with respect to the lengths of the lists in the worst case, and likely _sub-linear_ in practice with the use of checksums (in particular if registries make an effort to preserve the ordering of lists found on other registries)


### Scalability

- Registries can restrict themselves to small subsets of creators/pubkeys and/or types, and therefore the global set of nanopublications can be distributed across as many servers as needed
- Any subset defined by creator/pubkey/type can be efficiently located and loaded (by other registries, query services, and other tools)
- Approval of new trusted agents can be done by any existing trusted agent (with sufficiently strong trust chains), and therefore doesn't depend on a single bottleneck
- Arbitrarily large datasets can be published, by setting up dedicated registry instances and using dedicated pubkeys if quotas of existing general registries don't suffice


### Robustness

- Inclusion in a registry requires signature with a pubkey that is considered trusted, which prevents flooding with nanopublications that are not signed with one of these pubkeys
- If a pubkey is compromised, the quota limits the possible flooding
- Once a compromised pubkey is identified as such, the respective nanopublications can be efficiently unloaded from a registry, as all lists are clearly separated by pubkey


## HTTP API

All responses include the following headers:

- `Nanopub-Registry-Status` — registry status (`launching`, `coreLoading`, `coreReady`, `updating`, `ready`)
- `Nanopub-Registry-Setup-Id` — unique ID, changes on reset
- `Nanopub-Registry-Trust-State-Counter` — incremented on trust state changes
- `Nanopub-Registry-Last-Trust-State-Update` — ISO 8601 timestamp of last trust state update
- `Nanopub-Registry-Trust-State-Hash` — SHA256 hash of the current trust state
- `Nanopub-Registry-SeqNum` — max sequence number from nanopubs collection (monotonic cursor for sync, may have gaps)
- `Nanopub-Registry-Nanopub-Count` — approximate number of nanopubs (via `estimatedDocumentCount`)
- `Nanopub-Registry-Load-Counter` — same as SeqNum (transition compatibility for old peers)
- `Nanopub-Registry-Test-Instance` — `true` if this is a test instance
- `Nanopub-Registry-Coverage-Types` — comma-separated type hashes this registry covers (absent if all types covered)

Endpoints:

- `GET /` — registry info (HTML or JSON via `/.json`)
- `GET /list` — all accounts (JSON)
- `GET /list/{pubkeyHash}` — all lists for a pubkey (JSON)
- `GET /list/{pubkeyHash}/{typeHash}.json` — list entries with positions and checksums (JSON)
- `GET /list/{pubkeyHash}/{typeHash}.jelly` — nanopubs in a list (Jelly binary stream)
- `GET /pubkeys` — all pubkey hashes (JSON)
- `GET /agent/{agentId}` — agent info (JSON)
- `GET /agents` — all agents (JSON)
- `GET /np/{artifactCode}` — single nanopub (TriG, Jelly, JSON-LD, NQ, XML, or HTML)
- `POST /` — submit a nanopub (TriG or other RDF format)

See [MainVerticle.java](src/main/java/com/knowledgepixels/registry/MainVerticle.java).


## Task Workflow

Tasks are scheduled and executed sequentially from a `tasks` collection. The main flow:

1. `INIT_DB` → `LOAD_CONFIG` → `LOAD_SETTING` → `INIT_COLLECTIONS` — bootstrap the registry
2. `LOAD_DECLARATIONS` → `EXPAND_TRUST_PATHS` → `LOAD_CORE` — iteratively load core nanopubs and build the trust network
3. `FINISH_ITERATION` — repeats step 2 until no more changes
4. `CALCULATE_TRUST_SCORES` → `AGGREGATE_AGENTS` → `ASSIGN_PUBKEYS` → `DETERMINE_UPDATES` → `FINALIZE_TRUST_STATE` → `RELEASE_DATA` — compute trust scores and quotas, swap in new data

Steps 2–4 can be skipped with `REGISTRY_ENABLE_TRUST_CALCULATION=false`, which makes `INIT_COLLECTIONS` jump straight to `FINALIZE_TRUST_STATE`. Useful when only explicit `REGISTRY_COVERAGE_AGENTS` pubkeys are needed.
5. `LOAD_FULL` → `RUN_OPTIONAL_LOAD` → `CHECK_NEW` — continuous cycle: load nanopubs for trusted accounts, then optionally load for non-approved pubkeys (one per cycle), then check peers for new nanopubs and discover new pubkeys, then loop back to `LOAD_FULL`. Optional loading can be disabled with `REGISTRY_ENABLE_OPTIONAL_LOAD=false`
6. `UPDATE` (hourly) → `INIT_COLLECTIONS` — triggers a trust state recalculation (steps 2–4); the `LOAD_FULL` cycle (step 5) continues running during updates

See [Task.java](src/main/java/com/knowledgepixels/registry/Task.java).


## Updating from peers

The `CHECK_NEW` task invokes `RegistryPeerConnector.checkPeers()`, which iterates over peer registries (in random order) and synchronizes nanopubs. Per-peer state is tracked in the `peerState` collection.

**Preparation:**
- Track each peer's `setupId` and `seqNum` in the `peerState` collection
- `seqNum` is the maximum sequence number from the peer's nanopubs collection (monotonic, may have gaps due to batch allocation)
- Peer URLs come from the `REGISTRY_PEER_URLS` environment variable

**Per-peer sync steps:**

1. Send HTTP HEAD request to peer URL; extract `Nanopub-Registry-Status`, `Nanopub-Registry-Setup-Id`, and `Nanopub-Registry-SeqNum` (or `Nanopub-Registry-Load-Counter` for old peers) from response headers.
2. Skip peers with non-ready status (only `ready` and `updating` are accepted).
3. If `setupId` changed since last check, delete stored peer state and treat as new.
4. If `seqNum` is unchanged, skip (nothing new).
5. **Incremental sync**: fetch recent nanopubs via `/nanopubs.jelly?afterSeqNum=X`. Nanopubs of uncovered types are filtered client-side.
6. **Discover pubkeys**: fetch `/pubkeys.json` from the peer and create `encountered` intro lists for any unknown pubkeys, so they can be loaded later via `RUN_OPTIONAL_LOAD`.
7. Update peer state with current `setupId` and `seqNum`.

**Not yet implemented optimizations:**
- Per-pubkey/type position tracking for incremental sync (currently downloads full lists)
- Checksum-based binary search to avoid downloading full lists when only a few nanopubs are new
- Throttled loading for non-approved pubkeys during peer sync


## Type-Based Coverage

Registries can restrict which nanopub types they store via the `REGISTRY_COVERAGE_TYPES` environment variable (whitespace-separated type URIs). Core types (introductions, endorsements) are always covered regardless of the setting, since they are needed for trust path computation.

When coverage is restricted:
- **POST handler** rejects nanopubs whose types are not covered
- **LOAD_FULL** and **RUN_OPTIONAL_LOAD** fetch per covered type from peers (with checksum-based skip-ahead), instead of fetching the `$` (all types) list
- **Peer sync** (`loadRecentNanopubs`) filters uncovered nanopubs client-side
- **`loadNanopubVerified`** only creates individual type lists for covered types when expanding `$`
- The `$` list means "all covered types" — it always exists but only contains nanopubs of covered types
- The `Nanopub-Registry-Coverage-Types` response header advertises coverage to peers

Configuration example:
```
REGISTRY_COVERAGE_TYPES=http://example.org/TypeA http://example.org/TypeB
```

When unset, all types are covered (default, no behavioral change).


## Data Structure

Field type legend: primary# / unique* / combined-unique** / indexed^ (all with prefix lookup)

    serverInfo:
      setupId: 1332309348
      status: ready
      testInstance: false
      lastTrustStateUpdate: 2024-03-16T...
      lastUptodate: 20240317-...  (not yet implemented; to be added with peer sync)
      trustStateCounter: 1423293
      trustStateHash: 7a8b9c...
      coverageAgents:_viaSetting_
      coverageTypes:_all_
      globalQuota: 1000000  (not yet implemented; currently a hardcoded constant, to be moved to setting)
    hashes:
      { hash*:a83, value*:4e8d9x... }
      ...
    lists:
      { pubkey**:a83, type**:_all_, status^:loading }
      ...
    listEntries:
      { pubkey**:a83, type**:_all_, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:2, np**:RA..., checksum**:XX... }
      ...
      { pubkey**:a83, type**:intro, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:2, np**:RA..., checksum**:XX... }
      ...
    invalidations:
      { invalidatingNp^:RA..., invalidatingPubkey^:a83, invalidatedNp^:RA... }
      ...
    nanopubs:
      { id#:RA..., fullId*:'https://w3id.org/np/RA12...', seqNum*:1423293, counter*:1423293, pubkey^:a83, content:'@prefix ...', jelly:<binary> }
      ...
    endorsements:
      { agent^:JohnDoe, pubkey^:a83, endorsedNanopub^:RA..., source^:RA..., status^:retrieved }
      ...
    setting:
      original: RA123...
      current: RA...
      bootstrap-services: [..., ...]
      status: loaded  (not yet implemented)
      linkThreshold: 0.000001  (not yet implemented; currently hardcoded as MIN_TRUST_PATH_RATIO)
    accounts:
      { pubkey**:a83, agent**:JohnDoe, ratio:0.1362, type^:base, depth:1, pathCount:3, quota:1362000, status:loaded }
      { pubkey**:d28, agent**:JohnDoe, ... }
      ...
    agents:
      { agent**:JohnDoe, totalRatio^:0.1732, accountCount:3, avgPathCount:2.5 }
      { agent**:SueRich, ... }
      ...
    trustEdges:
      { fromAgent^:$, fromPubkey^:$, toAgent^:JohnDoe, toPubkey^:a83, source^:RA..., invalidated:false }
      { fromAgent^:JohnDoe, fromPubkey^:a83, toAgent^:SueRich, toPubkey^:b55, source^:RA..., invalidated:false }
      { fromAgent^:SueRich, fromPubkey^:b55, toAgent^:EveBlue, toPubkey^:c43, source^:RA..., invalidated:false }
      ...
    trustPaths:
      { id#:'JohnDoe>a83', depth^:1, agent^:JohnDoe, pubkey^:a83, ratio:0.01 }
      { id#:'SueRich>b55 JohnDoe>a83', depth^:2, agent^:JohnDoe, pubkey^:a83, ratio:0.009 }
      { id#:'BillSmith>d32 JoeBold>e83 AmyBaker>f02 JohnDoe>a83', depth^:4, agent^:JohnDoe, pubkey^:a83, ratio:0.00007 }
      { id#:'JohnDoe>d28', depth^:1, agent^:JohnDoe, pubkey^:d28, ratio:0.01 }
      ...
    peerState:
      { id#:'https://example.com/peer/', setupId:1332309348, seqNum:42000, loadCounter:42000, lastChecked:1710672000000 }
      ...
    tasks:
      { notBefore^:1710672000000, action^:CHECK_NEW }
      { notBefore^:1710672000100, action^:LOAD_FULL }

During trust state computation, intermediate `_loading` collections (`endorsements_loading`, `accounts_loading`, `agents_loading`, `trustPaths_loading`) are used and then renamed to replace the live collections in `RELEASE_DATA`.

See also [RegistryDB.java](src/main/java/com/knowledgepixels/registry/RegistryDB.java).


## Trust Paths

Every account has at most one primary path (`>`) leading to it:

    $ > A
    $ > A > X
    $ > A > Y
    $ > B
    $ > B > C
    $ > B > C > D

Extended paths add a single extended edge (`~`) to the end of a primary path that can reach any other account,
including those with their own primary paths, as long as they are not already part of the given path
(so no single path can visit the same account more than once).

    $ > A ~ B
    $ > A > X ~ D
    $ > B > C ~ A

These extended paths can themselves not be further extended.
Therefore, each account can only append its endorsements to the location in its primary path.
