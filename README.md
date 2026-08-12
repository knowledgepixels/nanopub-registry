[![Coverage Status](https://coveralls.io/repos/github/knowledgepixels/nanopub-registry/badge.svg?branch=main)](https://coveralls.io/github/knowledgepixels/nanopub-registry?branch=main)
[![semantic-release: angular](https://img.shields.io/badge/semantic--release-angular-e10079?logo=semantic-release)](https://github.com/semantic-release/semantic-release)

# Nanopub Registry

The Nanopub Registry implements a publication/lookup service as envisaged by
the [Knowledge Space](https://w3id.org/knowledge-space/).

See the [design document](design.md) for some more details on the conceptual and technical parts.

The Nanopub Registry is the second-generation publication/lookup service for nanopublications, superseding the
[nanopub-server](https://github.com/tkuhn/nanopub-server).

## Status

This code base is in beta phase.

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

## Development

The recommended development environment is Docker Compose too. Optional development features like remote JVM
debugging (`localhost:5005`) and Mongo Express (`localhost:8081`) can be enabled in the development section of
`docker-compose.override.yml`. To build and run from the local sources, simply run:

```bash
./run.sh
```
