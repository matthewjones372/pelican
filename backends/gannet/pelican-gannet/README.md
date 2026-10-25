# pelican-gannet

> [!WARNING]
> **Experimental. Do not use it in production.**
>
> - It is on `main` only and in no Pelican release. Gannet itself is a `0.1.0-SNAPSHOT`.
> - No HTTP/2 or HTTP/3, no compression. TLS is Gannet's own and as new as the rest.
> - Linux 6.1 or later only. Gannet uses a JDK internal (`jdk.internal.vm.Continuation`) that any JDK release may
>   change or remove.
> - Its API, and Gannet's, may change at any time.
>
> For a production service, use [`pelican-jetty`](../../jetty/pelican-jetty) or [`pelican-pekko`](../../pekko).

Serves Pelican endpoint descriptions on [Gannet](https://github.com/matthewjones372/gannet), an HTTP/1.1 server
built directly on Linux's io_uring: one thread and one ring per CPU core, and each connection a lightweight
continuation. Gannet is the server underneath; this module is the interpreter that turns an `Api` into a Gannet
handler. Handlers stay plain blocking code.

## Requirements

- Linux 6.1 or later, with io_uring allowed. Docker's default seccomp profile blocks it, and so does
  `kernel.io_uring_disabled=2`.
- JDK 25.
- Two flags on every JVM that runs it (Gannet's `GannetSupport.FLAGS` has the same string):

  ```
  --enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
  ```

`start` throws Gannet's `GannetUnavailableException`, naming whatever is missing, when a machine or JVM can't run it.

## Use

The binders are `pelican-jetty`'s, with the same names and types, so moving a service over is an import swap.

```kotlin
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.gannet.streamedNow

val routes = listOf(
    getOrder handledNow { id -> store.order(id) },
    streamOrders streamedNow { store.orders().asSequence() },
)

api(routes, JacksonCodecs).start(port = 8080).use { it.block() }
```

`start` takes a `GannetConfig` for Gannet's sizing and limits. Its `tls` serves the port over TLS only, and the
server's `baseUrl` is then `https`:

```kotlin
import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetTls
import java.nio.file.Path

val tls = GannetTls(Path.of("server.p12"), password)
api(routes, JacksonCodecs).start(port = 8443, config = GannetConfig(tls = tls))
```

## Where handlers run

| `Handlers` | where a synchronous handler runs |
|---|---|
| `Handlers.default` | a virtual thread: safe for code that blocks |
| `Handlers.on(executor)` | your executor |
| `Handlers.onDispatcher` | the core's own thread: the fastest, but a handler that blocks stalls every connection on that core |

## Testing

`pelican-test-gannet` gives the typed client. Gannet has no in-memory connector, so `onLoopback()` starts the API on
a loopback port and stops it when the client closes:

```kotlin
import io.github.matthewjones372.pelican.test.gannet.onLoopback

api(routes, JacksonCodecs).onLoopback().use { app -> app.call(getOrder, 1L) }
```

## More

- [The reference section](../../../docs/reference.md#on-gannet): composing handlers, streams, and how it differs
  from Jetty.
- `pelican-gannet-docs` serves the OpenAPI document and a docs UI on it.
- Spec [0088](../../../specs/0088-served-on-io-uring.md) describes the interpreter;
  [Gannet's README](https://github.com/matthewjones372/gannet) describes the server.
