import com.typesafe.config.Config;
import org.apache.pekko.dispatch.DispatcherPrerequisites;
import org.apache.pekko.dispatch.ExecutorServiceConfigurator;
import org.apache.pekko.dispatch.ExecutorServiceFactory;
import org.apache.pekko.dispatch.MonitorableThreadFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A default-dispatcher executor whose worker threads record who interrupts them.
 * Otherwise a plain ForkJoinPool of Pekko's own worker threads.
 */
public final class ProbeConfigurator extends ExecutorServiceConfigurator {
    static final AtomicInteger interrupts = new AtomicInteger();
    static final AtomicInteger created = new AtomicInteger();
    static final String LOG = System.getProperty("probe.log", "build/flake-0049/probe.log");
    private final int parallelism;

    public ProbeConfigurator(Config config, DispatcherPrerequisites prerequisites) {
        super(config, prerequisites);
        int p = config.hasPath("fork-join-executor.parallelism-max")
            ? config.getInt("fork-join-executor.parallelism-max") : 4;
        this.parallelism = Math.max(2, Math.min(p, Runtime.getRuntime().availableProcessors()));
    }

    static synchronized void log(String s) {
        try (PrintWriter w = new PrintWriter(new FileWriter(LOG, true))) { w.println(s); }
        catch (IOException ignored) { }
    }

    public static final class ProbeWorker extends MonitorableThreadFactory.PekkoForkJoinWorkerThread {
        ProbeWorker(ForkJoinPool pool) {
            super(pool);
            if (created.getAndIncrement() == 0) log("PROBE ACTIVE pid=" + ProcessHandle.current().pid() + " java.version=" + System.getProperty("java.version") + " java.home=" + System.getProperty("java.home"));
        }

        @Override
        public void interrupt() {
            Thread by = Thread.currentThread();
            StringBuilder sb = new StringBuilder("INTERRUPT #" + interrupts.incrementAndGet()
                + " target=" + getName() + " by=" + by.getName());
            for (StackTraceElement e : by.getStackTrace()) sb.append("\n    at ").append(e);
            log(sb.toString());
            super.interrupt();
        }
    }

    @Override
    public ExecutorServiceFactory createExecutorServiceFactory(String id, ThreadFactory threadFactory) {
        return () -> (ExecutorService) new ForkJoinPool(parallelism, ProbeWorker::new, (t, e) -> {}, true);
    }
}
