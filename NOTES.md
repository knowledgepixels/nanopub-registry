# Nanopub Registry Notes

## Data Structure Sketch

Core part (to be developed first):

    state: 1332309348 / 1423293 (installation ID / state ID)
    last-uptodate: 20230317-...
    last-update: 20230316-...
    main log (latest first):
    - 20230316-... a83 1536 RA...
    - 20230316-... e77 4521 RA...
    - ...
    per-key registry:
    - a83: (key:4e8d9s..., count:1537, checksum:XX..., complete:true)
      - np: (0:RA..., 1:RA..., ..., 1536:RA...)
    - b55: (key:b89029..., count:234 checksum:XX..., complete:true)
      - np: (0:RA..., 1:RA..., ..., 233:RA...)
    - c04: (key:f382ac..., count:9543, checksum:XX..., complete:false)
      - np: (0:RA..., 1:RA..., ..., 9542:RA...)
    - ...

Setting/quota support (to be developed later):

    setting: ...
    last-setting-update: ...
    next-setting-update: ...
    authorities/keys:
    - John-Doe: a83,d28,c32
    - ...
    services:
    - ...
    quota:
    - 10: * (anyone, with limited new users per hour)
    - 10000: ? (any approved user)
    - 100000: John-Doe, Jane-Smith
    - 1000000: Sue-Rich

## Update Dependencies

    $ mvn versions:use-latest-versions
    $ mvn versions:update-properties

