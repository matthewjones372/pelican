package io.github.matthewjones372.pelican.openapi

/*
 * As `pelican-openapi` publishes them. The real pair compares two OpenAPI
 * documents; what is under test here is the call, so these answer from the
 * text they were handed.
 */

fun compatibilityReport(published: String, proposed: String, heading: String, colour: Boolean): String =
    if (published == proposed) "$heading — nothing changed." else "$heading — 1 change breaks callers.$colour"

fun breakingChanges(published: String, proposed: String): Int = if (published == proposed) 0 else 1
