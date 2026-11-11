package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SetupManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Copies the Glowroot traces of a recording's requests into the recording, on the node that served each request.
 *
 * When a tagged request ends and Glowroot would have kept a trace for it (slower than Glowroot's own threshold, or an
 * error), a background job looks the trace up in the local Glowroot and saves its export ({@link TraceStore}). Glowroot
 * stores a trace only after the request has fully ended, so the job tries again after 2, 5, 15, 30 and 60 seconds,
 * then every minute until {@link #GIVE_UP_MS} after the request ended. Requests still running past the threshold are
 * listed as pending at once (and again every few minutes), so the replay page can say a trace is on its way.
 *
 * The files go to the capture's pending folder, or to the recording's folder once it is uploaded.
 *
 * Retry (replay page): only the node that served a request can read its trace (its own Glowroot), and the click may
 * reach any node. So {@link #requestRetry} marks the failed traces pending and leaves one request file per node in
 * {@code <wflow>/blackbox/retry/<recording>@<node>.json}; each node picks up its own every {@link #RETRY_SCAN_S} s.
 */
final class TraceCollector {

    static final long GIVE_UP_MS = 10 * 60 * 1000L;
    private static final long[] RETRY_MS = {2000, 5000, 15000, 30000, 60000};
    private static final long HEARTBEAT_MS = 3 * 60 * 1000L; // < TraceStore.STALE_MS
    private static final long DEFAULT_THRESHOLD_MS = 2000;   // when Glowroot can't be asked
    private static final int MAX_PER_CAPTURE = 100;          // traces per capture on one node
    private static final long RETRY_SCAN_S = 20;
    static final String RETRY_PREFIX = "Retry requested";
    private static final String NODE = ServerCapture.nodeName();
    private static final Pattern NODE_NAME = Pattern.compile("^[A-Za-z0-9.-]+$");

    // Other users' traces (capture option otherTraces): copied once, OTHERS_AFTER_MS after the stop, from each node the
    // recording reached; at most MAX_OTHERS per node
    static final long OTHERS_AFTER_MS = 30_000L;  // Glowroot stores a trace only after its request ended
    static final int MAX_OTHERS = 200;
    static final long OTHERS_BUDGET_MS = 5 * 60_000L; // per recording and node: a slow Glowroot can't hold the copy up
    private static final long OTHERS_MAX_WAIT_MS = 3 * 3600 * 1000L; // a capture never stopped
    private static final String[] OTHER_TYPES = {"Web", "Background"};
    private static final Map<String, Long> OTHERS = new ConcurrentHashMap<>(); // capture id -> watched since
    // capture id -> when this node last wrote its "pending" note; rewritten well within TraceStore.STALE_MS
    private static final Map<String, Long> OTHERS_NOTED = new ConcurrentHashMap<>();
    static final long OTHERS_NOTE_EVERY_MS = 2 * 60_000L;
    // Saved in the capture's meta.json while this node still owes its other traces, so a restart picks them up again
    static final String OTHERS_PENDING = "othersPending-" + NODE;

    private static final Map<Running, Boolean> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, GlowrootClient> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile ScheduledExecutorService jobs;
    // Other users' traces have their own thread: up to MAX_OTHERS * 3 Glowroot calls per recording must not delay the
    // heartbeat, lookups and retries on jobs
    private static volatile ScheduledExecutorService othersJobs;

    private TraceCollector() {
    }

    static synchronized void start() {
        if (jobs != null) {
            return;
        }
        jobs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlackBox traces");
            t.setDaemon(true);
            return t;
        });
        jobs.scheduleWithFixedDelay(TraceCollector::heartbeat, 2, 2, TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(() -> COUNTS.clear(), 1, 1, TimeUnit.DAYS);
        jobs.scheduleWithFixedDelay(TraceCollector::retryScan, RETRY_SCAN_S, RETRY_SCAN_S, TimeUnit.SECONDS);
        othersJobs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlackBox other traces");
            t.setDaemon(true);
            return t;
        });
        othersJobs.schedule(() -> {
            try {
                recoverOthers(ServerCapture.store(), System.currentTimeMillis());
            } catch (IOException | RuntimeException e) {
                LogUtil.warn(TraceCollector.class.getName(), "Could not check for other users' traces still to copy: " + e);
            }
        }, 1, TimeUnit.SECONDS);
        othersJobs.scheduleWithFixedDelay(TraceCollector::othersTick, 10, 10, TimeUnit.SECONDS);
    }

    static synchronized void stop() {
        if (jobs != null) {
            jobs.shutdownNow();
            jobs = null;
        }
        if (othersJobs != null) {
            othersJobs.shutdownNow();
            othersJobs = null;
        }
        RUNNING.clear();
        CLIENTS.clear();
        OTHERS.clear();
        OTHERS_NOTED.clear();
    }

    // ---------- other users' traces ----------

    /** The capture is open on this node: if it asked for other users' traces, copy them after the stop. */
    static void watchOthers(ServerCapture.Capture capture) {
        if (othersJobs != null && capture.traces && capture.otherTraces
                && OTHERS.putIfAbsent(capture.id, System.currentTimeMillis()) == null) {
            markOthers(capture.id, true);
            noteOthers(capture.id, System.currentTimeMillis()); // from the start: the replay page polls while it is pending
        }
    }

    /** This node's "pending" note: other users' traces are still to come. */
    private static void noteOthers(String id, long now) {
        OTHERS_NOTED.put(id, now);
        writeOther(id, otherNote(id, "pending", "Other users' traces on " + NODE + " are copied about "
                + OTHERS_AFTER_MS / 1000 + " s after the stop"));
    }

    /** After a restart: captures whose meta says this node still owes their other traces are watched again. */
    static List<String> recoverOthers(ServerCaptureStore store, long now) throws IOException {
        List<String> watched = new ArrayList<>();
        for (String id : store.ids()) {
            JSONObject meta = store.readMeta(id);
            if (meta != null && meta.optBoolean(OTHERS_PENDING) && OTHERS.putIfAbsent(id, now) == null) {
                watched.add(id);
                noteOthers(id, now);
            }
        }
        return watched;
    }

    private static void markOthers(String id, boolean pending) {
        try {
            ServerCapture.store().put(id, OTHERS_PENDING, pending);
        } catch (IOException e) {
            LogUtil.warn(TraceCollector.class.getName(), "Could not save the other traces' state of capture " + id + ": " + e.getMessage());
        }
    }

    /**
     * Every 10 s: drops captures that are gone or never stopped, keeps each waiting capture's "pending" note fresh, and
     * copies at most one capture that is due, so the notes of the others are kept fresh between copies.
     */
    private static void othersTick() {
        long now = System.currentTimeMillis();
        String due = null;
        JSONObject dueMeta = null;
        for (Map.Entry<String, Long> w : OTHERS.entrySet()) {
            String id = w.getKey();
            JSONObject meta = ServerCapture.store().readMeta(id);
            if (meta == null) {
                OTHERS.remove(id); // expired or deleted
                OTHERS_NOTED.remove(id);
            } else if (!meta.has("stoppedAt") && now - w.getValue() > OTHERS_MAX_WAIT_MS) {
                OTHERS.remove(id); // never stopped
                OTHERS_NOTED.remove(id);
                markOthers(id, false);
                writeOther(id, otherNote(id, "none", "The recording never stopped: other users' traces were not copied"));
            } else {
                if (now - OTHERS_NOTED.getOrDefault(id, 0L) >= OTHERS_NOTE_EVERY_MS) {
                    noteOthers(id, now);
                }
                if (due == null && meta.has("stoppedAt") && now >= meta.optLong("stoppedAt") + OTHERS_AFTER_MS) {
                    due = id;
                    dueMeta = meta;
                }
            }
        }
        if (due == null) {
            return;
        }
        try {
            copyOthers(due, dueMeta);
        } catch (IOException | RuntimeException e) {
            if (!Thread.currentThread().isInterrupted()) {
                writeOther(due, otherNote(due, "failed", "Could not read other users' traces from Glowroot: " + e.getMessage()));
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            return; // the plugin is stopping: still pending in meta.json, copied again after the restart
        }
        OTHERS.remove(due);
        OTHERS_NOTED.remove(due);
        markOthers(due, false);
    }

    /**
     * Copies the traces Glowroot kept of every request on this node that overlapped the recording and is not one of
     * its own (those have the capture's tag): other users, scheduler and background jobs. Marked "other": the viewer
     * lists them apart, never as the trace of a recorded request.
     */
    private static void copyOthers(String id, JSONObject meta) throws IOException {
        GlowrootClient g = client(meta.optString("glowrootUrl"));
        if (g == null) {
            writeOther(id, otherNote(id, "failed", "Glowroot is not running on " + NODE));
            return;
        }
        // The folder is looked up per trace: the upload may move the capture to its recording during the copy
        collectOthers(g, id, meta.optLong("startedAt"), meta.optLong("stoppedAt"), () -> dir(id), e -> writeOther(id, e),
                System.currentTimeMillis() + OTHERS_BUDGET_MS);
    }

    /**
     * copyOthers without the capture store: each other trace (and the limit note) goes to out; files to dir.get(). Stops
     * copying at MAX_OTHERS or at deadline (epoch ms); what is left is only counted, with no further Glowroot call.
     */
    static void collectOthers(GlowrootClient g, String id, long from, long to, java.util.function.Supplier<Path> dir,
            java.util.function.Consumer<JSONObject> out, long deadline) throws IOException {
        int copied = 0;
        int left = 0; // not looked at: may include the recording's own
        boolean late = false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String type : OTHER_TYPES) { // requests, and scheduler / background jobs
            for (GlowrootClient.Point p : g.points(from, to + OTHERS_AFTER_MS, type)) {
                if (Thread.currentThread().isInterrupted()) {
                    return; // the plugin is stopping; copied again after the restart
                }
                if (!seen.add(p.traceId) || p.endMs - p.durationMs > to) {
                    continue; // listed twice, or started after the stop
                }
                if (copied >= MAX_OTHERS || late || (late = System.currentTimeMillis() > deadline)) {
                    left++;
                    continue;
                }
                JSONObject h;
                try {
                    h = g.header(p.traceId);
                } catch (IOException e) {
                    continue; // listed a moment before its header can be read
                }
                if (TraceStore.tagged(h, id) || h.optLong("startTime") > to) {
                    continue; // the recording's own (find() copies those), or started after the stop
                }
                out.accept(other(g, id, p.traceId, h, dir.get()));
                copied++;
            }
        }
        // Always one note at the end: it replaces the "pending" one
        out.accept(otherNote(id, "none", left == 0 ? copied + " other traces copied from " + NODE
                : (late ? "Glowroot on " + NODE + " was too slow: copying stopped after " + OTHERS_BUDGET_MS / 60_000
                + " minutes. " : "Only the first " + MAX_OTHERS + " other traces on " + NODE + " are kept. ")
                + copied + " copied, up to " + left + " more were not"));
    }

    private static JSONObject other(GlowrootClient g, String captureId, String traceId, JSONObject h, Path dir) throws IOException {
        try {
            JSONObject detail = h.optJSONObject("detail");
            JSONObject e = new JSONObject()
                    .put("key", NODE + "-other-" + traceId)
                    .put("capture", captureId)
                    .put("node", NODE)
                    .put("other", true)
                    .put("ts", h.optLong("startTime"))
                    .put("type", h.optString("transactionType"))
                    .put("method", detail != null ? detail.optString("Request http method") : "")
                    .put("url", h.optString("headline"))
                    .put("user", h.optString("user"))
                    .put("ms", Math.round(h.optLong("durationNanos") / 1e6))
                    .put("state", "saved")
                    .put("updated", System.currentTimeMillis())
                    .put("traceId", traceId);
            if (h.optJSONObject("error") != null) {
                e.put("failed", true);
            }
            JSONObject queries = null;
            if ("yes".equals(h.optString("queriesExistence"))) {
                try {
                    queries = g.queries(traceId);
                } catch (IOException ex) {
                    e.put("reason", "Saved without its SQL: " + ex.getMessage());
                }
            }
            e.put("summary", TraceStore.summary(h, queries));
            if (dir != null) {
                String name = TraceStore.fileName(NODE, traceId);
                try {
                    TraceStore.writeTrace(dir, name, g.exportHtml(traceId));
                    e.put("file", name);
                } catch (IOException ex) {
                    e.put("reason", "Only the summary was saved: " + ex.getMessage());
                }
            }
            return e;
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
    }

    /** One line about this node's other traces as a whole (Glowroot missing, the limit). */
    private static JSONObject otherNote(String captureId, String state, String reason) {
        try {
            return new JSONObject().put("key", NODE + "-other-note").put("capture", captureId).put("node", NODE)
                    .put("other", true).put("ts", 0).put("url", "").put("state", state).put("reason", reason)
                    .put("updated", System.currentTimeMillis());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeOther(String captureId, JSONObject e) {
        Path dir = dir(captureId);
        if (dir != null) {
            write(dir, captureId, null, e);
        }
        // its export may have gone to the pending folder just before the upload moved it
        Path pending = ServerCapture.store().dir(captureId);
        if (!pending.equals(dir) && Files.isDirectory(pending)) {
            try {
                moveIfUploaded(captureId, pending);
            } catch (IOException ex) {
                LogUtil.warn(TraceCollector.class.getName(), "Could not move a trace of capture " + captureId + ": " + ex.getMessage());
            }
        }
    }

    /** A tagged request has started. Returns what to pass to {@link #end}, or null when traces are off. */
    static Running begin(ServerCapture.Capture capture, String method, String url, long start) {
        if (jobs == null || !capture.traces) {
            return null;
        }
        Running r = new Running(capture.id, capture.glowrootUrl, method, url, start);
        RUNNING.put(r, Boolean.TRUE);
        return r;
    }

    static void end(Running r, int status, long ms, Throwable failure) {
        end(r, status, ms, failure, false);
    }

    /** loggedError: the request logged an ERROR line; GlowrootTag.keep() asked Glowroot to keep its trace. */
    static void end(Running r, int status, long ms, Throwable failure, boolean loggedError) {
        if (r == null) {
            return;
        }
        RUNNING.remove(r);
        ScheduledExecutorService ex = jobs;
        if (ex == null) {
            return;
        }
        r.status = status;
        r.ms = ms;
        r.failed = failure != null;
        r.loggedError = loggedError;
        r.ended = System.currentTimeMillis();
        ex.execute(() -> ended(r));
    }

    // ---------- jobs (one thread) ----------

    /** Lists requests still running past the threshold as pending, and keeps them fresh while they run. */
    private static void heartbeat() {
        long now = System.currentTimeMillis();
        for (Running r : RUNNING.keySet()) {
            if (now - r.lastWritten < HEARTBEAT_MS || now - r.start < threshold(r)) {
                continue;
            }
            if (r.lastWritten == 0 && !reserve(r)) {
                RUNNING.remove(r);
                continue;
            }
            r.lastWritten = now;
            write(r, entry(r, "pending", "Request still running (" + (now - r.start) / 1000 + " s so far)"));
        }
    }

    private static void ended(Running r) {
        GlowrootClient g = client(r);
        boolean keeps;
        if (g == null) {
            keeps = r.listed || r.failed || r.loggedError || r.status >= 500 || r.ms >= DEFAULT_THRESHOLD_MS;
            if (keeps && (r.listed || reserve(r))) {
                write(r, entry(r, "failed", "Glowroot is not running on " + NODE));
            }
            return;
        }
        try {
            keeps = r.loggedError || g.settings().keeps(r.ms, r.status, r.failed);
        } catch (IOException e) {
            keeps = r.failed || r.loggedError || r.status >= 500 || r.ms >= DEFAULT_THRESHOLD_MS;
        }
        if (!keeps && !r.listed) {
            return;
        }
        if (!r.listed && !reserve(r)) {
            return;
        }
        write(r, entry(r, "pending", "Waiting for Glowroot to store the trace"));
        attempt(r, 0, null);
    }

    private static void attempt(Running r, int n, String lastError) {
        ScheduledExecutorService ex = jobs;
        if (ex == null) {
            return;
        }
        long delay = n < RETRY_MS.length ? RETRY_MS[n] : 60000;
        long now = System.currentTimeMillis();
        if (now + delay - r.ended > GIVE_UP_MS) {
            write(r, lastError != null
                    ? entry(r, "failed", "Could not read Glowroot: " + lastError)
                    : r.untagged
                    ? entry(r, "none", "Glowroot has a trace of the same request and time, but not tagged with this recording "
                            + "(another user's request, or Glowroot's API was not reachable), so it was not added")
                    : entry(r, "none", "Glowroot kept no trace for this request"));
            return;
        }
        ex.schedule(() -> {
            try {
                if (!find(r)) {
                    attempt(r, n + 1, null);
                }
            } catch (IOException | RuntimeException e) {
                attempt(r, n + 1, e.getMessage());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Looks the request's trace up and saves it. False when Glowroot has none (yet). */
    private static boolean find(Running r) throws IOException {
        GlowrootClient g = client(r);
        if (g == null) {
            throw new IOException("Glowroot is not running on " + NODE);
        }
        JSONObject best = null;
        String bestId = null;
        // Only traces that ended when the request ended and lasted about as long (Glowroot times a little more than
        // the filter), so a busy server (or a 0 ms slow threshold) costs a few header reads, not one per request.
        // ms < 0: the request's end was never seen (a retry of one still running at a restart): search until now.
        long slack = Math.max(1000, r.ms / 5);
        long end = r.start + r.ms;
        // ms < 0: never past GIVE_UP_MS after the start, so a retry can't make Glowroot scan days of traces
        long from = r.ms >= 0 ? end - 3000 : r.start - 1000;
        long to = r.ms >= 0 ? end + 3000 : Math.min(System.currentTimeMillis(), r.start + GIVE_UP_MS) + 1000;
        for (GlowrootClient.Point p : g.points(from, to)) {
            if (r.ms >= 0 && (Math.abs(p.durationMs - r.ms) > slack || Math.abs(p.endMs - end) > 3000)) {
                continue;
            }
            String id = p.traceId;
            JSONObject h;
            try {
                h = g.header(id);
            } catch (IOException e) {
                // Glowroot lists a trace a moment before its header can be read (404): skip it, so one such trace
                // doesn't fail the whole lookup. If it was this request's, the next attempt finds it.
                continue;
            }
            if (!TraceStore.matches(h, r.method, r.url, r.start)) {
                continue;
            }
            if (!TraceStore.tagged(h, r.tag)) {
                r.untagged = true; // another recording's or user's request, or Glowroot's API wasn't reachable
                continue;
            }
            if ((best == null || Math.abs(h.optLong("startTime") - r.start) < Math.abs(best.optLong("startTime") - r.start))) {
                best = h;
                bestId = id;
            }
        }
        if (best == null) {
            return false;
        }
        JSONObject e = entry(r, "saved", "");
        try {
            // Glowroot answers 404 for the queries of a trace without SQL; its header says whether there are any
            JSONObject queries = null;
            if ("yes".equals(best.optString("queriesExistence"))) {
                try {
                    queries = g.queries(bestId);
                } catch (IOException ex) {
                    e.put("reason", "Saved without its SQL: " + ex.getMessage());
                }
            }
            e.put("traceId", bestId).put("summary", TraceStore.summary(best, queries));
            Path dir = dir(r);
            if (dir != null) {
                String name = TraceStore.fileName(NODE, bestId);
                try {
                    TraceStore.writeTrace(dir, name, g.exportHtml(bestId));
                    e.put("file", name);
                } catch (IOException ex) {
                    e.put("reason", "Only the summary was saved: " + ex.getMessage());
                }
            }
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        write(r, e);
        return true;
    }

    // ---------- retry ----------

    /**
     * Marks a recording's failed traces (all, or those in keys) pending again and asks the nodes that served them to
     * look again. Returns how many. Runs on the node that got the click; recDir is the recording's folder.
     */
    static int requestRetry(String recId, Path recDir, Set<String> keys, String glowrootUrl) throws IOException {
        Map<String, Set<String>> byNode = new HashMap<>();
        long now = System.currentTimeMillis();
        JSONArray traces = TraceStore.merge(recDir, now);
        for (int i = 0; i < traces.length(); i++) {
            JSONObject e = traces.optJSONObject(i);
            String node = e != null ? e.optString("node") : "";
            if (e == null || !"failed".equals(e.optString("state")) || !NODE_NAME.matcher(node).matches() || !retryable(e)
                    || (!keys.isEmpty() && !keys.contains(e.optString("key")))) {
                continue;
            }
            try {
                JSONObject p = new JSONObject(e.toString());
                p.remove("traceId");
                p.remove("summary");
                p.remove("file");
                p.put("state", "pending").put("updated", now)
                        .put("reason", RETRY_PREFIX + ": waiting for " + node + " to read its Glowroot");
                TraceStore.append(recDir, NODE, p); // this node's file: the newest line wins in merge
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
            byNode.computeIfAbsent(node, k -> new LinkedHashSet<>()).add(e.optString("key"));
        }
        Path dir = Files.createDirectories(retryDir());
        for (Map.Entry<String, Set<String>> n : byNode.entrySet()) {
            Path f = dir.resolve(recId + "@" + n.getKey() + ".json");
            Set<String> all = new LinkedHashSet<>(n.getValue());
            JSONObject old = readJson(f);
            JSONArray oldKeys = old != null ? old.optJSONArray("keys") : null;
            for (int i = 0; oldKeys != null && i < oldKeys.length(); i++) {
                all.add(oldKeys.optString(i));
            }
            try {
                JSONObject req = new JSONObject().put("keys", new JSONArray(all)).put("requestedAt", now)
                        .put("glowrootUrl", glowrootUrl == null ? "" : glowrootUrl);
                Path tmp = dir.resolve(f.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".part");
                Files.write(tmp, req.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
        }
        ScheduledExecutorService ex = jobs;
        if (ex != null && byNode.containsKey(NODE)) {
            ex.execute(TraceCollector::retryScan); // this node's own: no need to wait for the next scan
        }
        int n = 0;
        for (Set<String> k : byNode.values()) {
            n += k.size();
        }
        return n;
    }

    /** Picks up the retry requests addressed to this node. */
    private static void retryScan() {
        Path dir = retryDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String suffix = "@" + NODE + ".json";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (!name.endsWith(suffix)) {
                    continue;
                }
                JSONObject req = readJson(f);
                try {
                    Files.delete(f);
                } catch (NoSuchFileException e) {
                    continue; // taken already
                }
                String recId = name.substring(0, name.length() - suffix.length());
                Path recDir = recordingDir(recId);
                if (req == null || recDir == null || !Files.isDirectory(recDir)) {
                    continue; // the recording was deleted
                }
                retry(recId, recDir, req);
            }
        } catch (IOException | RuntimeException e) {
            LogUtil.warn(TraceCollector.class.getName(), "Could not read the trace retry requests: " + e.getMessage());
        }
    }

    private static void retry(String recId, Path recDir, JSONObject req) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        JSONArray list = req.optJSONArray("keys");
        for (int i = 0; list != null && i < list.length(); i++) {
            keys.add(list.optString(i));
        }
        JSONArray traces = TraceStore.merge(recDir, System.currentTimeMillis());
        for (int i = 0; i < traces.length(); i++) {
            JSONObject e = traces.optJSONObject(i);
            if (e == null || !keys.contains(e.optString("key")) || !NODE.equals(e.optString("node"))
                    || !"pending".equals(e.optString("state")) || !retryable(e)) {
                continue;
            }
            Running r = new Running(recId, req.optString("glowrootUrl"), e);
            write(r, entry(r, "pending", "Retrying: looking in Glowroot again"));
            attempt(r, 0, null);
        }
    }

    /**
     * Only this server's own entries can be looked up again: not ones from a ZIP import (another server's, and written
     * by whoever made the ZIP), nor ones without the capture id (recorded before traces were matched by their tag), nor
     * other users' traces (copied once after the stop).
     */
    static boolean retryable(JSONObject e) {
        return !e.optBoolean("imported") && !e.optBoolean("other") && !e.optString("capture").isEmpty();
    }

    private static Path retryDir() {
        return Paths.get(SetupManager.getBaseDirectory(), "blackbox", "retry");
    }

    private static JSONObject readJson(Path f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---------- helpers ----------

    private static long threshold(Running r) {
        GlowrootClient g = client(r);
        if (g == null) {
            return DEFAULT_THRESHOLD_MS;
        }
        try {
            return g.settings().thresholdMs;
        } catch (IOException e) {
            return DEFAULT_THRESHOLD_MS;
        }
    }

    private static GlowrootClient client(Running r) {
        return client(r.glowrootUrl);
    }

    private static GlowrootClient client(String glowrootUrl) {
        String key = glowrootUrl == null ? "" : glowrootUrl;
        GlowrootClient c = CLIENTS.get(key);
        if (c == null) {
            c = GlowrootClient.find(key);
            if (c != null) {
                CLIENTS.put(key, c);
            }
        }
        return c;
    }

    /** Counts the request against the capture's limit. Writes a failed entry once the limit is reached. */
    private static boolean reserve(Running r) {
        int n = COUNTS.computeIfAbsent(r.captureId, k -> new AtomicInteger()).incrementAndGet();
        if (n == MAX_PER_CAPTURE + 1) {
            write(r, entry(r, "failed", "Only the first " + MAX_PER_CAPTURE + " traces of a recording are kept"));
        }
        r.listed = n <= MAX_PER_CAPTURE;
        return r.listed;
    }

    private static JSONObject entry(Running r, String state, String reason) {
        try {
            JSONObject e = new JSONObject()
                    .put("key", r.key)
                    .put("capture", r.tag)
                    .put("node", NODE)
                    .put("ts", r.start)
                    .put("method", r.method)
                    .put("url", r.url)
                    .put("state", state)
                    .put("updated", System.currentTimeMillis());
            if (r.ms >= 0) {
                e.put("status", r.status).put("ms", r.ms);
                if (r.failed) {
                    e.put("failed", true);
                }
            }
            if (!reason.isEmpty()) {
                e.put("reason", reason);
            }
            return e;
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void write(Running r, JSONObject e) {
        Path dir = dir(r);
        if (dir == null) {
            return; // the capture expired or the recording was deleted
        }
        write(dir, r.captureId, r.recordingId, e);
    }

    private static void write(Path dir, String captureId, String recordingId, JSONObject e) {
        try {
            TraceStore.append(dir, NODE, e);
            if (recordingId == null) {
                moveIfUploaded(captureId, dir);
            }
            String rec = recordingId != null ? recordingId : uploadedAs(captureId);
            if (rec != null) {
                ServerData.refreshTraces(BlackBoxPlugin.store(), rec); // the recordings list's counts
            }
        } catch (IOException ex) {
            LogUtil.warn(TraceCollector.class.getName(), "Could not save a trace of capture " + captureId + ": " + ex.getMessage());
        }
    }

    private static Path dir(Running r) {
        if (r.recordingId == null) {
            return dir(r.captureId);
        }
        Path dir = recordingDir(r.recordingId);
        return dir != null && Files.isDirectory(dir) ? dir : null;
    }

    /** Where a capture's files (traces, gauges) go now: its pending folder, or the recording's after the upload. */
    static Path captureDir(String captureId) {
        return dir(captureId);
    }

    /** Where the capture's traces go now: its pending folder, or the recording's folder after the upload. */
    private static Path dir(String captureId) {
        ServerCaptureStore store = ServerCapture.store();
        JSONObject meta = store.readMeta(captureId);
        if (meta == null) {
            return null;
        }
        String rec = meta.optString("recording");
        Path dir = rec.isEmpty() ? store.dir(captureId) : recordingDir(rec);
        return dir != null && Files.isDirectory(dir) ? dir : null;
    }

    /** Written to the pending folder just as the upload moved it: move what was left behind. */
    private static void moveIfUploaded(String captureId, Path written) throws IOException {
        ServerCaptureStore store = ServerCapture.store();
        if (!written.equals(store.dir(captureId))) {
            return;
        }
        JSONObject meta = store.readMeta(captureId);
        String rec = meta != null ? meta.optString("recording") : "";
        Path target = rec.isEmpty() ? null : recordingDir(rec);
        if (target != null && Files.isDirectory(target)) {
            TraceStore.moveAll(written, target);
        }
    }

    /** The recording a capture was uploaded as, or null before the upload. */
    private static String uploadedAs(String captureId) {
        JSONObject meta = ServerCapture.store().readMeta(captureId);
        String rec = meta != null ? meta.optString("recording") : "";
        return rec.isEmpty() ? null : rec;
    }

    private static Path recordingDir(String recId) {
        if (!RecordingStore.isValidId(recId)) {
            return null;
        }
        return Paths.get(SetupManager.getBaseDirectory(), "blackbox", recId);
    }

    /** One tagged request on this node, or a retry of one (recordingId set). */
    static final class Running {
        final String captureId;
        final String recordingId;
        final String glowrootUrl;
        final String method;
        final String url;
        final long start;
        final String key;
        final String tag; // the capture id: GlowrootTag's value on this request's trace
        volatile long lastWritten;
        volatile boolean untagged; // a trace matched path and time but carried another tag (or none)
        volatile boolean listed;
        int status;
        long ms = -1; // -1: the end was not seen
        boolean failed;
        boolean loggedError; // logged an ERROR line: kept even when fast and 200
        long ended;

        Running(String captureId, String glowrootUrl, String method, String url, long start) {
            this.captureId = captureId;
            this.recordingId = null;
            this.glowrootUrl = glowrootUrl;
            this.method = method;
            this.url = url;
            this.start = start;
            this.key = NODE + "-" + start + "-" + SEQ.incrementAndGet();
            this.tag = captureId;
        }

        /** A retry of a recorded trace entry: same key, so its new state replaces the failed one. */
        Running(String recordingId, String glowrootUrl, JSONObject e) {
            this.captureId = recordingId; // only for the per-capture limit, which a retry does not use
            this.recordingId = recordingId;
            this.glowrootUrl = glowrootUrl;
            this.method = e.optString("method");
            this.url = e.optString("url");
            this.start = e.optLong("ts");
            this.key = e.optString("key");
            this.tag = e.optString("capture");
            this.listed = true;
            if (e.has("ms")) {
                this.status = e.optInt("status");
                this.ms = e.optLong("ms");
                this.failed = e.optBoolean("failed");
            }
            this.ended = System.currentTimeMillis(); // give up GIVE_UP_MS after the retry, not after the request
        }
    }
}
