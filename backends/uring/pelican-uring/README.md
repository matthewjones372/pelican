# pelican-uring

> [!WARNING]
> **Experimental. Do not use it in production.**
>
> - It is on `main` only and in no release.
> - No TLS, no HTTP/2 or HTTP/3, no compression.
> - Linux 6.1 or later only. It uses a JDK internal (`jdk.internal.vm.Continuation`) that any JDK release may
>   change or remove.
> - Its API may change at any time.
>
> For a production service, use [`pelican-jetty`](../../jetty/pelican-jetty) or [`pelican-pekko`](../../pekko).

Serves Pelican endpoint descriptions on an HTTP/1.1 server built directly on Linux's io_uring: one thread and one
ring per CPU core, and each connection a lightweight continuation. Handlers stay plain blocking code.

## Requirements

- Linux 6.1 or later, with io_uring allowed. Docker's default seccomp profile blocks it, and so does
  `kernel.io_uring_disabled=2`.
- JDK 25.
- Two flags on every JVM that runs it (`UringSupport.FLAGS` has the same string):

  ```
  --enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
  ```

`start` throws `UringUnavailableException`, naming whatever is missing, when a machine or JVM can't run it.

## Use

The binders are `pelican-jetty`'s, with the same names and types, so moving a service over is an import swap.

```kotlin
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.streamedNow

val routes = listOf(
    getOrder handledNow { id -> store.order(id) },
    streamOrders streamedNow { store.orders().asSequence() },
)

api(routes, JacksonCodecs).start(port = 8080).use { it.block() }
```

## Where handlers run

| `Handlers` | where a synchronous handler runs |
|---|---|
| `Handlers.default` | a virtual thread: safe for code that blocks |
| `Handlers.on(executor)` | your executor |
| `Handlers.onDispatcher` | the core's own thread: the fastest, but a handler that blocks stalls every connection on that core |

## More

- [The reference section](../../../docs/reference.md#on-io_uring): composing handlers, streams, and how it differs
  from Jetty.
- `pelican-uring-docs` serves the OpenAPI document and a docs UI on it.
- Specs [0087](../../../specs/0087-a-runtime-on-io-uring.md) and [0088](../../../specs/0088-served-on-io-uring.md)
  describe the design.
