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

You can use the attached `docker-compose.yml` to start a development environment. To build the image and start the environment, simply run:

```bash
./run.sh
```

The registry will be available at http://localhost:9292/

Additionally, you can attach a remote debugger to the running nanopub-registry container on `localhost:5005`.
