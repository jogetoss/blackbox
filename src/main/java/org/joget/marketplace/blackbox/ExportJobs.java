package org.joget.marketplace.blackbox;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * started, saving each as failed (its dropped callback, run on a thread of its own); the running one isn't interrupted:
 * it ends (or fails) on its own and saves its end state, even after a reload. An "exporting" state is {@link #lost}
 * when queued on this node before the JVM started (a restart or crash ended its job) or older than MAX_EXPORT_MS (a
 * job that died without saving its end); the readers then save it as failed, reason "lost", which a late end still
 * replaces.
 */
final class ExportJobs {

    /** An export running longer than this counts as lost (its job died without saving its end). */
    static final long MAX_EXPORT_MS = 2L * 3600 * 1000;
    /** How long stop() waits for the dropped callbacks, so they run while the bundle is still installed. */
    static final long DROPPED_WAIT_MS = 5000;

    private static ThreadPoolExecutor jobs;
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
            jobs = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "BlackBox app export");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // background work: requests come first
                return t;
            });
        }
    }

    /**
     * Drops the queued jobs and lets the running one finish (not interrupted: it must still be able to lock and save).
     * The dropped callbacks run on a thread of their own, not the stopping one: that is Joget's (the Manage Plugins or
     * app import request) and a PluginThread's run() resets the thread's request and user when it ends.
     */
    static synchronized void stop() {
        if (jobs == null) {
            return;
        }
        List<Runnable> queued = new ArrayList<>();
        jobs.getQueue().drainTo(queued);
        jobs.shutdown();
        jobs = null;
        List<Runnable> dropped = new ArrayList<>();
        for (Runnable r : queued) {
            if (r instanceof Job) {
                dropped.add(((Job) r).dropped);
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> dropped.forEach(Runnable::run), "BlackBox dropped app exports");
        t.setDaemon(true);
        t.start();
        try {
            t.join(DROPPED_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            LogUtil.warn(ExportJobs.class.getName(), "Dropped app export jobs still being saved as failed after " + DROPPED_WAIT_MS + " ms");
        }
    }

    /** The appExport state of a job about to be queued on this node. */
    static JSONObject exporting() throws JSONException {
        return new JSONObject().put("state", "exporting").put("at", System.currentTimeMillis()).put("node", currentNode());
    }

    /**
     * True when state says exporting but its job is gone: queued on this node before this JVM started (a restart or
     * crash), or on any node more than MAX_EXPORT_MS ago (the job died without saving its end, e.g. the old bundle's
     * after a plugin reload). States without a node (older versions) count as this node's.
     */
    static boolean lost(JSONObject state) {
        if (state == null || !"exporting".equals(state.optString("state"))) {
            return false;
        }
        long at = state.optLong("at");
        if (at < System.currentTimeMillis() - MAX_EXPORT_MS) {
            return true;
        }
        String on = state.optString("node");
        return startedAt != 0 && (on.isEmpty() || on.equals(currentNode())) && at < startedAt;
    }

    /** The state a lost export is saved as: failed, but a late end of its job still replaces it. */
    static JSONObject lostState() throws JSONException {
        return new JSONObject().put("state", "failed").put("reason", "lost");
    }

    /** True for a state saved by {@link #lostState}. */
    static boolean wasLost(JSONObject state) {
        return state != null && "failed".equals(state.optString("state")) && "lost".equals(state.optString("reason"));
    }

    private static String currentNode() {
        String n = node;
        return n != null ? n : ServerCapture.nodeName();
    }

    /**
     * Queues a job; runs it on the calling thread when the queue is stopped (plugin stopping). dropped saves the job as
     * failed when stop() drops it before it starts; it should be quick (stop() waits DROPPED_WAIT_MS for all of them).
     */
    static void submit(Runnable job, Runnable dropped) {
        ThreadPoolExecutor ex;
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

    }
}
