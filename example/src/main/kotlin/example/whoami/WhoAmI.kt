package example.whoami

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty:1.0.0")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
 *     }
 */

/*
 * Spec 0093's caller on its own: one endpoint that needs one, so the 401
 * `authenticatedBy` declares is what `generateWhoAmIClient` generates from and
 * a call can be refused with it. Its own little API for the reason adoption is.
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow

val bearer = bearerAuth(bearerFormat = null, name = "bearer")

/** Who is calling, as the subject their token names. */
val subject = authenticated(bearer, Identity::subject)

val whoAmI = endpoint {
    get("me")
    operationId = "whoAmI"
    summary = "Who the token names"
    authenticatedBy(subject)
    text()
}

/** The one token this service issued. Any other is refused, saying so, and never repeated back. */
val tokens = Authenticator { credentials ->
    when (credentials.bearerToken()) {
        null -> null
        ADA_TOKEN -> Identity("ada")
        else -> throw Unauthenticated("That token is not one this service issued")
    }
}

const val ADA_TOKEN = "ada-token"

fun whoAmIApi(): Api = api(endpoints = listOf(whoAmI handledNow { this[subject] }), codecs = JacksonCodecs) {
    authenticate(bearer, tokens)
}
