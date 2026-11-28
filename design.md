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
- The list of a type holds the nanopublications of that pubkey that have that type, and the ones that invalidate them (see below). A nanopublication is checked for the type on every way onto such a list, also when the list is copied from a peer and while only the introduction and endorsement lists of a pubkey are kept: what does not have the type is stored, but not listed there
- To efficiently synchronize them, these lists keep at each position a checksum of the set of contained identifiers up to that point, which can be used to identify identical sets even when list ordering is different
- Each lists provides index-based lookup on positions (get n-th position in list), identifier (get position of RA... in list), and checksum (get position with checksum XX...)
- Entries are appended by reading the entry with the highest position and inserting at the next one; the unique index on the position arbitrates between concurrent writers, so every checksum is computed from its direct predecessor and the last checksum of a list is the checksum of its whole set (`lists.maxPosition` is a derived counter for displaying list sizes, raised after each insert)
- When loading from a peer, nanopubs are verified and stored in parallel but appended to the lists in the order the peer serves them, so that lists loaded from one another keep the same order and the checksums at the fallback positions used for skip-ahead can match, not only the final one. The ordered appends are computed in memory off each list's tail and written in batches of 100, in order, so a crash can shorten a chain but not punch a hole into it; if another writer appended to a list meanwhile, the affected entries are placed one by one off the actual end of the list

Invalidations (retractions, new versions):

- If a nanopublication invalidates another nanopublication, then the types of the latter also apply to the former, so an invalidating nanopublication is added also to all the lists where the invalidated nanopublication belongs
- This holds whichever of the two a registry receives first, and down a chain of versions: an invalidating nanopublication joins the lists the invalidated one is on when the invalidation is recorded, and any list the invalidated one is put on afterwards. Which nanopublications are on a list therefore does not depend on the order of arrival; only their positions do
- Registries provide backlink lookup to find such invalidating nanopublications for a given nanopublication identifier
- Applying an invalidation (recording it, marking the entries of the invalidated nanopublication, adding the invalidating one to its lists) is repeatable and happens every time the invalidating nanopublication is loaded, not only when it is first stored, so a registry that stopped in between makes up for it on the next load. An invalidation is on record once, enforced by a unique index
- An invalidation only has an effect for the pubkey that made it: it marks the entries of the invalidated nanopublication on that pubkey's lists, and it invalidates a trust edge only if that pubkey signed the nanopublication the edge is sourced from, so nobody can take back somebody else's endorsement. The root edges of an instance are sourced from its setting and are judged by the pubkey that signed the setting

Trusted agents:

- Trust is rooted in a setting nanopublication provided by the registry configuration
- The setting nanopublication provides a list of initial trusted agents (as ID/pubkey pairs)
- For the determination of trusted agents and their quotas, a few core types (introductions and endorsements of other agents) can be loaded for a given pubkey
- For endorsed agents, the core types can be loaded too
- This process is repeated as long as the link of an endorsed agent back to the initial trusted agents is strong enough (based on number of steps and number of other endorsements at each step)
- Based on the endorsement network of the partially loaded agents, it can be determined which agents should be considered trusted (based on the strength and number of endorsement chains)

Quotas:

- Quotas are computed per account from trust path ratios (stored as a `quota` field on each entry in the `accounts` collection)
- A quota configured explicitly for a pubkey in `REGISTRY_COVERAGE_AGENTS` overrides the computed one, and is what gets
  recorded, published and enforced — a trust ratio says how much an agent is trusted, not how much they publish, so
  high-volume publishers need this
