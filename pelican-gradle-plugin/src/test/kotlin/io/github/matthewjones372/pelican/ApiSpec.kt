package io.github.matthewjones372.pelican

/**
 * Stands in for the real `ApiSpec`, with the two accessors the plugin reads.
 *
 * The plugin never compiles against Pelican — it loads these names off the
 * consumer's classpath — so what the tests need is a classpath carrying the
 * same names. That is what this package is: the shape of the library, at the
 * one place it is reached by reflection rather than by the compiler.
 */
class ApiSpec(val title: String, val servers: List<String> = emptyList())
