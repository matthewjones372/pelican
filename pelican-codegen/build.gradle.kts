// A fourth interpretation of the same descriptions: Kotlin client source.
//
// pelican-core and nothing else — no server, no JSON library, and no codec
// module. Payload types are read off the schemas the `ApiSpec` already carries,
// so this module never has an opinion about how a body is written.
//
// What it *emits* depends on pelican-core too: the generated client takes a
// `Codecs`, exactly as a server does, so the consumer picks a codec module the
// same way and in the same place.
//
// The emitted runtime lives in src/main/resources as real Kotlin rather than as
// a string in this source set, so it can be read and edited as code.
dependencies {
    api(project(":pelican-core"))
}
