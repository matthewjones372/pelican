# Contributing

[AGENTS.md](AGENTS.md) is the source of truth for how work is done here, by
people and by agents alike: specs in `specs/` before code, one spec section per
stacked pull request, the testing order, the comment and documentation rules,
and the build gates. This file is the short version; where the two disagree,
AGENTS.md is right.

## Building and testing

The build needs JDK 25.

```bash
./gradlew spotlessApply && ./gradlew build
```

`build` runs every module's tests, detekt, spotless, the Kover coverage floor
and the gates listed in AGENTS.md. Run it before saying a change is done, and
quote its result. Formatting is a gate, so `spotlessApply` comes after the last
edit.

The examples run from the same build, for example:

```bash
./gradlew :example:runFirstEndpointOnPekko # the README's greeting, on Pekko, on :8080
./gradlew :example:runReadmeExample        # the bookmarks service
```

The README's [Running the examples](README.md#running-the-examples) lists the
rest. Tests against real brokers run in Docker and are kept out of `build`:
`./gradlew containerTests containerTestsOldestKafka --no-parallel`.

## Compatibility

From 1.0.0 the public API of the released modules is stable, and a breaking
change waits for a major release. Two checks hold that, and a change must pass
both rather than work around them:

- **The `.api` dump beside each module** is the binary contract, and `apiCheck`
  runs on `build`. A change to a public signature changes its dump in the same
  commit, where a reviewer reads it. A dump that changes for a released module
  is a compatibility question, not a file to regenerate for green.
- **`StillCompilesTest`** compiles pinned call sites of the reified inline DSL
  (`json<T>()`, `pathParam<T>()` and the rest), which no dump can see. A
  fixture that stops compiling is a break.

[Stability](docs/reference.md#stability) in the reference manual says what is
and is not covered.

## Documentation

The README and `docs/` describe the library as it is on `main`. Anything on
`main` but not in a release is labelled as such wherever it is installed or
recommended, and examples in the docs are cut from files under `example/` that
the build compiles. [docs/README.md](docs/README.md) is the index.
