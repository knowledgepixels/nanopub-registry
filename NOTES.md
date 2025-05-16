# Notes for Nanopub Registry

## Test POST Request

    $ curl -i -X POST http://localhost:9292/ -H 'Content-Type: application/trig' --data-binary "@scratch/np1.trig"

## DB packaging

Create package:

    $ sudo tar -cvzf nanopub-registry-mongodb.tar.gz data/mongodb

Expand package:

    $ tar -xvzf nanopub-registry-mongodb.tar.gz
