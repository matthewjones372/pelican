# flake-0049

The tooling that diagnosed [spec 0049](../../specs/0049-the-shutdown-that-interrupts-itself.md):
an `InterruptedException`, boxed by Scala, joined out of `PelicanServer.stop()`.
Kept because the flake is still live on the `build (21)` job and the next person
to see the trace should not have to build this again.

Nothing here is wired into the build. `tools/` is not a Gradle module, and
Spotless and detekt do not reach it. Everything writes to `build/flake-0049/`.

## The pieces

| | what it answers |
|---|---|
| `jdk/StopCheck.java` | **Who interrupts.** Does a plain `ForkJoinPool.shutdown()` interrupt a worker parked in `managedBlock`? JDK 21 yes, 23 and 25 no. |
| `probe/ProbeConfigurator.java` | **Whether anything interrupts at all.** Replaces `:example:test`'s default dispatcher with a pool whose workers log every `interrupt()` and the interrupter's stack. |
| `soak.sh` | **Catching it live.** Runs `:example:test` until a `Boxed Exception` appears, keeping that run's test-results. |
| `init-testjdk.gradle` | **Which JDK the tests run on.** `jvmToolchain(21)` means every matrix job tests on 21; this overrides it, and is the prototype of the spec's second stack entry. |
| `jdk/SyntheticLoop.java` | A negative result, kept so nobody rebuilds it. |

## Running it

```bash
$JAVA_HOME/bin/java tools/flake-0049/jdk/StopCheck.java   # per JDK; no build needed

tools/flake-0049/probe/build-probe.sh                      # once
tools/flake-0049/soak.sh 2000                              # stops on the first hit
TEST_JDK=25 tools/flake-0049/soak.sh 2000                  # soak a different runtime
```

`soak.sh` appends to `build/flake-0049/soak.log` and copies a hit's results to
`build/flake-0049/hit-<n>/`. The probe writes `build/flake-0049/probe.log`.

## What it has found so far

- `StopCheck` names the interrupter, and is why the spec says JDK 21 rather than
  the 25 in the job label.
- 185 consecutive `:example:test` runs with the probe installed: no hit, and no
  interrupt of any kind on a dispatcher worker. The window never opened locally
  — it is not that it opened and the suite survived.
- `SyntheticLoop`, ~48,000 iterations: nothing. See its header.

## Caveat on the probe

It swaps `PekkoForkJoinPool` for a plain `ForkJoinPool`, so `:example:test` runs
without Pekko's `LoadMetrics` and without `PekkoForkJoinTask` wrapping each
task. Close enough to find an interrupter, not close enough to prove one absent.
