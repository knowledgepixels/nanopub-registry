# Nanopub Registry Design (Draft)

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


## Data Structure

_(work in progress...)_

Field type legend: primary# / unique* / combined-unique** / indexed^ (all with prefix lookup)

    server-info:
      setup-id: 1332309348
      status: ready
      last-update: 20240316-...
      last-uptodate: 20240317-...
      coverage-agents:_via-setting_
      coverage-types:_all_
      global-quota: 1000000
      state-counter: 1423293
    quotas:
      { for#:_anyone_ quota:10 }
      { for#:_approved_ quota:'global*ratio' }
      { for#:JohnDoe/a83 quota:'global*ratio*10' }
      { for#:SueRich/b55 quota:1000000 }
      ...
    pubkeys:
      { id#:a83, full-pubkey:4e8d9x... }
      ...
    lists:
      { pubkey**:a83, type**:_all_, status^:loading }
      ...
    list-entries:
      { pubkey**:a83, type**:_all_, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:2, np**:RA..., checksum**:XX... }
      ...
      { pubkey**:a83, type**:intro, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:2, np**:RA..., checksum**:XX... }
      ...
    invalidations:
      { invalidating-np^:RA..., invalidating-pubkey^:a83, invalidated-np^:RA... }
      ...
    pubkey-declarations:
      { agent^:JohnDoe, pubkey^:a83, declaration-pubkey^:a83, declaration^:RA... }
      ...
    setting:
      original: RA123...
      current: RA...
      last-update: 20240316-...
      status: completed
      link-threshold: 0.000001
      bootstrap-services: [..., ...]
    base-agents:
      { id**:JohnDoe, pubkey**:a83 }
      { id**:EveBlue, pubkey**:c43 }
      ...
    agents:
      { pubkey#:a83, agent^:JohnDoe, ratio:0.1362, paths:3, independent-paths:3, quota:1362000 }
      { pubkey#:d28, agent^:JohnDoe, ... }
      ...
    trust-edges:
      { from-agent^:@, from-pubkey^:@ to-agent^:JohnDoe to-pubkey^:a83, source^:RA... }
      { from-agent^:JohnDoe, from-pubkey^:a83, to-agent^:SueRich to-pubkey^:b55, source^:RA... }
      { from-agent^:SueRich, from-pubkey^:b55, to-agent^:EveBlue to-pubkey^:c43, source^:RA... }
      ...
    trust-paths:
      { id#:'JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.01 }
      { id#:'SueRich>b55 JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.009 }
      { id#:'BillSmith>d32 JoeBold>e83 AmyBaker>f02 JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.00007 }
      { id#:'JohnDoe>d28', agent^:JohnDoe, pubkey^:d28, ratio:0.01 }
      ...
    nanopubs:
      { id#:RA..., full-id*:'https://w3id.org/np/RA12...', counter*:1423293, pubkey^:a83, content:'@prefix ...' }
      ...
    tasks:
      { not-before^:20240317-..., action^:check-np, peer:'https://example.com/peer', type:_all_, position:1538, retry-count:0 }
      { not-before^:20240317-..., ... }
      ...
      { not-before^:20240229-..., ... }


## Process

Database initialized:

    server-info:
      setup-id: 1332309348
      status: launching
      state-counter: 0
    tasks:
      { not-before^:20240317-..., action:load-config }

Config loaded:

    server-info:
      ...
      coverage-agents:_via-setting_
      coverage-types:_all_
      global-quota: 1000000
    quotas:
      { for#:_anyone_ quota:10 }
      { for#:_approved_ quota:'global*ratio' }
      { for#:JohnDoe/a83 quota:'global*ratio*10' }
      { for#:SueRich/b55 quota:1000000 }
      ...
    tasks:
      { not-before^:20240317-..., action:load-setting }

Setting loaded:

    pubkeys:
      { id#:a83, full-pubkey:4e8d9x... }
      ...
    setting:
      original: RA123...
      current: RA...
      last-update: 20240316-...
      status: completed
      link-threshold: 0.000001
      bootstrap-services: [..., ...]
    base-agents:
      { id**:JohnDoe, pubkey**:a83 }
      { id**:EveBlue, pubkey**:c43 }
      ...
    nanopubs:
      { id#:RA123..., full-id*:'https://w3id.org/np/RA123...', counter*:1, pubkey^:a83, content:'@prefix ...' }
      ...
    tasks:
      { not-before^:20240317-..., action:load-agent-core, agent:JohnDoe/a83, path:@, ratio:0.1 }
      { not-before^:20240317-..., action:load-agent-core, agent:EveBlue/c43, path:@, ratio:0.1 }
      ...
      { not-before^:20240317-..., action:load-core-info }

Agent core info loaded:

    server-info:
      ...
      state-counter: 132
    pubkeys:
      ...
    lists:
      { pubkey**:a83, type**:_all_, status^:loading }
      ...
    list-entries:
      { pubkey**:a83, type**:_all_, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:_all_, position**:2, np**:RA..., checksum**:XX... }
      ...
      { pubkey**:a83, type**:intro, position**:0, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:1, np**:RA..., checksum**:XX... }
      { pubkey**:a83, type**:intro, position**:2, np**:RA..., checksum**:XX... }
      ...
    invalidations:
      { invalidating-np^:RA..., invalidating-pubkey^:a83, invalidated-np^:RA... }
      ...
    trust-edges:
      { from-agent^:@, from-pubkey^:@ to-agent^:JohnDoe to-pubkey^:a83, source^:RA... }
      { from-agent^:JohnDoe, from-pubkey^:a83, to-agent^:SueRich to-pubkey^:b55, source^:RA... }
      { from-agent^:SueRich, from-pubkey^:b55, to-agent^:EveBlue to-pubkey^:c43, source^:RA... }
      ...
    nanopubs:
      ...
      { id#:RA123..., full-id*:'https://w3id.org/np/RA123...', counter*:59, pubkey^:a83, content:'@prefix ...' }
      ...
    tasks:
      { not-before^:20240317-..., action:calculate-trust-network }

Trust network calculated:

    agents:
      { pubkey#:a83, agent^:JohnDoe, ratio:0.1362, paths:3, independent-paths:3, quota:1362000 }
      { pubkey#:d28, agent^:JohnDoe, ... }
      ...
    trust-paths:
      { id#:'JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.01 }
      { id#:'SueRich>b55 JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.009 }
      { id#:'BillSmith>d32 JoeBold>e83 AmyBaker>f02 JohnDoe>a83', agent^:JohnDoe, pubkey^:a83, ratio:0.00007 }
      { id#:'JohnDoe>d28', agent^:JohnDoe, pubkey^:d28, ratio:0.01 }
      ...
    tasks:
      { not-before^:20240317-..., action:load-nanopubs }

_to be continued..._
