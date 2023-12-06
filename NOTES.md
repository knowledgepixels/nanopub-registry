# Nanopub Registry Notes

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
- Each lists provides hashtable-based lookup on positions (get n-th position in list), identifier (get position of RA... in list), and checksum (get position with checksum XX...)

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
- Determining the difference of the list of given creator/pubkey/type with that of another registry is _linear_ with respect to the lengths of the lists in the worst case, and likely _sub-linear_ in practice with the use of checksums


### Scalability

- Registries can restrict themselves to small subsets of creators/pubkeys and/or types, and therefore the global set of nanopublications can be distributed across as many servers as needed
- Any subset defined by creator/pubkey/type can be efficiently located and loaded (from other registries, query services, and other tools)
- Approval of new trusted agents can be done by any existing trusted agent (with sufficiently strong trust chains), and therefore doesn't depend on a single bottleneck
- Arbitrarily large datasets can be published, by setting up dedicated registry instances and using dedicated pubkeys if quotas of existing general registries don't suffice


### Robustness

- Inclusion in a registry requires signature with a pubkey that is considered trusted, which prevents flooding with nanopublications that are not signed with one of these pubkeys
- If a pubkey is compromised, the quota limits the possible flooding
- Once a compromised pubkey is identified as such, the respective nanopublications can be efficiently unloaded from a registry, as all lists are clearly separated by pubkey


## Data Structure

