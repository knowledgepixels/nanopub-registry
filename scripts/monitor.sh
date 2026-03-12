#!/bin/bash
#
# Monitor nanopub registry loading progress.
#
# Periodically polls the registry's JSON endpoint and logs key metrics as TSV.
# Useful for tracking how fast nanopubs are loaded after a fresh start, comparing
# configurations (e.g. with/without REGISTRY_LOAD_ALL_PUBKEYS), or watching a
# trust update cycle.
#
# Columns: timestamp, elapsed seconds, server status, nanopubCount, loadCounter,
#          accountCount, agentCount
#
# Usage:
#   ./scripts/monitor.sh                          # poll every 10s, default URL
#   ./scripts/monitor.sh 5                        # poll every 5s
#   ./scripts/monitor.sh 10 http://host:9292      # custom registry URL
#   ./scripts/monitor.sh 10 | tee monitor.log     # watch live + save to file
#
# Requirements: curl, python3

set -euo pipefail

INTERVAL=${1:-10}
BASE_URL=${2:-http://localhost:9292}
URL="${BASE_URL}/.json"

printf "timestamp\tseconds\tstatus\tnanopubCount\tloadCounter\taccountCount\tagentCount\n"

START=$(date +%s)

while true; do
    NOW=$(date +%s)
    ELAPSED=$((NOW - START))
    JSON=$(curl -sf "$URL" 2>/dev/null) || true
    if [ -n "$JSON" ]; then
        printf "%s\t%d\t%s\t%s\t%s\t%s\t%s\n" \
            "$(date +%H:%M:%S)" \
            "$ELAPSED" \
            "$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status","?"))' 2>/dev/null)" \
            "$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("nanopubCount",0))' 2>/dev/null)" \
            "$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("loadCounter",0))' 2>/dev/null)" \
            "$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accountCount",0))' 2>/dev/null)" \
            "$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("agentCount",0))' 2>/dev/null)"
    else
        printf "%s\t%d\t%s\n" "$(date +%H:%M:%S)" "$ELAPSED" "unreachable"
    fi
    sleep "$INTERVAL"
done
