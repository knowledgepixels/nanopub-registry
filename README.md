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

- https://registry.np.trustyuri.net/
- https://registry.knowledgepixels.com/
- https://registry.petapico.org/

Or check out the [Nanopub Monitor](https://monitor.knowledgepixels.com/).

## Development

The recommended development environment is Docker Compose. The `docker-compose.yml` file contains a production
configuration with minimal exposed ports. To add features like remote JVM debugging and Mongo Express, copy the
`docker-compose.override.yml.template` file to `docker-compose.override.yml` and adjust the configuration. Then, simply
run:

```bash
./run.sh
```

**Development ports:**

- `localhost:9292` - Nanopub Registry
- `localhost:5005` - Remote JVM debugging of the Nanopub Registry
- `localhost:8081` - Mongo Express
