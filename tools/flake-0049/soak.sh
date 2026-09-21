#!/usr/bin/env bash
# Runs :example:test in a loop until the flake in spec 0049 appears.
# Stops on the first "Boxed Exception" and keeps that run's test-results.
#   tools/flake-0049/probe/build-probe.sh          # once, optional but worth it
#   tools/flake-0049/soak.sh 2000
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNS=${1:-500}
OUT="$ROOT/build/flake-0049"
mkdir -p "$OUT"

ARGS=(-I "$ROOT/tools/flake-0049/init-probe.gradle")
[ -d "$OUT/probe-classes" ] || { echo "no probe compiled; running without it"; ARGS=(); }
[ -n "${TEST_JDK:-}" ] && ARGS+=(-I "$ROOT/tools/flake-0049/init-testjdk.gradle" "-DtestJdk=$TEST_JDK")

for i in $(seq 1 "$RUNS"); do
    "$ROOT/gradlew" -p "$ROOT" :example:test --rerun -q "${ARGS[@]}" > "$OUT/last-run.log" 2>&1
    rc=$?
    if grep -qr "Boxed Exception" "$ROOT/example/build/test-results/test/" 2>/dev/null; then
        echo "HIT on iteration $i at $(date +%T)" | tee -a "$OUT/soak.log"
        cp -r "$ROOT/example/build/test-results/test" "$OUT/hit-$i"
        exit 1
    fi
    echo "iteration $i rc=$rc $(date +%T)" >> "$OUT/soak.log"
    [ "$rc" -ne 0 ] && echo "non-zero build on iteration $i; see $OUT/last-run.log" && exit 1
done
echo "LOOP DONE, no hit, $RUNS runs" | tee -a "$OUT/soak.log"
