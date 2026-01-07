#!/usr/bin/env bash

cd "$( dirname "${BASH_SOURCE[0]}" )"

set -e

docker compose down
docker build -f local.Dockerfile -t nanopub/registry:latest .
docker compose up
