package org.joget.marketplace.blackbox;

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
 * hosts on, the one export thread serves every tenant. Jobs don't survive a restart: stop() drops them, and an
 * "exporting" state from this node older than start() is {@link #lost} (the readers then save it as failed).
 */
final class ExportJobs {

    private static ExecutorService jobs;
    private static volatile long startedAt;
    private static volatile String node;

    private ExportJobs() {
    }

    static synchronized void start() {
        if (jobs == null) {
            startedAt = System.currentTimeMillis();
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
            jobs.shutdownNow();
            jobs = null;
        }
    }

    /** The appExport state of a job about to be queued on this node. */
    static JSONObject exporting() throws JSONException {
        return new JSONObject().put("state", "exporting").put("at", System.currentTimeMillis()).put("node", currentNode());
    }

    /**
     * True when state says exporting but its job is gone: queued on this node before the queue last started (a restart
     * or plugin reload dropped it). States without a node (older versions) count as this node's.
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
        // Made here, on the request's thread: it keeps the request's profile and user; run() sets them for the job
        PluginThread withProfile = new PluginThread(safe);
        try {
            ex.execute(withProfile::run);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            safe.run();
        }
    }
}