- A quota can also be set by nanopublication (issue #220): a *quota grant* is a nanopublication of type `npx:QuotaGrant`
  (declared in its pubinfo with `npx:hasNanopubType`, which is what puts it on the list the grants are read from)
  whose assertion names an account — an agent (`npx:forAgent`) and one of its pubkeys (`npx:hasPublicKeyHash`, or
  `npx:hasPublicKey` with the full key) — and a quota (`npx:hasQuota`), signed with one of the keys listed in
  `REGISTRY_QUOTA_AUTHORITIES` (whitespace-separated pubkey hashes). A grant is published once and reaches every
  registry that lists its signer through the ordinary sync, where the explicit env var needs a configuration change
  and a restart on every host. The trust cycle applies the grants where it records the quotas: an explicit entry wins
  over a grant, a grant over the computed quota; where several current grants name the same account, the most
  recently created nanopublication wins; a retracted or superseded grant stops counting, which lets the one before it
  hold again, so a replacement should supersede its predecessor. A grant that is not plainly one — no agent, a pubkey
  hash that is not 64 lowercase hex characters, two quotas for one account in the same nanopublication — is ignored
  rather than resolved by statement order. A grant is not bounded by `REGISTRY_MAX_USER_QUOTA`, and a quota of 0
  shuts an account off. A registry restricted to some types (`REGISTRY_COVERAGE_TYPES`) only sees grants if
  `npx:QuotaGrant` is among them. See [QuotaGrant.java](src/main/java/com/knowledgepixels/registry/QuotaGrant.java)
- A grant sets a quota and approves nothing: it applies to the account of that agent and pubkey once the trust
  network approves it, and has no effect before that or for any other agent declaring the same key. Approval stays
  with introductions and endorsements, so a registry that does not compute the trust network applies no grants. The
  same holds for the authorities: being listed makes a key's grants count, but only an approved account is loaded in
  full from the peers, so an authority should be an agent the trust network approves (the trust cycle logs a warning
  for one that is not)
- A quota, however it was set (computed, explicit, or by a grant), is part of what a registry publishes about an
  account, and so of its trust state hash (see [Trust State Snapshots](#trust-state-snapshots)): a changed quota is
  published as a new trust state. Registries with different authorities or explicit quotas therefore differ in their
  trust state hash while agreeing in their trust network hash, which is why a registry shows the authorities it
  lists: as `quotaAuthorities` on its main page and in its info JSON, read from the running configuration
- For any trusted agent, if the number of nanopublications found on other registries is smaller than the agent's quota, all nanopublications are loaded
- Nothing is held to a quota unless `REGISTRY_ENFORCE_QUOTA=true`; by default the quotas are computed and published but
  not applied
- Under enforcement, an account that has reached its quota is marked `capped`: loading stops for it and further
  publishing is refused, but it stays a loaded, servable account and stays in the published trust state. A later cycle
  that computes a larger quota for it resumes loading
- Unapproved pubkeys are rationed rather than refused, so that the trust network stays enterable — see
  [Publishing responses](#publishing-responses)


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
- `Nanopub-Registry-Trust-State-Hash` — SHA256 hash of what this registry currently publishes as its trust state; the key of the current snapshot
- `Nanopub-Registry-Trust-Network-Hash` — SHA256 hash of the trust paths alone, comparable between registries; left out by a registry that has no trust paths
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

### Publishing responses

`POST /` answers `201` on success. A refusal puts the reason in the response **body** as plain text, and repeats it
in the HTTP reason phrase as a summary — the body is the authoritative copy, since HTTP/2 has no reason phrases and
many clients never surface them.

The status says what *kind* of refusal it is, which is what a publishing client acts on. nanopub-java treats `400`,
`409`, `413`, `415` and `422` as a permanent rejection of that nanopublication, retries `408`, `429` and `5xx` with
backoff, and moves on to the next registry on `401`, `403`, `404` and `405`:

| Status | Meaning | Causes |
|---|---|---|
| `400` | The body is not a nanopublication | malformed nanopub, unparseable RDF |
| `403` | This registry will not take it, another one may | types not covered, pubkey not authorized, quota exhausted, unapproved publishing switched off entirely, `npx:ProtectedNanopub` on a public instance |
| `413` | Over a limit every registry enforces | more than 1200 triples, over 1,000,000 bytes |
| `422` | Breaks a content rule every registry enforces | `dct:created` more than 60 s in the future, graph URI outside the nanopub URI, artifact code that does not verify, no valid signature |
| `429` | This registry will not take it *yet*, with `Retry-After` | the shared allowance for unapproved publishing is spent for this window |

The split that matters is `403` against `413`/`422`: a `403` is about *this* instance and should send the client to
another registry, while `413` and `422` are about the nanopublication itself and no registry would accept it. A `429`
is about *this moment*: the same nanopublication offered after `Retry-After` is accepted.

### Who may publish

With `REGISTRY_ENFORCE_QUOTA=false` (the default) any pubkey may publish anything. With enforcement on, a POST is
decided in this order (see [PublishPolicy.java](src/main/java/com/knowledgepixels/registry/PublishPolicy.java)):

1. **An approved pubkey** — one with a quota, whether from the trust network or from `REGISTRY_COVERAGE_AGENTS` —
   publishes until its quota is reached, and is refused with `403` after that.
2. **An agent introduction that an approved agent has already endorsed** is accepted in all situations, without
   touching any allowance. The endorsement (`npx:approvesOf`) names the one artifact code it approves of, and the
   introduction has to declare the pubkey it is signed with, so this grants exactly one specific nanopublication.
3. **Anything else from an unapproved pubkey** is accepted while the registry's shared allowance
   (`REGISTRY_UNAPPROVED_PUBLISH_QUOTA` per `REGISTRY_UNAPPROVED_PUBLISH_WINDOW`) has room, and answered with `429`
   once it is spent.

Rule 2 is what keeps the network enterable: an agent becomes approved by having an introduction endorsed, and an
introduction no registry accepts can never be endorsed. Rule 3 is the ordinary path — a new agent publishes a few
nanopublications before anyone has approved them — and its allowance is deliberately one budget for the whole
registry rather than one per pubkey, since pubkeys are free to make and a per-pubkey allowance would bound nothing.
A flood can therefore spend the allowance; what honest newcomers do then is rule 2, which a flood cannot close:
create the introduction, pass its URI to an approved agent, have them endorse it, then publish.

The status of an endorsement is deliberately not consulted for rule 2. An endorsement of an introduction this
registry does not hold yet cannot be resolved and is recorded as `discarded` — which is precisely the state of an
endorsement published ahead of the introduction it is for, the case rule 2 exists to serve.

A reverse proxy in front of the registry should keep to the same contract — in particular, a rate limit should answer
`429` with `Retry-After`, not nginx's default `503`.


## Task Workflow

Tasks are scheduled and executed sequentially from a `tasks` collection. The main flow:

1. `INIT_DB` → `LOAD_CONFIG` → `LOAD_SETTING` → `INIT_COLLECTIONS` → `SEED_TRUST_STATE` — bootstrap the registry
2. `LOAD_DECLARATIONS` → `EXPAND_TRUST_PATHS` → `LOAD_CORE` — iteratively load core nanopubs and build the trust network
3. `FINISH_ITERATION` — repeats step 2 until no more changes
4. `CALCULATE_TRUST_SCORES` → `AGGREGATE_AGENTS` → `ASSIGN_PUBKEYS` → `DETERMINE_UPDATES` → `FINALIZE_TRUST_STATE` → `RELEASE_DATA` → `PUBLISH_TRUST_STATE` — compute trust scores and quotas, swap in new data

Steps 2–4 can be skipped with `REGISTRY_ENABLE_TRUST_CALCULATION=false`, which makes `SEED_TRUST_STATE` jump straight to `FINALIZE_TRUST_STATE`. Useful when only explicit `REGISTRY_COVERAGE_AGENTS` pubkeys are needed.
5. `LOAD_FULL` → `RUN_OPTIONAL_LOAD` → `CHECK_NEW` — continuous cycle: load nanopubs for trusted accounts, then optionally load for non-approved pubkeys (one per cycle), then check peers for new nanopubs and discover new pubkeys, then loop back to `LOAD_FULL`. Optional loading can be disabled with `REGISTRY_ENABLE_OPTIONAL_LOAD=false`
6. `UPDATE` (by default 10min after the previous recalculation finished, `REGISTRY_UPDATE_INTERVAL`) → `INIT_COLLECTIONS` — triggers a trust state recalculation (steps 2–4); the `LOAD_FULL` cycle (step 5) continues running during updates

### Early updates

Waiting out the full interval means a new approval, introduction or revocation is published up to that long after this registry learned about it, so `TrustUpdateTrigger` pulls the queued `UPDATE` forward when trust-relevant data arrives.
Nanopubs entering through the ingest channels — peer sync, the legacy connector, the POST endpoint — are classified as they arrive: an `npx:approvesOf` or `npx:declaredBy`/`npx:introduces` assertion, or an invalidation of a nanopub that currently sources a trust edge, is something the next cycle would compute differently.
The trust calculation itself is unchanged; only when it runs is.

A cycle fetches the intro and endorsement list of every account from the peers, so triggering is bounded:

- **Floor** (`REGISTRY_UPDATE_MIN_INTERVAL`, default 2min): no two cycles ever start closer together than this, whatever arrives. It is stamped on the queued task as `not-before-floor` when the task is scheduled, so a second trigger cannot compute a floor from an already-triggered task and walk the update arbitrarily close.
- **Debounce**: arrivals set a single flag and the trigger moves the one queued `UPDATE` rather than scheduling another, so a burst collapses into one early update. While a cycle is running there is no queued `UPDATE` to move — it left the queue when the cycle started — and the request is kept for the next round rather than dropped.
- **Jitter** (`REGISTRY_UPDATE_TRIGGER_JITTER`, default 30s): registries feed each other, so an unjittered trigger would have every registry that sees the same publication recompute at the same moment.

Misclassifying is cheap either way: a missed arrival waits for the regular interval, and a false positive costs one cycle that finds the trust state unchanged and publishes nothing.

Some tasks run inside a MongoDB transaction, so that their writes commit atomically with their own removal from the queue; `Task.runAsTransaction()` says which, and defaults to true.
These are the short tasks: `INIT_DB`, `LOAD_CONFIG`, `LOAD_SETTING`, `SEED_TRUST_STATE`, `FINISH_ITERATION`, `FINALIZE_TRUST_STATE`, `PUBLISH_TRUST_STATE` and `UPDATE`.
A task opts out when it does network I/O or catalog operations (creating indexes, renaming or dropping collections), neither of which belongs in a transaction.
Several pairs above exist for exactly that reason — `INIT_COLLECTIONS` prepares and fetches so that `SEED_TRUST_STATE` can write transactionally, and `RELEASE_DATA` renames so that `PUBLISH_TRUST_STATE` can.
The tasks that go through every account of the trust network opt out too (`EXPAND_TRUST_PATHS`, `CALCULATE_TRUST_SCORES`, `AGGREGATE_AGENTS`, `ASSIGN_PUBKEYS`, `DETERMINE_UPDATES`): they take seconds, growing with the network, and MongoDB aborts a transaction after 60 seconds by default.
Transactions need MongoDB to run as a replica set, as the compose files start it (`--replSet rs0`).
On a standalone server the registry logs an error at startup and runs every task without a transaction.

A task that stays non-transactional is re-executed after a crash and must be idempotent, since its writes are not atomic with its own removal from the queue.
On startup, a trust state cycle (steps 2–4) interrupted that way is therefore not resumed but restarted: the queued cycle tasks are dropped and `INIT_COLLECTIONS` is scheduled afresh, which discards the `_loading` collections and rebuilds them.
The exception is the tail of the cycle, `RELEASE_DATA` and `PUBLISH_TRUST_STATE`: the computation is complete by then and both are idempotent, so they are kept and left to finish rather than recomputed.
The loading loop (step 5) is kept to a single copy instead: a loop task stopped after it had queued its successor is still queued next to that successor, and both would carry the loop on, polling the peers twice as often.
So on startup, when more than one loop task is queued, the one due first is kept and the others are dropped ([#250](https://github.com/knowledgepixels/nanopub-registry/issues/250)).

A task that fails while the process keeps running stays first in the queue and is run again right away.
A transactional task then starts from where the failed run started, as nothing it wrote was kept.
Any other task runs on top of whatever it wrote before failing, so the non-transactional tasks of the trust cycle are written such that running them again completes a partial run ([#238](https://github.com/knowledgepixels/nanopub-registry/issues/238)):
their writes leave or replace what an earlier run wrote, they take their work by a status that a finished piece of work no longer has (an account whose trust path is marked as expanded is not expanded again), and they queue their successor only if it is not queued already.
`SEED_TRUST_STATE` is written the same way, for a MongoDB that cannot run transactions.

See [Task.java](src/main/java/com/knowledgepixels/registry/Task.java) (`recoverInterruptedCycle`, `dropDuplicateLoadingLoops`).


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
- **Invalidating nanopubs** are the exception: a nanopub that invalidates one the registry holds, signed with the same key, is accepted whatever its own types. A retraction has the type `npx:retracts` and not the type of what it retracts; without the exception, a restricted registry would keep listing retracted nanopubs as valid. It goes on the lists of the nanopub it invalidates, and gets no list for its own uncovered types
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
      trustNetworkHash: 5d6e7f...  (absent on a registry without trust paths)
      accountsNewlyToLoad: 1  (how many accounts the current cycle has handed to the loader)
      trustStateHeldBackAt: 2024-03-16T...  (the cycle whose changed trust state was held back, if the last one was)
      coverageAgents: viaSetting  (not stored; reported from the running configuration, REGISTRY_COVERAGE_AGENTS)
      coverageTypes: all  (not stored; reported from the running configuration, REGISTRY_COVERAGE_TYPES)
      globalQuota: 1000000  (not stored; configured per instance as REGISTRY_GLOBAL_QUOTA, to be moved to setting)
    hashes:
      { hash*:a83, value*:4e8d9x... }
      ...
    lists:
      { pubkey**:a83, type**:_all_, status^:loading, maxPosition:1423 }
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
      Note on ratio vs MIN_TRUST_PATH_RATIO: a path is born as `type:extended` at its full
      ratio `(parent.ratio * 0.9) / nAgents / nPubkeys`, and the gate at `LOAD_CORE`
      compares this value to `MIN_TRUST_PATH_RATIO`. After the next depth's
      `EXPAND_TRUST_PATHS` runs, the same row is rewritten to `type:primary` with only 10%
      of its ratio retained (the other 90% having been distributed to its children). The
      stored `primary` ratio can therefore legitimately be up to 10x below
      `MIN_TRUST_PATH_RATIO` without indicating a gate leak; the threshold is enforced on
      the pre-retention extended value.
      Note on scoring: `CALCULATE_TRUST_SCORES` sums, per account, the ratios of all paths
      ending there. A `primary` path counts at its stored (already retained) ratio. An
      `extended` path is never expanded, so its stored ratio is the full incoming value; it
      is multiplied by the same self share (10%) at scoring time, so that every path counts
      the share that stays with the account (issue #196). The path count orders paths by this
      effective ratio.
    peerState:
      { id#:'https://example.com/peer/', setupId:1332309348, seqNum:42000, loadCounter:42000, lastChecked:1710672000000 }
      ...
    tasks:
      { notBefore^:1710672000000, action^:CHECK_NEW }
      { notBefore^:1710672000100, action^:LOAD_FULL }
    publishBudget:
      { id#:'unapproved', windowStart:2026-10-23T12:00:00Z, count:17, granted:true }
      (one document; the shared allowance for publishing by unapproved pubkeys. `granted`
      is the outcome of the last request, written by the same update that counts it)

During trust state computation, intermediate `_loading` collections (`endorsements_loading`, `accounts_loading`, `agents_loading`, `trustPaths_loading`) are used and then renamed to replace the live collections in `RELEASE_DATA`. They are always built from scratch: `INIT_COLLECTIONS` drops whatever is still there before it starts inserting, so a cycle left half-built by a shutdown is discarded rather than added to.

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


## Trust State Snapshots

A registry reports two hashes, for two different questions:

- The **trust network hash** (`trustNetworkHash`, header `Nanopub-Registry-Trust-Network-Hash`) is the input side: who endorses whom.
  It is taken over the trust paths and nothing else, so registries running the same setting report the same value once they hold the same introductions and endorsements, whatever their local configuration.
  This is the value to compare between registries.
  It says that the paths are equal, not that the scores derived from them are: registries on different versions can agree on the network and still compute different ratios.
- The **trust state hash** (`trustStateHash`, header `Nanopub-Registry-Trust-State-Hash`) is the outcome on one registry: the accounts as it publishes them.
  It is taken over the content of the snapshot described below, so it depends on the network and also on what is local to the registry: explicit quotas, the quota authorities it lists, quota enforcement, and how far accounts have been loaded.
  This is the value consumers watch, and it is not meant to be compared between registries.

The trust state can change while the network stays the same, for example when a quota is granted or an account finishes loading.
A change of the network usually changes the trust state too, though not always in the same cycle: a newly endorsed account enters the network at once, and the trust state when it is loaded.

Downstream consumers (e.g. Nanopub Query) mirror the registry's trust state as immutable snapshots keyed by `trustStateHash`.
To let them load a given trust state atomically and side-by-side with earlier states, the registry persists a structured snapshot each time the hash transitions.

Snapshot writes happen inside `PUBLISH_TRUST_STATE`, which `RELEASE_DATA` queues as the very next task after it has renamed the `_loading` collections into place, so that what is published is the state the cycle computed and not one the loading chain has meanwhile worked on.
The task reads the accounts and edges once, takes the trust state hash over them, and compares it with the one recorded.
Because the hash is that of the content itself, anything a snapshot contains is covered by it: a changed status, ratio, quota, name or edge is a new trust state, with no list of cases to keep complete.
The snapshot is written before the new hash is recorded, so a consumer that sees the hash finds the snapshot.

Two situations publish nothing although the content differs from what is recorded:

- On a fresh registry, nothing is published until a cycle finds the initial full load complete, so that the first trust state is not a near-empty one.
- When a cycle has handed newly approved accounts to the loader, and publishing would add no account to the recorded trust state, a changed state is held back for that one cycle.
  Such an account is left out until it is loaded, but the scores of the accounts next to it move at once, because an endorser's share is divided among all it endorses.
  Published as they come, that would be two trust states for one event, and consumers rebuild on each.
  The next cycle publishes the account and the moved scores together.
  An account that has been loaded is never kept waiting by this: a state that adds an account is published at once, so each account appears in the cycle after the one that approved it, also while others keep arriving.
  Nor does it happen two cycles in a row, and an account holds things back only in the cycle that hands it to the loader: one that takes long to load, or that the loader passes over, delays nothing after that.

For each hash transition, a single document is upserted into the `trustStateSnapshots` collection:

    trustStateSnapshots:
      { _id:'<trustStateHash>', trustStateCounter:42, createdAt:'2026-04-15T...', trustNetworkHash:'5d6e7f...',
        accounts: [ { pubkey, agent, name, nameCreatedAt, introNanopub, status, depth, pathCount, ratio, quota }, ... ],
        edges: [ { fromAgent, fromPubkey, toAgent, toPubkey, viaNanopub }, ... ] }

The `accounts` array omits the `$` sentinel and the accounts still in the internal `toLoad` status, and projects only fields that are outputs of the trust computation (volatile fields like the per-pubkey nanopub `count` are excluded).
The `edges` array holds the non-invalidated endorsements between published accounts.
Both are stored in the order they are hashed in: accounts by agent and pubkey, edges by their endpoints and source nanopublication.
`createdAt`, `trustStateCounter` and `trustNetworkHash` describe the snapshot and are not part of the hash.
Retention is capped at the 100 most recent snapshots; older entries are pruned in the same task.

An account can have several introductions. Its `name`, `nameCreatedAt` and `introNanopub` all come from one of them, chosen by a rule that does not depend on the order they are met in: an introduction with a name before one without, then the one created later (a dated one before an undated one), then the one with the smaller URI. An introduction whose timestamp cannot be read counts as undated.

The hash is taken over a text with one line per account and per edge, each a JSON array of the values in a fixed order, after a first line naming the format (`trust-state-1`).
See `calculateTrustStateHash` in [RegistryDB.java](src/main/java/com/knowledgepixels/registry/RegistryDB.java) for the exact form.

Consumers read snapshots via:

    GET /trust-state/<trustStateHash>.json

which returns an envelope `{ trustStateHash, trustStateCounter, createdAt, trustNetworkHash, accounts, edges }` with `Cache-Control: public, immutable, max-age=31536000`.
Snapshots recorded by older versions lack `trustNetworkHash`, and the oldest `edges`.
Returns `404` if the hash has been pruned or never existed.

Change detection uses the `Nanopub-Registry-Trust-State-Hash` response header (set on every response).
A typical consumer workflow:

1. `HEAD /` → read `Nanopub-Registry-Trust-State-Hash`
2. If the hash differs from the last seen, `GET /trust-state/<new_hash>.json` and load alongside the previous snapshot

See also [TrustStatePage.java](src/main/java/com/knowledgepixels/registry/TrustStatePage.java) and the snapshot writer in [Task.java](src/main/java/com/knowledgepixels/registry/Task.java) (`PUBLISH_TRUST_STATE`).