Field type legend: primary# / unique* / indexed^

    setup-ID: 1332309348
    status: ready
    last-update: 20230316-...
    last-uptodate: 20230317-...
    coverage: { agents: _via-setting_, types: _all_ }
    global-quota: 1000000
    quotas: { _anyone_: 10, _approved_: 'global*ratio', JohnDoe/a83: 'global*ratio*10', SueRich/b55: 1000000 }
    registry-state-counter: 1423293
    registry-log:
      { counter#:1423293, timestamp:20230316-..., action:add, id:RA..., key:a83)
      { counter#:1423292, ... }
      ...
    registry:
      a83:
        full-key: 4e8d9s...
        status: loading
        lists:
          _all_:
            count: 1537
            status: loading
            content:
              { id#:RA..., position*:0, checksum*:XX..., invalidated-by:[] }
              { id#:RA..., position*:1, checksum*:XX..., invalidated-by:[] }
              { id#:RA..., position*:2, checksum*:XX..., invalidated-by:[RA...] }
              ...
              { id#:RA..., invalidated-by:[RA...] }
              ...
          intro:
            count:11
            status: complete
            content:
              { id#:RA..., position*:0, checksum*:XX..., invalidated-by:[] }
              { id#:RA..., position*:1, checksum*:XX..., invalidated-by:[] }
              { id#:RA..., position*:2, checksum*:XX..., invalidated-by:[], flag:secondary }
              ...
              { id#:RA..., invalidated-by:[RA...] }
              ...
          endorsement: ...
          service-info: ...
          typex: ...
          ...
        b55: ...
        ...
    setting:
      original: RA123...
      current: RA...
      last-update: 20230316-...
      status: completed
      link-threshold: 0.000001
      bootstrap-services:
        ...
      base-agents: [ JohnDoe/a83, EveBlue/c43, ... ]
      agents:
        { key#:a83, agent^:JohnDoe, ratio:0.1362, paths:3, independent-paths:3, quota:1362000 }
        { key#:d28, agent^:JohnDoe, ... }
        ...
      trust network:
        edges: [ @-JohnDoe/a83, JohnDoe/a83-SueRich/b55, SueRich/b55-EveBlue/c43, ... ]
        ratio-paths:
          { path#:'@-JohnDoe', agent^:JohnDoe, key^:a83, ratio:0.1 }
          { path#:'@-SueRich/b55-JohnDoe/a83', agent^:JohnDoe, key^:a83, ratio:0.1 }
          { path#:'@-BillSmith/d32-JoeBold/e83-AmyBaker/f02-JohnDoe/a83', agent^:JohnDoe, key^:a83, ratio:0.1 }
          { path#:'@-JohnDoe', agent^:JohnDoe, key^:d28, ratio:0.1 }
          ...
    content:
      { id#:RA12..., content:'@prefix ...', code-prefix^:RA1, code-prefix^:RA12, id-prefix^:'http://example.org/np/' }
      ...
    tasks:
      { not-before^:20230317-..., action:check-np, peer:'https://example.com/peer', type:_all_, position:1538, retry-count:0 }
      { not-before^:20230317-..., ... }
      ...
      { not-before^:20240229-..., ... }


## Process

Process started:

    - setup-ID: 1332309348
    - status: launching
    - last-update: _none_
    - last-uptodate: _none_
    - coverage: _empty_
    - quotas: _empty_
    - registry-state-counter: 0
    - registry-log: _empty_
    - registry: _empty_
    - setting: _empty_
    - content: _empty_
    - tasks:
      - 20230317-...: [ (action:load-config), (action:load-setting) ]

Config loaded:

    ...
    - coverage:
      - agents: _via-setting_
      - types: _all_
    - quotas:
      - _global_: 1000000
      - _anyone_: 10
      - _approved_: global * ratio
      - JohnDoe/a83: global * ratio * 10
      - SueRich/b55: 1000000
    - tasks:
      - 20230317-...: [ (action:load-setting) ]

Setting definition loaded:

    ...
    - setting:
      - original: RA123...
      - current: RA123...
      - last-update: _none_
      - status: initializing
      - link-threshold: 0.000001 
      - bootstrap-services:
        - https://...
        - ...
      - base-agents: [JohnDoe/a83, EveBlue/c43, ...]
      - agents: _empty_
      - trust network:
        - edges: [@-JohnDoe/a83, @-EveBlue/c43, ...]
        - ratio-paths:
          - John-Doe/a83:
            - @: 0.1
          - EveBlue/c43:
            - @: 0.1
          - ...
    - content:
      - RA123...: '@prefix ...'
    - tasks:
      - 20230317-...: [ (action:load-agent-core, agent:JohnDoe/a83, path:@, ratio:0.1), (action:load-agent-core, agent:EveBlue/c43, path:@, ratio:0.1), ..., (action:calculate-trust-network) ]

Agent core info loaded:

    ...
    - registry-state-counter: 132
    - registry-log:
      - 132: (timestamp:20230316-..., action:add-core, id:RA..., key:a83)
      - 131: ...
    - registry:
      - a83:
        - full-key: 4e8d9s...
        - status: core-loaded
        - lists:
          - intro:
            - count:11
            - status: complete
            - positions:
              - 0: (id:RA..., checksum:XX...)
              - 1: (id:RA..., checksum:XX...)
              - 2: (id:RA..., checksum:XX..., flag:secondary)
              - ...
            - ids:
              - RA...: (position:5, invalidated-by:[])
              - RA...: (position:2, invalidated-by:[RA...])
              - ...
            - checksums:
              - XX...: 5
              - ...
          - endorsement:
            - ...
      - b55:
        - ...
    - setting:
      - trust network:
        - edges: [@-JohnDoe/a83, JohnDoe/a83-SueRich/b55, SueRich/b55-EveBlue/c43, ...]
        - ratio-paths:
          - John-Doe/a83:
            - @: 0.1
            - @-SueRich/b55: 0.02
            - @-BillSmith/d32-JoeBold/e83-AmyBaker/f02: 0.0001
          - John-Doe/d28:
            - ...
          - SueRich/b55:
            - ...
    - content:
      - ...
      - RA123...: '@prefix ...'
      - ...
    - tasks:
      - 20230317-...: [ (action:calculate-trust-network) ]

Trust network calculated:

    ...
    - setting:
      - agents:
        - JohnDoe:
          - a83:
            - ratio: 0.1362
            - paths: 3
            - independent-paths: 3
            - quota: 1362000
          - d28:
            ...
        - ...
    - tasks:
      - 20230317-...: [ (action:load-content) ]

_to be continued..._

