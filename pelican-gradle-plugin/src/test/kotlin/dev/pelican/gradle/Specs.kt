package dev.pelican.gradle

import dev.pelican.ApiSpec

/** A top-level function, which is where a spec usually lives. */
fun ordersSpec(): ApiSpec = ApiSpec("Orders", listOf("https://orders.test", "https://second.test"))

/** No servers, so the client's default base URL has to come from somewhere else. */
fun serverlessSpec(): ApiSpec = ApiSpec("Orders")

/** Not a spec at all. */
fun notASpec(): String = listOf("no").first()

fun throwingSpec(): ApiSpec = error("the spec itself failed")

/** A member of an `object`. */
object Specs {
    fun spec(): ApiSpec = ApiSpec("Bookmarks", listOf("https://bookmarks.test"))
}

/** A member of a class the plugin has to instantiate. */
class Holder {
    fun spec(): ApiSpec = ApiSpec("Reports")
}

/** No no-argument constructor, so nothing the plugin can call. */
class Uninstantiable(private val port: Int) {
    fun spec(): ApiSpec = ApiSpec("Never $port")
}
