package org.joget.marketplace.blackbox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.joget.commons.util.LogUtil;

/**
 * App exports run here, one at a time on a background thread, so no request waits for them past a proxy's timeout:
 * recording-stop and create wait a short while, then answer and leave the export to finish on its own. The checks that
 * need the request (who may have which app exported) are made before a job is queued.
 */
final class ExportJobs {

    private static ExecutorService jobs;

    private ExportJobs() {
    }

    static synchronized void start() {
        if (jobs == null) {
            jobs = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BlackBox app export");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // background work: requests come first
                return t;
            });
        }
    }

    static synchronized void stop() {
        if (jobs != null) {
            jobs.shutdownNow();
            jobs = null;
        }
    }

    /** Queues a job; runs it on the calling thread when the queue is stopped (plugin stopping). */
    static void submit(Runnable job) {
        ExecutorService ex;
        synchronized (ExportJobs.class) {
            ex = jobs;
        }
        Runnable safe = () -> {
            try {
                job.run();
            } catch (RuntimeException | LinkageError e) {
                LogUtil.warn(ExportJobs.class.getName(), "App export job failed: " + e);
            }
        };
        if (ex == null) {
            safe.run();
            return;
        }
        try {
            ex.execute(safe);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            safe.run();
        }
    }
}
