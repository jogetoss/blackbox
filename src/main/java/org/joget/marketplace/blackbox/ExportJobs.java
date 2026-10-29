package org.joget.marketplace.blackbox;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * App exports run here, one at a time on a background thread, so no request waits for them past a proxy's timeout:
 * recording-stop and create wait a short while, then answer and leave the export to finish on its own. The checks that
 * need the request (who may have which app exported) are made before a job is queued.
 *
 * Each job runs with the profile (and user) of the request that queued it (PluginThread), not the thread's: with virtual
 * hosts on, the one export thread serves every tenant. stop() (plugin reload or shutdown) drops the jobs that haven't
 * started, saving each as failed (its dropped callback); a running one is interrupted but ends (or fails) on its own and saves
 * its end state, even after a reload. An "exporting" state from this node older than the JVM is {@link #lost} (a
 * restart or crash ended its job; the readers then save it as failed).
 */
final class ExportJobs {

    private static ExecutorService jobs;
    private static volatile long startedAt;
    private static volatile String node;
    /** Wraps a job with the request's profile and user; tests (no Joget running) swap it. */
    static java.util.function.UnaryOperator<Runnable> withRequest = PluginThread::new;

    private ExportJobs() {
    }

    static synchronized void start() {
        if (jobs == null) {
            // The JVM's start, not the queue's: a plugin reload leaves the old bundle's running job going
            startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
            node = ServerCapture.nodeName();
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
            for (Runnable r : jobs.shutdownNow()) {
                if (r instanceof Job) {
                    ((Job) r).dropped();
                }
            }
            jobs = null;
        }
    }

    /** The appExport state of a job about to be queued on this node. */
    static JSONObject exporting() throws JSONException {
        return new JSONObject().put("state", "exporting").put("at", System.currentTimeMillis()).put("node", currentNode());
    }

    /**
     * True when state says exporting but its job is gone: queued on this node before this JVM started (a restart or
     * crash). States without a node (older versions) count as this node's.
     */
    static boolean lost(JSONObject state) {
        if (state == null || !"exporting".equals(state.optString("state")) || startedAt == 0) {
            return false;
        }
        String on = state.optString("node");
        return (on.isEmpty() || on.equals(currentNode())) && state.optLong("at") < startedAt;
    }

    private static String currentNode() {
        String n = node;
        return n != null ? n : ServerCapture.nodeName();
    }

    /**
     * Queues a job; runs it on the calling thread when the queue is stopped (plugin stopping). dropped saves the job as
     * failed when stop() drops it before it starts; it must be quick (it runs on the stopping thread).
     */
    static void submit(Runnable job, Runnable dropped) {
        ExecutorService ex;
        synchronized (ExportJobs.class) {
            ex = jobs;
        }
        if (ex == null) {
            safely(job, "App export job failed: ");
            return;
        }
        // Made here, on the request's thread: they keep the request's profile and user; run() sets them for the job
        Job queued = new Job(withRequest.apply(() -> safely(job, "App export job failed: ")),
                withRequest.apply(() -> safely(dropped, "Could not save a dropped app export job: ")));
        try {
            ex.execute(queued);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            safely(job, "App export job failed: ");
        }
    }

    private static void safely(Runnable r, String warning) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            LogUtil.warn(ExportJobs.class.getName(), warning + e);
        }
    }

    /** A queued job and what saves it as failed if stop() drops it. */
    private static final class Job implements Runnable {
        private final Runnable job;
        private final Runnable dropped;

        Job(Runnable job, Runnable dropped) {
            this.job = job;
            this.dropped = dropped;
        }

        @Override
        public void run() {
            job.run();
        }

        void dropped() {
            dropped.run();
        }
    }
}
