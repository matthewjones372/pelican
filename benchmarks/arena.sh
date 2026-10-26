#!/usr/bin/env bash
# benchmarks/arena.sh <profile> <contestant>...: HttpArena's closed-model load against each contestant, on one machine.
#
#   profile     baseline (GET and POST /baseline11, the POST half by Content-Length and half chunked) or json (/json/50)
#   contestant  raw-gannet, gannet-inline, gannet, gannet-nonblocking, jetty, jetty-dispatcher, jetty-nonblocking, pekko
#               or pekko-dispatcher
#
# Each contestant is a fresh JVM on cores 0-1 with gcannon on 2-3, warmed WARM seconds and measured DURATION, the
# contestants alternating within each of RUNS runs. Before each measurement the machine must be quiet; a measurement
# during which anything else used more than 20% of a core is discarded and taken again. Prints one RESULT line per
# measurement and a table of medians, both also written to benchmarks/build/arena-<profile>.txt.
#
# Needs GCANNON (github.com/MDA2AV/gcannon, built against liburing as HttpArena's Dockerfile does) and a JDK 25 java,
# from JAVA or JAVA_HOME. RUNS (3), WARM (10), DURATION (10) and CONNS (4096) override the defaults; SKIP_BUILD=1 uses
# the classpath already built.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -ge 2 ]] || { sed -n '2,6p' "$0" >&2; exit 2; }
profile=$1
shift
RUNS=${RUNS:-3}
WARM=${WARM:-10}
DURATION=${DURATION:-10}
CONNS=${CONNS:-4096}
: "${GCANNON:?set GCANNON to the gcannon binary}"
java=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
port=8080
arena=benchmarks/arena
out=benchmarks/build/arena-$profile.txt

[[ -n ${SKIP_BUILD:-} ]] || ./gradlew -q :benchmarks:arenaClasspath
cp=$(cat benchmarks/build/arena-classpath.txt)
flags="-Xms1g -Xmx1g --enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED"
pkg=io.github.matthewjones372.pelican.benchmarks.arena
hz=$(getconf CLK_TCK)
pidfile=benchmarks/build/arena.pid
# The server runs in a subshell; on the way out, stop it by the PID it wrote.
trap '[[ -s $pidfile ]] && kill "$(cat "$pidfile")" 2> /dev/null; rm -f "$pidfile"' EXIT
trap 'exit 130' INT TERM

server() { # server <contestant>: runs in the foreground, so `server x &` leaves the JVM's own PID in $!
  case $1 in
    raw-gannet) exec taskset -c 0,1 "$java" $flags -cp "$cp" $pkg.RawGannetArenaKt $port ;;
    gannet | gannet-inline | gannet-nonblocking | jetty | jetty-dispatcher | jetty-nonblocking | pekko | pekko-dispatcher)
      exec taskset -c 0,1 "$java" $flags -cp "$cp" $pkg.ArenaServerKt "$1" $port $arena/dataset.json ;;
    *) echo "unknown contestant $1" >&2; exit 2 ;;
  esac
}

load() { # load <seconds>
  local r=$arena/requests
  case $profile in
    baseline) taskset -c 2,3 "$GCANNON" "http://127.0.0.1:$port/" --raw "$r/get.raw,$r/post_cl.raw,$r/post_chunked.raw" \
                -c "$CONNS" -t 2 -d "${1}s" -p 1 --json ;;
    json) taskset -c 2,3 "$GCANNON" "http://127.0.0.1:$port/" --raw "$r/json-get.raw" \
            -c "$CONNS" -t 2 -d "${1}s" -p 1 --recv-buf 65536 --json ;;
    *) echo "unknown profile $profile" >&2; exit 2 ;;
  esac
}

busy() { awk '/^cpu /{print $2+$3+$4+$7+$8+$9}' /proc/stat; } # every CPU's busy ticks
reaped() { awk '{print $16+$17}' "/proc/$1/stat"; }           # ticks of the children process $1 reaped: gcannon

