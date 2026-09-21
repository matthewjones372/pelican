#!/usr/bin/env bash
# Compiles ProbeConfigurator against :example's test runtime classpath.
# --release 21 because Test tasks run on the jvmToolchain(21) toolchain, and a
# class file the test JVM cannot read fails as an UnsupportedClassVersionError
# in every test rather than as anything that names the probe.
#
#   tools/flake-0049/probe/build-probe.sh                     # compile
#   tools/flake-0049/probe/build-probe.sh --print-classpath   # just print it
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT="$ROOT/build/flake-0049"

INIT=$(mktemp -t flake0049cp.XXXXXX).gradle
trap 'rm -f "$INIT"' EXIT
cat > "$INIT" <<'GRADLE'
allprojects {
    tasks.register("printTestCp") {
        doLast { println("CP=" + project.sourceSets.test.runtimeClasspath.asPath) }
    }
}
GRADLE

CP=$("$ROOT/gradlew" -p "$ROOT" -q -I "$INIT" :example:printTestCp | grep '^CP=' | head -1 | sed 's/^CP=//')

if [ "${1:-}" = "--print-classpath" ]; then
    echo "$CP"
    exit 0
fi

mkdir -p "$OUT/probe-classes"
javac --release 21 -nowarn -cp "$CP" -d "$OUT/probe-classes" "$(dirname "$0")/ProbeConfigurator.java"
echo "probe compiled into $OUT/probe-classes"
