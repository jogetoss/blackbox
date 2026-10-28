package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.joget.commons.util.LogUtil;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds each node's Glowroot data package of a recording ({@link GlowrootData}) after the recording stops.
 *
 * A node watches a capture once the capture opens on it (the start call, or its first tagged request), like
 * {@link GaugeSampler}. After the stop it waits until Glowroot has written the aggregates of the stop's minute
 * (Glowroot writes each minute's aggregates just after the minute ends), then cuts the recording out of its own
 * Glowroot's data folder (found next to the -javaagent jar) and saves glowroot/{node}.zip into the capture's pending
 * folder, or into the recording once uploaded. One package at a time, on one background thread.
 *
 * The H2 work uses Glowroot's own H2 driver (shaded in lib/glowroot-embedded-collector.jar), so the copy stays in the
 * exact H2 version Glowroot reads. Only memory tracks what to build, so after a restart {@link #recover} picks up this
 * node's unfinished packages again, or marks them failed when their capture is gone.
 */
final class GlowrootPackager {

    private static final long TICK_S = 10;
    private static final long AFTER_MINUTE_MS = 30_000L; // Glowroot writes a minute's aggregates a moment after it ends
    private static final long MAX_WAIT_MS = 3 * 3600 * 1000L; // give up on captures never stopped
    private static final String NODE = ServerCapture.nodeName();
    private static final String EMBEDDED_JAR = "glowroot-embedded-collector.jar";
    private static final String H2_DRIVER = "org.glowroot.agent.embedded.shaded.org.h2.Driver";

    private static final Map<String, Long> WATCHED = new ConcurrentHashMap<>(); // capture id -> since
    private static ScheduledExecutorService jobs;

    private GlowrootPackager() {
    }

    static synchronized void start() {
        if (jobs != null) {
            return;
        }
        jobs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlackBox Glowroot data");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // background work: requests come first
            return t;
        });
        jobs.schedule(() -> {
            try {
                recover(ServerCapture.store(), BlackBoxPlugin.store(), NODE, System.currentTimeMillis());
            } catch (IOException | RuntimeException e) { // JSONException is a RuntimeException
                LogUtil.warn(GlowrootPackager.class.getName(), "Could not check for unfinished Glowroot data packages: " + e);
            }
        }, 1, TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(GlowrootPackager::tick, TICK_S, TICK_S, TimeUnit.SECONDS);
        try {
            shutdownFlag = new Thread(() -> jvmStopping = true, "BlackBox shutdown flag");
            Runtime.getRuntime().addShutdownHook(shutdownFlag);
        } catch (IllegalStateException | SecurityException e) {
            shutdownFlag = null; // already shutting down, or not allowed: only the plugin's own stop is noticed
        }
    }

    /**
     * After a restart: this node's packages left waiting or building are watched again while their capture exists (the
     * build runs as usual; blocks Glowroot overwrote meanwhile are counted as lost). Those in a recording whose capture
     * is gone are marked failed, so the recording doesn't stay "building" for good. Returns the capture ids watched.
     */
    static List<String> recover(ServerCaptureStore logs, RecordingStore recordings, String node, long now)
            throws IOException, JSONException {
        List<String> watched = new ArrayList<>();
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (String id : logs.ids()) {
            JSONObject meta = logs.readMeta(id);
            if (meta == null || !meta.optBoolean("glowrootData")) {
                continue;
            }
            String rec = meta.optString("recording");
            Path dir = rec.isEmpty() ? logs.dir(id) : recordings.dir(rec);
            if (!GlowrootData.unfinished(dir, node)) {
                continue;
            }
            WATCHED.putIfAbsent(id, now);
            watched.add(id);
            if (!rec.isEmpty()) {
                covered.add(rec);
            }
        }
        for (JSONObject meta : recordings.list()) {
            String rec = meta.optString("id");
            Path dir = recordings.dir(rec);
            if (covered.contains(rec) || !GlowrootData.unfinished(dir, node)) {
                continue;
            }
            GlowrootData.writeStatus(dir, node, new JSONObject().put("node", node).put("state", "failed").put("updated", now)
                    .put("reason", "Joget restarted before the package was built, and the capture's data is gone"));
            ServerData.refreshGlowroot(recordings, rec);
        }
        return watched;
    }

    static synchronized void stop() {
        if (jobs != null) {
            jobs.shutdownNow();
            jobs = null;
        }
        WATCHED.clear();
        if (shutdownFlag != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownFlag);
            } catch (IllegalStateException | SecurityException e) {
                // the JVM is shutting down: the hook runs anyway
            }
            shutdownFlag = null;
        }
    }

    private static volatile boolean jvmStopping;
    private static Thread shutdownFlag; // registered while started, so a reloaded plugin leaves none behind

    /** The plugin stopped (jobs gone), this thread was interrupted, or the JVM is shutting down. */
    private static boolean stopping() {
        synchronized (GlowrootPackager.class) {
            if (jobs == null) {
                return true;
            }
        }
        return jvmStopping || Thread.currentThread().isInterrupted();
    }

    /** A capture is open on this node: build its package after it stops. */
    static void watch(ServerCapture.Capture capture) {
        if (jobs == null || !capture.glowrootData || WATCHED.putIfAbsent(capture.id, System.currentTimeMillis()) != null) {
            return;
        }
        status(capture.id, "waiting", "Built about a minute after the recording stops", null);
    }

    /** When the package can be built: after Glowroot wrote the aggregates of the minute the recording stopped in. */
    static long readyAt(long stoppedAt) {
        long minuteEnd = (stoppedAt / 60_000L + 1) * 60_000L;
        return minuteEnd + AFTER_MINUTE_MS;
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : WATCHED.entrySet()) {
            String id = e.getKey();
            JSONObject meta = ServerCapture.store().readMeta(id);
            if (meta == null) {
                WATCHED.remove(id); // expired or deleted
                continue;
            }
            if (!meta.has("stoppedAt")) {
                if (now - e.getValue() > MAX_WAIT_MS) {
                    WATCHED.remove(id);
                    status(id, "failed", "The recording did not stop within " + MAX_WAIT_MS / 3600_000L + " hours", null);
                }
                continue;
            }
            if (now >= readyAt(meta.optLong("stoppedAt"))) {
                WATCHED.remove(id);
                build(id, meta.optLong("startedAt"), meta.optLong("stoppedAt"));
            }
        }
    }

    private static void build(String id, long from, long to) {
        status(id, "building", "Cutting the recording out of Glowroot's data", null);
        Path work = null;
        try {
            Path jar = GlowrootClient.agentJar();
            if (jar == null || jar.getParent() == null) {
                throw new IOException("Glowroot is not running on " + NODE);
            }
            String version = GlowrootClient.agentVersion();
            if (version == null || !version.startsWith("0.14")) {
                throw new IOException("Only Glowroot 0.14 is supported, this node runs " + (version == null ? "an unknown version" : version));
            }
            Path agent = agentDir(jar.getParent());
            if (agent == null) {
                throw new IOException("Glowroot's data folder was not found next to " + jar);
            }
            Path embedded = jar.getParent().resolve("lib").resolve(EMBEDDED_JAR);
            if (!Files.isRegularFile(embedded)) {
                throw new IOException("Glowroot's " + EMBEDDED_JAR + " was not found");
            }
            Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
            long h2Bytes = Files.size(agent.resolve("data").resolve("data.h2.db"));
            long free = Files.getFileStore(tmp).getUsableSpace();
            if (free < GlowrootData.spaceNeeded(h2Bytes)) {
                throw new IOException("Not enough free disk space in " + tmp + ": needs " + mb(GlowrootData.spaceNeeded(h2Bytes))
                        + " (one copy of Glowroot's data.h2.db, " + mb(h2Bytes) + "), " + mb(free) + " free");
            }
            work = Files.createTempDirectory("blackbox-glowroot-");
            Path data = work.resolve("data");
            GlowrootData.Result r;
            // Parent: the loader of java.sql (platform on Java 9+, bootstrap on 8), so the shaded H2 sees JDBC only
            try (URLClassLoader cl = new URLClassLoader(new URL[]{embedded.toUri().toURL()}, Driver.class.getClassLoader())) {
                Driver h2 = (Driver) Class.forName(H2_DRIVER, true, cl).getDeclaredConstructor().newInstance();
                r = GlowrootData.slice(h2, agent.resolve("data"), data, from, to, id);
            }
            String config = GlowrootData.viewerConfig(read(agent.resolve("config.json"), jar.getParent().resolve("config.json")));
            String admin = GlowrootData.viewerAdmin(cappedMb(data.resolve(GlowrootData.TRACE_CAPPED)), rollupMb(data));
            String readme = GlowrootData.readme(id, NODE, version, from, to);

            Path dir = TraceCollector.captureDir(id); // pending, or the recording's folder once uploaded
            if (dir == null) {
                return; // the capture expired or the recording was deleted meanwhile
            }
            Path zip = dir.resolve(GlowrootData.DIR).resolve(NODE + ".zip");
            GlowrootData.zip(data, config, admin, readme, zip);
            JSONObject done = new JSONObject()
                    .put("bytes", Files.size(zip))
                    .put("traces", r.traces)
                    .put("aggregates", r.aggregates)
                    .put("gauges", r.gauges)
                    .put("lostBlocks", r.lostBlocks)
                    .put("glowroot", version);
            status(id, "saved", r.lostBlocks > 0 ? r.lostBlocks + " details were overwritten in Glowroot before they could be copied" : "", done);
            moveIfUploaded(id, dir);
        } catch (Exception | LinkageError e) {
            if (stopping()) {
                // Joget or the plugin is stopping: left "building", so recover builds it again after the restart
                LogUtil.info(GlowrootPackager.class.getName(), "Glowroot data of capture " + id + " not built: stopping (" + e + ")");
                return;
            }
            LogUtil.warn(GlowrootPackager.class.getName(), "Could not build the Glowroot data of capture " + id + ": " + e);
            status(id, "failed", e.getMessage() != null ? e.getMessage() : e.toString(), null);
        } finally {
            if (work != null) {
                try {
                    RecordingStore.deleteTree(work);
                } catch (IOException e) {
                    LogUtil.warn(GlowrootPackager.class.getName(), "Could not delete " + work + ": " + e.getMessage());
                }
            }
        }
    }

    private static String mb(long bytes) {
        return (bytes + 1024 * 1024 - 1) / (1024 * 1024) + " MB";
    }

    /** agent-{host} when Glowroot keeps one folder per server (its multi-directory mode), else the jar's folder. */
    static Path agentDir(Path glowroot) {
        List<Path> candidates = new ArrayList<>();
        try {
            InetAddress local = InetAddress.getLocalHost();
            candidates.add(glowroot.resolve("agent-" + local.getHostName()));
            candidates.add(glowroot.resolve("agent-" + local.getCanonicalHostName()));
        } catch (IOException e) {
            // no host name: only the jar's folder
        }
        candidates.add(glowroot);
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("data").resolve("data.h2.db"))) {
                return p;
            }
        }
        return null;
    }

    private static String read(Path... files) throws IOException {
        for (Path f : files) {
            if (Files.isRegularFile(f)) {
                return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** A capped file's ring size in MB, from its header (500 when it can't be read). */
    private static int cappedMb(Path f) {
        try (RandomAccessFile r = new RandomAccessFile(f.toFile(), "r")) {
            r.readLong();
            return Math.max(1, r.readInt() / 1024);
        } catch (IOException e) {
            return 500;
        }
    }

    private static int[] rollupMb(Path data) {
        int[] mb = new int[GlowrootData.AGGREGATE_MS.length];
        for (int i = 0; i < mb.length; i++) {
            mb[i] = cappedMb(data.resolve(GlowrootData.rollupCapped(i)));
        }
        return mb;
    }

    /** This node's status in the capture's folder (pending, or the recording's once uploaded). */
    private static void status(String id, String state, String reason, JSONObject extra) {
        Path dir = TraceCollector.captureDir(id);
        if (dir == null) {
            return;
        }
        try {
            JSONObject s = extra != null ? extra : new JSONObject();
            s.put("node", NODE).put("state", state).put("updated", System.currentTimeMillis());
            if (reason != null && !reason.isEmpty()) {
                s.put("reason", reason);
            }
            GlowrootData.writeStatus(dir, NODE, s);
            moveIfUploaded(id, dir);
            JSONObject meta = ServerCapture.store().readMeta(id);
            String rec = meta != null ? meta.optString("recording") : "";
            if (!rec.isEmpty()) {
                ServerData.refreshGlowroot(BlackBoxPlugin.store(), rec); // the recordings list's state
            }
        } catch (IOException | JSONException e) {
            LogUtil.warn(GlowrootPackager.class.getName(), "Could not save the Glowroot data status of capture " + id + ": " + e.getMessage());
        }
    }

    /** Written to the pending folder just as the upload moved it: move what was left behind. */
    private static void moveIfUploaded(String id, Path written) throws IOException {
        ServerCaptureStore store = ServerCapture.store();
        if (!written.equals(store.dir(id))) {
            return;
        }
        Path target = TraceCollector.captureDir(id);
        if (target != null && !target.equals(written)) {
            GlowrootData.moveAll(written, target);
        }
    }
}
