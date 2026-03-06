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
- `Nanopub-Registry-Load-Counter` — max counter value from nanopubs collection
- `Nanopub-Registry-Test-Instance` — `true` if this is a test instance

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
5. `UPDATE` → `LOAD_FULL` — load all nanopubs for trusted accounts
6. `CHECK_MORE_PUBKEYS` → `RUN_OPTIONAL_LOAD` → `CHECK_NEW` — discover new pubkeys from peers, load new nanopubs, then loop back to `LOAD_FULL`

See [Task.java](src/main/java/com/knowledgepixels/registry/Task.java).


## Updating from peers

To update nanopublications from peer Nanopub Registries, the steps below are followed. A Nanopub Registry prepares itself for these updates like this:

- Keep a list of peer services, together with info from the last time they were visited, most importantly `setupId`, `stateCounter`, and `status`.
- (`stateCounter` is roughly the total number of nanopublication load events that have ever occurred on this instance, including those for pubkeys that have been unloaded since; but this still needs some proper write-up/specification)
- This list of peer services includes the services listed in the settings as well as services approved by approved agents (to be specified how this works exactly).
- Keep info also about the specific pubkey/type lists that were visited at these peer services, most importantly the position up to which we had checked and loaded all nanopublications

For a registry to update its nanopublications, it iterates over the list of peer services and performs these steps on each of them:

1. Retrieve the basic info of the chosen peer service, i.e. `setupId`, `stateCounter`, and `status`.
2. If we have info about this peer service from an earlier request, but the `setupId` is different, this means that the service has been reset in the meantime and our previous info about it is no longer valid. So, we delete the old info and treat it as an unknown service.
3. If `setupId` and `stateCounter` both haven't changed since our last request, then there is nothing new on this peer service, and we can abort this process and move to the next peer service.
4. If `status` is `loading` (or other non-ready status; to be specified), we ignore this instance for now and abort this process and move to the next peer service.
5. If `stateCounter` has increased since the last request but only by a relatively small amount (given by a threshold value still to be determined, e.g. 100 or 1000), request these nanopublications and load them to the respective lists. Then we abort this process and move to the next peer service.
6. If `stateCounter` has increased by more, we iterate over all our pubkeys to ask the peer service about them.
7. Retrieve info about the specific pubkey list (with type = `$` standing for "all types"; more specific algorithms to be determined for the cases where services store only certain types), most importantly the maximum position (= size of list) and the overall checksum
8. If our list has the same checksum (and therefore same size), there is nothing new and we can move on with checking the next pubkey.
9. We calculate the maximum number of unknown nanopublications in the peer list, taking into account the info we have from any previous request (e.g. if the peer list has size 13, and we had checked the nanopublications up to position 5 the last time, then the maximum number of unknown nanopublications is 8)
10. If the maximum number of unknown nanopublications is small enough (given by a threshold value still to be determined, e.g. 100 or 1000), we request all nanopublications after the last position one by one, and add unknown ones to our list too.
11. If the maximum number of unknown nanopublications is larger, then we try to find a position up to which both lists have identical nanopublications by checking whether checksums we have in our list occur in the peer list too (this can be done with individual requests or bulk ones of e.g. 100 checksums; to be defined)
12. If we find a match, we update our position up to which we know that all nanopublications are loaded to the found position. If the maximum number of unknown nanopublications is now small enough, we load the nanopublications one by one as for Step 5.
13. If we don't find a matching checksum or the maximum number of unknown nanopublications is still too large, we check a defined number of positions at the peer list, but then stop and leave this for later. The idea is that we are hoping that we have better luck at other peers loading the missing nanopublications (because they might have ordered them in a way that is more similar to our lists, thereby increasing the chances of identical checksums).


## Data Structure

Field type legend: primary# / unique* / combined-unique** / indexed^ (all with prefix lookup)

    serverInfo:
      setupId: 1332309348
      status: ready
      lastTrustStateUpdate: 2024-03-16T...
      lastUptodate: 20240317-...  (not yet implemented; to be added with peer sync)
      coverageAgents:_viaSetting_
      coverageTypes:_all_
      globalQuota: 1000000  (not yet implemented; currently a hardcoded constant, to be moved to setting)
      stateCounter: 1423293
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
      { id#:RA..., fullId*:'https://w3id.org/np/RA12...', counter*:1423293, pubkey^:a83, content:'@prefix ...', jelly:<binary> }
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
      { pubkey**:a83, agent**:JohnDoe, ratio:0.1362, type^:base, paths:3, independentPaths:3, quota:1362000, status:loaded }
      { pubkey**:d28, agent**:JohnDoe, ... }
      ...
    agents:
      { agent**:JohnDoe, totalRatio^:0.1732, accountCount:3, avgPathCount:2.5 }
      { agent**:SueRich, ... }
      ...
    trustEdges:
      { fromAgent^:$, fromPubkey^:$, toAgent^:JohnDoe toPubkey^:a83, source^:RA... }
      { fromAgent^:JohnDoe, fromPubkey^:a83, toAgent^:SueRich toPubkey^:b55, source^:RA... }
      { fromAgent^:SueRich, fromPubkey^:b55, toAgent^:EveBlue toPubkey^:c43, source^:RA... }
      ...
    trustPaths:
      { id#:'JohnDoe>a83', depth^:1, agent^:JohnDoe, pubkey^:a83, ratio:0.01 }
      { id#:'SueRich>b55 JohnDoe>a83', depth^:2, agent^:JohnDoe, pubkey^:a83, ratio:0.009 }
      { id#:'BillSmith>d32 JoeBold>e83 AmyBaker>f02 JohnDoe>a83', depth^:4, agent^:JohnDoe, pubkey^:a83, ratio:0.00007 }
      { id#:'JohnDoe>d28', depth^:1, agent^:JohnDoe, pubkey^:d28, ratio:0.01 }
      ...
    tasks:
      { notBefore^:20240317-..., action^:checkNp, peer:'https://example.com/peer', type:_all_, position:1538, retryCount:0 }
      { notBefore^:20240317-..., ... }
      ...
      { notBefore^:20240229-..., ... }

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
