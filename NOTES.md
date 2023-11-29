# Nanopub Registry Notes

## Principles

- Nanopublications are distributed and decentrally stored in registries
- Registries discriminate which nanopublications to store based on the creator/pubkey (firstly) and their types (secondly)
- Registries apply quotas per creator/pubkey, which can be automatically set or manually configured
- Registries publicly communicate which creator/pubkey and which types they cover, and the applied quotas


## General Behavior

Content store:

- Each registry stores and provides the content of its nanopublications with a key-value-based lookup with the artifact code (RA...) as key

Identifier lists:

- Each registry keeps multiple add-only lists of identifiers that refer to nanopublications in its store
- Each list covers just one pubkey, and can cover either one specific type (e.g. BiodivNanopubs) or all types (_all_)
- To efficently synchronize them, these lists keep at each position a checksum of the set of contained identifiers, which can be used to identify identical sets even when list ordering is different

Trusted agents:

- Trust is rooted in a setting nanopublication provided by the registry configuration
- The setting nanopublication provides a list of initial trusted agents (as ID/pubkey pairs)
- For the determination of trusted agents and their quotas, a few core types (introductions and endorsements of other agents) can be loaded for a given pubkey
- For endorsed agents, the core types can be loaded too
- This process is repeated as long as the link of an endorsed agent back to the initial trusted agents is strong enough (based on number of steps and number of other endorsements at each step)
- Based on the endorsement network of the partially loaded agents, it can be determined which agents should be considered trusted (based on the strength and number of endorsement chains)

Quotas:

- For any trusted agent, if the number of nanopublications found on other registries is smaller than the agent's quota, all nanopublications are loaded


## Complete Data Structure

    - setup-ID: 1332309348
    - status: ready
    - last-update: 20230316-...
    - last-uptodate: 20230317-...
    - coverage:
      - agents: _via-setting_
      - types: _all_
    - quotas:
      - _global_: 1000000
      - _anyone_: 10
      - _approved_: global * ratio
      - JohnDoe: global * ratio * 10
      - SueRich: 1000000
    - registry-state-counter: 1423293
    - registry-log:
      - 1423293: (timestamp:20230316-..., action:add, key:a83, position:1536, id:RA...)
      - 1423292: ...
    - registry:
      - a83:
        - full-key: 4e8d9s...
        - status: loading
        - lists:
          - _all_:
            - count: 1537
            - status: loading
            - positions:
              - 0: (id:RA..., checksum:XX...)
              - 1: (id:RA..., checksum:XX...)
              - 2: (id:RA..., checksum:XX...)
              - ...
            - ids:
              - RA...: (position:354, invalidated-by:[RA...])
              - RA...: (position:928, invalidated-by:[])
              - RA...: (invalidated-by:[RA...])
              - ...
            - checksums:
              - XX...: 324
              - ...
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
          - service-info:
            - ...
          - typex:
            - ...
          - ...
      - b55:
        - ...
    - setting:
      - original: RA123...
      - current: RA...
      - last-update: 20230316-...
      - status: completed
      - link-threshold: 0.000001
      - bootstrap-services:
        - ...
      - services:
        - ...
      - agents:
        - JohnDoe: a83,d28,c32
        - ...
      - base-agents: (JohnDoe/a83, EveBlue/c43, ...)
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
      - RA...: '@prefix ...'
      - ...
    - tasks:
      - 20230317-...: [ (action:check-np, peer:https://example.com/peer, type:_all_, position:1538, retry-count:0) ]
      - 20230317-...: ...
      - ...
      - 20240229-...: ...


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

    - setup-ID: 1332309348
    - status: launching
    - last-update: _none_
    - last-uptodate: _none_
    - coverage:
      - agents: _via-setting_
      - types: _all_
    - quotas:
      - _global_: 1000000
      - _anyone_: 10
      - _approved_: global * ratio
      - JohnDoe: global * ratio * 10
      - SueRich: 1000000
    - registry-state-counter: 0
    - registry-log: _empty_
    - registry: _empty_
    - setting: _empty_
    - content: _empty_
    - tasks:
      - 20230317-...: [ (action:load-setting) ]

Setting definition loaded:

    - setup-ID: 1332309348
    - status: launching
    - last-update: _none_
    - last-uptodate: _none_
    - coverage:
      - agents: _via-setting_
      - types: _all_
    - quotas:
      - _global_: 1000000
      - _anyone_: 10
      - _approved_: global * ratio
      - JohnDoe: global * ratio * 10
      - SueRich: 1000000
    - registry-state-counter: 0
    - registry-log: _empty_
    - registry: _empty_
    - setting:
      - original: RA123...
      - current: RA123...
      - last-update: _none_
      - status: initializing
      - link-threshold: 0.000001 
      - bootstrap-services:
        - https://...
        - ...
      - services: _empty_
      - agents: _empty_
      - base-agents: [JohnDoe/a83, EveBlue/c43, ...]
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
      - 20230317-...: [ (action:load-agent-core, agent:JohnDoe/a83, path:@), (action:load-agent-core, agent:EveBlue/c43, path:@), ..., (action:finalize-trust-network) ]

