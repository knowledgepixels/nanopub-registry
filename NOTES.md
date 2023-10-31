# Nanopub Registry Notes

## Data Structure Sketch

Core part (to be developed first):

    setup ID: 1332309348
    state counter: 1423293
    last-uptodate: 20230317-...
    last-update: 20230316-...
    main log (latest first):
    - 20230316-... a83 1536 RA...
    - 20230316-... e77 4521 RA...
    - ...
    per-key registry:
    - a83: full-key:4e8d9s...
      - intro: (count:11, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 10:RA...)
      - approval: (count:143, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 142:RA...)
      - all: (count:1537, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 1536:RA...)
    - b55: (full-key:b89029..., count:234 checksum:XX..., complete:true)
      - intro: (count:1, checksum:XX..., complete:true, 0:RA..., 1:RA...)
      - approval: (count:12, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 11:RA...)
      - all: (count:1537, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 233:RA...)
    - c04: (full-key:f382ac..., count:9543, checksum:XX..., complete:false)
      - intro: (count:8, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 7:RA...)
      - approval: (count:55, checksum:XX..., complete:true, 0:RA..., 1:RA..., ..., 54:RA...)
      - all: (count:1537, checksum:XX..., complete:false, 0:RA..., 1:RA..., ..., 9542:RA...)
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

