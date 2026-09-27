package example.handlers

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core, and compiles against Pekko
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC3")
 *         // Pekko itself: Pelican ships no Scala cross-build, so name yours
 *         implementation(platform("org.apache.pekko:pekko-bom_2.13:1.2.1"))
 *         implementation("org.apache.pekko:pekko-actor-typed_2.13")
 *         implementation("org.apache.pekko:pekko-stream_2.13")
 *         implementation("org.apache.pekko:pekko-http_2.13:1.3.0")
 *     }
 */

/*
 * The three calls in "Where a handler runs" from the README, verbatim, so they
 * cannot rot: if this file stops compiling, the README is wrong.
 */

import example.ordersApi
import io.github.matthewjones372.pelican.pekko.Handlers
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import java.util.concurrent.Executors

fun onVirtualThreads(): PelicanServer =
    ordersApi().start(port = 8080) // a virtual thread per request

fun onTheDispatcher(): PelicanServer =
    ordersApi().start(port = 8080, handlers = Handlers.onDispatcher) // no hop, for handlers that never block

fun onAPoolOfYourOwn(): PelicanServer =
    ordersApi().start(port = 8080, handlers = Executors.newCachedThreadPool()) // platform threads, if a driver pins
