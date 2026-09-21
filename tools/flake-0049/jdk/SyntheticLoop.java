import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.server.Directives;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replicates PelicanServer.start/stop out of process: bind a route, then
 * unbind -> terminate -> whenTerminated, joined from the caller.
 *
 * A NEGATIVE result, kept so nobody rebuilds it. ~48,000 iterations across JDK
 * 21 and 25 — parallelism pinned to two, CPU burners, a 1ms dispatcher
 * shutdown-timeout, ticks stretched to 200ms — never reproduced spec 0049. The
 * race wants the dispatcher's *scheduled* shutdown to land inside the
 * scheduler's close, and a tight loop never idles long enough to arrange it.
 * soak.sh, which runs the real suite, is the shape that has a chance.
 *
 *   CP=$(tools/flake-0049/probe/build-probe.sh --print-classpath)
 *   java -cp "$CP" -Druns=2000 -Dpar=2 -Dworkers=4 -Dload=4 \
 *        -Ddst=1ms -Dtick=10ms -DidleMs=120 tools/flake-0049/jdk/SyntheticLoop.java
 */
public final class SyntheticLoop {
    static final AtomicInteger runs = new AtomicInteger();
    static final AtomicInteger hits = new AtomicInteger();
    static final AtomicBoolean stop = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        int total = Integer.parseInt(System.getProperty("runs", "2000"));
        int par = Integer.parseInt(System.getProperty("par", "2"));
        int workers = Integer.parseInt(System.getProperty("workers", "4"));
        int load = Integer.parseInt(System.getProperty("load", "4"));

        Config cfg = ConfigFactory.parseString(
                "pekko.actor.default-dispatcher.fork-join-executor.parallelism-min=" + par + "\n" +
                "pekko.actor.default-dispatcher.fork-join-executor.parallelism-max=" + par + "\n" +
                "pekko.actor.default-dispatcher.executor=\"" + System.getProperty("exec","fork-join-executor") + "\"\n" +
                "pekko.actor.default-dispatcher.shutdown-timeout=" + System.getProperty("dst","1ms") + "\n" +
                "pekko.scheduler.tick-duration=" + System.getProperty("tick","10ms") + "\n" +
                "pekko.loggers=[]\npekko.loglevel=OFF\npekko.stdout-loglevel=OFF\n")
            .withFallback(ConfigFactory.load());

        for (int i = 0; i < load; i++) {
            Thread t = new Thread(() -> { long x = 0; while (!stop.get()) x += System.nanoTime(); }, "burner-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread[] ws = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            ws[i] = new Thread(() -> worker(cfg, total), "worker-" + i);
            ws[i].start();
        }
        for (Thread t : ws) t.join();
        stop.set(true);

        System.out.println("DONE runs=" + runs.get() + " hits=" + hits.get()
            + " java=" + System.getProperty("java.version") + " par=" + par + " workers=" + workers);
        System.exit(hits.get() > 0 ? 1 : 0);
    }

    static void worker(Config cfg, int total) {
        while (runs.get() < total && hits.get() < 3) {
            int n = runs.incrementAndGet();
            ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "repro", cfg);
            try {
                Route route = Directives.complete("ok");
                ServerBinding binding = Http.get(system).newServerAt("127.0.0.1", 0)
                    .bind(route).toCompletableFuture().join();
                int jitter = Integer.getInteger("idleMs", 0);
                if (jitter > 0) {
                    try { Thread.sleep(jitter / 2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(jitter)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                CompletionStage<Object> stopped = binding.unbind().thenCompose(u -> {
                    system.terminate();
                    return system.getWhenTerminated().thenApply(x -> (Object) x);
                });
                stopped.toCompletableFuture().join();
            } catch (CompletionException e) {
                if (isInterrupt(e)) {
                    hits.incrementAndGet();
                    synchronized (SyntheticLoop.class) {
                        System.out.println("HIT at run " + n + " on " + Thread.currentThread().getName());
                        e.printStackTrace(System.out);
                        dumpThreads();
                    }
                } else throw e;
            }
            if (n % 100 == 0) System.out.println("run " + n + " hits=" + hits.get());
        }
    }

    static boolean isInterrupt(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c instanceof InterruptedException) return true;
        return false;
    }

    static void dumpThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t.getName().startsWith("repro-") || t.getName().startsWith("worker-"))
                System.out.println("  thread " + t.getName() + " interrupted=" + t.isInterrupted() + " state=" + t.getState());
        }
    }
}
