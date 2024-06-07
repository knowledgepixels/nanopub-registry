# Notes

- load intros of base agents:
  - > agents
- repeat:
  - calculate trust paths:
    - trust-edges > trust-paths
  - determine newly accepted intros (stop if none)
    - trust-paths > pubkey-declarations
  - load newly accepted declarations:
    - load intro:
      - get agent-id/pubkeys
    - load intro list nanopubs:
      - > pubkey-declarations
    - load approval list nanopubs:
      - > endorsements
    - load incoming edges:
      - endorsements > trust-edges
    - mark agent-id/pubkey as loaded:
      - > agents
