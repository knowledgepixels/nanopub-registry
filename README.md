# Nanopub Registry

The Nanopub Registry implements a publication/lookup service as envisaged by the [Knowledge Space](https://w3id.org/knowledge-space/).

The Nanopub Registry is designed as the next-generation publication/lookup service for nanopublications, to supersede the
[nanopub-server](https://github.com/tkuhn/nanopub-server).


## Status

This code base is in design phase. See the [design document](design.md).

At the moment only the core nanopublications needed to calculate the trust network (agent introductions and approvals) are loaded.


## Public Instance

The current incomplete implementation is running at this public instance:

- https://registry.np.kpxl.org/


## Development

The recommended development environment is Docker Compose. The `docker-compose.yml` file contains a production configuration with minimal exposed ports. To add features like remote JVM debugging and Mongo Express, copy the `docker-compose.override.yml.template` file to `docker-compose.override.yml` and adjust the configuration. Then, simply run:

```bash
./run.sh
```

**Development ports:**

- `localhost:9292` - Nanopub Registry
- `localhost:5005` - Remote JVM debugging of the Nanopub Registry
- `localhost:8081` - Mongo Express
