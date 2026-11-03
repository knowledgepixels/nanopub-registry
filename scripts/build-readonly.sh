#!/usr/bin/env bash
# Builds a read-only registry file: nanopub files -> registry.nq.gz (the N-Quads source, via
# the registry's Build command) -> registry.rete (via the rete CLI container).
#
#   scripts/build-readonly.sh OUTDIR INPUT... [-- BUILD-OPTIONS...]
#
# Example:
#   scripts/build-readonly.sh data/readonly dumps/*.trig.gz -- --setting setting.trig --test-instance
#
# Needs the fat jar in target/ (./mvnw package -DskipTests) and Docker. Keep the .nq.gz:
# the rete format is pre-1.0, and a format change means re-running only the second step.
set -euo pipefail

RETE_IMAGE="${RETE_IMAGE:-ghcr.io/caviri/rete-cli:0.5.0}"
JAR="${REGISTRY_JAR:-$(ls -t "$(dirname "$0")"/../target/nanopub-registry-*-fat.jar 2>/dev/null | head -1)}"
[ -n "$JAR" ] || { echo "No fat jar in target/; run ./mvnw package -DskipTests first" >&2; exit 2; }

if [ $# -lt 2 ]; then
  sed -n '2,12p' "$0"
  exit 2
fi

outdir="$1"; shift
inputs=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
  inputs+=("$1"); shift
done
[ $# -gt 0 ] && shift   # drop the --
if ! mkdir -p "$outdir" 2>/dev/null; then
  echo "Cannot create $outdir (its parent is probably owned by root, like a Docker-created data/ directory)." >&2
  echo "Choose a directory you own, or fix the ownership, e.g. sudo chown \$USER $(dirname "$outdir")" >&2
  exit 2
fi

echo "== 1/2 building $outdir/registry.nq.gz from ${#inputs[@]} input(s)"
java ${JAVA_OPTS:-} -cp "$JAR" com.knowledgepixels.registry.readonly.Build \
  "${inputs[@]}" --out "$outdir/registry.nq.gz" --rejects "$outdir/rejects.txt" "$@"

echo "== 2/2 building $outdir/registry.rete with $RETE_IMAGE"
docker run --rm -v "$(cd "$outdir" && pwd):/data" "$RETE_IMAGE" \
  build /data/registry.nq.gz -o /data/registry.rete --card --title "Nanopub Registry snapshot"

echo "== done: $(ls -la "$outdir/registry.rete" | awk '{print $5}') bytes"
echo "   serve with: READONLY_DATA_DIR=$outdir docker compose -f docker-compose.readonly.yml up -d"
