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

final class ExportJobs {

    static final long MAX_EXPORT_MS = 2L * 3600 * 1000;
    static final long DROPPED_WAIT_MS = 5000;

    private static ThreadPoolExecutor jobs;
    private static volatile long startedAt;
    private static volatile String node;
    static java.util.function.UnaryOperator<Runnable> withRequest = PluginThread::new;

    private ExportJobs() {
    }

    static synchronized void start() {
        if (jobs == null) {
            startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
            node = ServerCapture.nodeName();
            jobs = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "BlackBox app export");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
    }

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

    static JSONObject exporting() throws JSONException {
        return new JSONObject().put("state", "exporting").put("at", System.currentTimeMillis()).put("node", currentNode());
    }

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

    static JSONObject lostState() throws JSONException {
        return new JSONObject().put("state", "failed").put("reason", "lost");
    }

    static boolean wasLost(JSONObject state) {
        return state != null && "failed".equals(state.optString("state")) && "lost".equals(state.optString("reason"));
    }

    private static String currentNode() {
        String n = node;
        return n != null ? n : ServerCapture.nodeName();
    }

    static void submit(Runnable job, Runnable dropped) {
        ThreadPoolExecutor ex;
        synchronized (ExportJobs.class) {
            ex = jobs;
        }
        if (ex == null) {
            safely(job, "App export job failed: ");
            return;
        }
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
