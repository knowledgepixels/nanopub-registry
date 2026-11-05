[![Coverage Status](https://coveralls.io/repos/github/knowledgepixels/nanopub-registry/badge.svg?branch=main)](https://coveralls.io/github/knowledgepixels/nanopub-registry?branch=main)
[![semantic-release: angular](https://img.shields.io/badge/semantic--release-angular-e10079?logo=semantic-release)](https://github.com/semantic-release/semantic-release)

# Nanopub Registry

The Nanopub Registry implements a publication/lookup service as envisaged by
the [Knowledge Space](https://w3id.org/knowledge-space/).

See the [design document](design.md) for some more details on the conceptual and technical parts.

The Nanopub Registry is the second-generation publication/lookup service for nanopublications, superseding the
[nanopub-server](https://github.com/tkuhn/nanopub-server).

## Status

The Nanopub Registry is in production use, with several public instances running (see below). Parts of the trust
model - in particular the handling of endorsements - are still evolving.

## Public Instance

These are some currently running instances:

- https://registry.nanodash.net/
- https://registry.knowledgepixels.com/
- https://registry.petapico.org/

Or check out the [Nanopub Monitor](https://monitor.knowledgepixels.com/).

## Running an Instance

To run your own registry instance, copy the `docker-compose.override.yml.template` file to
`docker-compose.override.yml`, adjust the settings in its Section 1 (public URL, coverage, and peers), and run:

```bash
docker compose up -d
```

The registry serves plain HTTP on `localhost:9292` (plus a localhost-only metrics endpoint on `localhost:9293`). To
make it publicly reachable via HTTPS, run a reverse proxy (e.g. nginx) on the host that terminates TLS and forwards
to `localhost:9292`.

If that proxy rate-limits publishing, make it answer `429` with a `Retry-After` header rather than nginx's default
`503` (`limit_req_status 429;`). Publishing clients back off and retry on `429`, while a `503` reads as "this registry
is down, try another one" — which makes a throttled bulk publish fail instead of slowing down.

## Read-only Instance from a Static File

A registry can also serve a fixed set of nanopublications without MongoDB: the nanopubs, their lists and the
registry's metadata are built once into a single [rete](https://github.com/caviri/rete) file, and a stateless registry
container serves it through a `rete serve` sidecar that answers SPARQL straight from the file. This is the cheap way to
publish a large collection: build the file, drop it on any host, run the two containers. The same file is a regular
SPARQL-queryable dataset, with every nanopub in its own named graphs.

A read-only instance accepts no nanopubs (`POST /` answers `405`, which publishing clients take as "try the next
registry"), runs no trust computation and syncs with no peers. It reports `Nanopub-Registry-Read-Only: true` and, since
it computes no trust, publishes none: no accounts, no agents, and one empty trust state snapshot. The pubkeys and their
lists are served as usual; that is what consumers need to load the content.

Build the file from nanopub files (TriG, N-Quads or Jelly, optionally gzipped; their order defines the load counters):

```bash
./mvnw package -DskipTests
scripts/build-readonly.sh data/readonly dumps/*.trig.gz -- --setting setting.trig
```

The script runs the registry's `Build` command, which verifies every nanopub (trusty artifact code, signature, size
limits) and writes `data/readonly/registry.nq.gz`, and then `rete build` in the `ghcr.io/caviri/rete-cli` container,
which turns that into `data/readonly/registry.rete`. Keep the `.nq.gz`: the rete format is pre-1.0, and a format change
means re-running only the second step. `Build --help` lists the options (coverage, `--no-content-quads` for a smaller
registry-only file, `--skip-verify`, batch size).

Run it, and check the file through the running sidecar:

```bash
READONLY_DATA_DIR=data/readonly docker compose -f docker-compose.readonly.yml up -d   # or any directory you own
java -cp target/nanopub-registry-*-fat.jar com.knowledgepixels.registry.readonly.Validate --sparql http://localhost:7878/sparql
```

`Validate` re-verifies every nanopub from its stored TriG, compares the TriG, Jelly and content graphs, walks every
list's position and checksum chain and the counter and id chains, and checks the trust state. See the
[design document](design.md#read-only-mode) for the file's layout.

## Development

The recommended development environment is Docker Compose too. Optional development features like remote JVM
debugging (`localhost:5005`) and Mongo Express (`localhost:8081`) can be enabled in the development section of
`docker-compose.override.yml`. To build and run from the local sources, simply run:

```bash
./run.sh
```
