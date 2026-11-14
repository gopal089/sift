#!/usr/bin/env bash
# Runs the four load tests (keyword/smart x 10/50 VUs, 60 s each), each against a FRESH server (empty cache).
# Usage: loadtest/run-all.sh [cache-on|cache-off]   (after ./gradlew :api:installDist; needs k6)
#   cache-off (default): SIFT_CACHE_SIZE=0, every request is fully computed = what the engine can really do.
#   cache-on: 10,000-entry cache. With 2,000 distinct queries it fills within seconds, so this is a best case.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="$(./gradlew -q :engine:printJavaHome | tail -n 1)"
export JAVA_HOME
SET="${1:-cache-off}"
if [[ "$SET" == cache-off ]]; then export SIFT_CACHE_SIZE=0; else export SIFT_CACHE_SIZE=10000; fi
PORT=8095
for mode in keyword smart; do
  for vus in 10 50; do
    PORT=$PORT api/build/install/sift-api/bin/sift-api > /dev/null 2>&1 &
    pid=$!
    until curl -sf "http://localhost:$PORT/health" > /dev/null; do sleep 0.2; done
    k6 run --quiet -e BASE="http://localhost:$PORT" -e MODE=$mode -e VUS=$vus \
       -e OUT="loadtest/results/$SET-$mode-$vus.json" loadtest/search.js
    rss=$(ps -o rss= -p $pid | tr -d ' ')
    cache=$(curl -s "http://localhost:$PORT/stats" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["cache"]))')
    echo "{\"mode\": \"$mode\", \"vus\": $vus, \"serverRssMbAfter\": $((rss / 1024)), \"cache\": $cache}" \
       > "loadtest/results/$SET-$mode-$vus-server.json"
    echo "  server RSS after: $((rss / 1024)) MB; cache: $cache"
    kill $pid; wait $pid 2>/dev/null || true
  done
done
