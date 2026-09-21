import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Names the interrupter in spec 0049: does a plain ForkJoinPool.shutdown() —
 * which is all Pekko's Dispatcher.shutdown() does — interrupt a worker that is
 * parked inside managedBlock?
 *
 * Run it on each JDK the matrix names:
 *   $JAVA_HOME/bin/java tools/flake-0049/jdk/StopCheck.java
 *
 * Observed 2026-09-21:
 *   21.0.9    across: InterruptedException at AbstractQueuedSynchronizer.java:1167  <- the CI frame
 *             after:  block 0, same frame
 *   23.0.1    across: returned normally    after: no InterruptedException in 200 blocks
 *   25.0.4.1  across: returned normally    after: no InterruptedException in 200 blocks
 *
 * An InterruptedException with no frames at all would be the STOP check JDK 23
 * added inside compensatedBlock, not a real interrupt. It has never appeared here.
 */
public final class StopCheck {

    public static void main(String[] args) throws Exception {
        System.out.println("java " + System.getProperty("java.version"));
        System.out.println("  blocked across a plain shutdown(): " + across());
        System.out.println("  blocking after one:                " + after());
        System.exit(0);
    }

    /** A worker is inside managedBlock when shutdown() arrives. */
    private static String across() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        CountDownLatch blocking = new CountDownLatch(1), done = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();

        pool.execute(() -> {
            result.append(block(() -> { blocking.countDown(); park(600); }));
            done.countDown();
        });
        blocking.await();
        Thread.sleep(150);
        pool.shutdown();
        done.await(10, TimeUnit.SECONDS);
        return result.toString();
    }

    /** The pool is already shut down, and is driven towards STOP while a worker blocks. */
    private static String after() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch started = new CountDownLatch(1), done = new CountDownLatch(1);
        StringBuilder result = new StringBuilder("no InterruptedException in 200 blocks");

        pool.execute(() -> {
            started.countDown();
            for (int i = 0; i < 200; i++) {
                String r = block(() -> park(5));
                if (!r.equals("returned normally")) { result.setLength(0); result.append("block ").append(i).append(": ").append(r); break; }
            }
            done.countDown();
        });
        started.await();
        pool.shutdown();
        // isQuiescent() calls quiescent(), which is what sets STOP once SHUTDOWN is on.
        Thread poker = new Thread(() -> { while (done.getCount() > 0) pool.isQuiescent(); });
        poker.setDaemon(true);
        poker.start();
        done.await(30, TimeUnit.SECONDS);
        return result.toString();
    }

    private interface Body { void run() throws InterruptedException; }

    private static String block(Body body) {
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                public boolean block() throws InterruptedException { body.run(); return true; }
                public boolean isReleasable() { return false; }
            });
            return "returned normally";
        } catch (InterruptedException e) {
            StackTraceElement[] st = e.getStackTrace();
            return st.length == 0
                ? "InterruptedException with NO frames (compensatedBlock's STOP check)"
                : "InterruptedException at " + st[0];
        }
    }

    private static void park(long millis) throws InterruptedException {
        new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
    }
}