quiet() { # waits until the machine's busy time over 3 s is under 15% of one core
  local b0
  for _ in $(seq 1200); do
    b0=$(busy); sleep 3
    (( ($(busy) - b0) * 100 / hz < 45 )) && return 0
  done
  echo "the machine did not go quiet" >&2; exit 1
}

measure() { # measure <run> <contestant>: one fresh server, warmed and measured; prints a RESULT line
  curl -s -m 1 -o /dev/null "http://127.0.0.1:$port/" && { echo "port $port is already serving" >&2; exit 1; }
  server "$2" > "benchmarks/build/arena-$2.log" 2>&1 &
  local pid=$! self=$BASHPID json u0 s0 u1 s1 t0 t1 b0 b1 k0 k1
  echo "$pid" > "$pidfile"
  for _ in $(seq 150); do curl -s -m 1 -o /dev/null "http://127.0.0.1:$port/pipeline" && break; sleep 0.2; done
  load "$WARM" > /dev/null
  read -r u0 s0 < <(awk '{print $14, $15}' /proc/$pid/stat); t0=$(date +%s%N); b0=$(busy); k0=$(reaped $self)
  json=$(load "$DURATION")
  read -r u1 s1 < <(awk '{print $14, $15}' /proc/$pid/stat); t1=$(date +%s%N); b1=$(busy); k1=$(reaped $self)
  kill "$pid"; wait "$pid" 2> /dev/null || true; rm -f "$pidfile"
  echo "$json" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
run, c, hz, u, s, other, nanos = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:])
secs, n, st = nanos / 1e9, d["rps"] * nanos / 1e9, d["status"]
print("RESULT run=%s contestant=%s rps=%.0f userUs=%.2f sysUs=%.2f otherPct=%d ok=%d non2xx=%d" % (
    run, c, d["rps"], u / hz / n * 1e6, s / hz / n * 1e6, other * 100 / hz / secs, st["2xx"],
    st["3xx"] + st["4xx"] + st["5xx"] + st["other"]))
' "$1" "$2" "$hz" $((u1 - u0)) $((s1 - s0)) $(((b1 - b0) - (u1 - u0) - (s1 - s0) - (k1 - k0))) $((t1 - t0))
}

mkdir -p benchmarks/build
{ "$java" -version 2>&1 | grep -v JAVA_TOOL | head -1
  echo "$profile; server on cores 0-1, gcannon on 2-3; $CONNS connections; warm ${WARM}s, measured ${DURATION}s"
} | sed 's/^/# /' | tee "$out"
for run in $(seq "$RUNS"); do
  for c in "$@"; do
    for try in 1 2 3 4; do
      quiet
      line=$(measure "$run" "$c")
      [[ $line == RESULT* ]] || exit 1
      other=${line##*otherPct=}; other=${other%% *}
      if (( other <= 20 )); then echo "$line" | tee -a "$out"; break; fi
      echo "# discarded, disturbed: $line" | tee -a "$out"
      sleep 2
    done
    sleep 2
  done
done

python3 -I - "$out" << 'EOF' | tee -a "$out"
import re, statistics, sys
rows = {}
for line in open(sys.argv[1]):
    if line.startswith("RESULT"):
        f = dict(kv.split("=") for kv in line.split()[1:])
        rows.setdefault(f["contestant"], []).append(f)
print("\n| contestant | rps (median) | spread | user µs/req | sys µs/req | non-2xx |\n|---|--:|--:|--:|--:|--:|")
for c, fs in rows.items():
    rps = sorted(float(f["rps"]) for f in fs)
    med = lambda k: statistics.median(float(f[k]) for f in fs)
    print("| %s | %.0f | %.0f-%.0f (%d) | %.2f | %.2f | %d |" % (
        c, statistics.median(rps), rps[0], rps[-1], len(rps), med("userUs"), med("sysUs"),
        sum(int(f["non2xx"]) for f in fs)))
EOF
