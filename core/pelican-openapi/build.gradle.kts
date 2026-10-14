// Descriptions in, OpenAPI out. Note the absence of any server dependency:
// this module can generate a spec in a build task with no runtime present.
// It has no JSON library either — payload schemas come from the SchemaSource
// the spec carries, and the document itself is core's JsonValue. Its own tests
// supply a hand-written SchemaSource rather than depending on a codec module,
// which is the same claim stated as a build file.
dependencies {
    api(project(":pelican-core"))
}
