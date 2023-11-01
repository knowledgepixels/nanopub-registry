# Nanopub Registry Notes

## Data Structure Sketch

    setup ID: 1332309348
    state counter: 1423293
    last-update: 20230316-...
    last-uptodate: 20230317-...
    global-quota: 1000000
    log:
    - 1423293 20230316-... a83 1536 RA...
    - 1423292 20230316-... e77 4521 RA...
    - 1423291 ...
    registry:
    - a83:
      - full-key:4e8d9s...
      - count:1537
      - checksum:XX...
      - complete:true
      - np: (0:RA..., 1:RA..., ..., 1536:RA...)
      - types:
        - intro:
          - count:11
          - checksum:XX...
          - complete:true
          - np: (0:RA..., 1:RA..., ..., 10:RA...)
        - approval:
          - ...
        - ... 
    - b55:
      - ...
    - setting:
      - current: ...
      - last-update: ...
      - status: completed
      - agents:
        - John-Doe: a83,d28,c32
        - ...
      - services:
        - ...
      - trust network:
        - edges: (a83-b55,b55-c43,...)
        - ratio paths:
          - a83: (@:0.1, @b55:0.02, @d32-e83-f02:0.0001)
          - b55: ...
    quota:
    - *: 10 (anyone, with limited new users per hour)
    - ?: r*1 ? (any approved user)
    - John-Doe: r*10
    - Sue-Rich: 1000000

## Update Dependencies

    $ mvn versions:use-latest-versions
    $ mvn versions:update-properties

