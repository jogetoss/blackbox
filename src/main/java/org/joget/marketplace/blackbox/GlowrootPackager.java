package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GlowrootPackager {

    private static final long TICK_S = 10;
    private static final long AFTER_MINUTE_MS = 30_000L;
    private static final long MAX_WAIT_MS = 3 * 3600 * 1000L;
    private static final String NODE = ServerCapture.nodeName();
    private static final String EMBEDDED_JAR = "glowroot-embedded-collector.jar";
    private static final String H2_DRIVER = "org.glowroot.agent.embedded.shaded.org.h2.Driver";

    private static final Map<String, ProfileDir.Watch> WATCHED = new ConcurrentHashMap<>();
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
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        jobs.schedule(() -> {
            long now = System.currentTimeMillis();
            for (Path d : ProfileDir.all(GlowrootPackager.class)) {
                ProfileDir.safely(GlowrootPackager.class, "check for unfinished Glowroot data packages", d,
                        () -> recover(ServerCapture.store(), BlackBoxPlugin.store(), d, NODE, now));
            }
        }, 1, TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(GlowrootPackager::tick, TICK_S, TICK_S, TimeUnit.SECONDS);
        try {
            shutdownFlag = new Thread(() -> jvmStopping = true, "BlackBox shutdown flag");
            Runtime.getRuntime().addShutdownHook(shutdownFlag);
        } catch (IllegalStateException | SecurityException e) {
            shutdownFlag = null;
        }
    }

    static List<String> recover(ServerCaptureStore logs, RecordingStore recordings, Path profileDir, String node, long now)
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
            WATCHED.putIfAbsent(id, new ProfileDir.Watch(now, profileDir));
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
            }
            shutdownFlag = null;
        }
    }

    private static volatile boolean jvmStopping;
    private static Thread shutdownFlag;

    private static boolean stopping() {
        synchronized (GlowrootPackager.class) {
            if (jobs == null) {
                return true;
            }
        }
        return jvmStopping || Thread.currentThread().isInterrupted();
    }

    static void watch(ServerCapture.Capture capture) {
        if (jobs == null || !capture.glowrootData || failedOnThisNode(capture.id)
                || WATCHED.putIfAbsent(capture.id, new ProfileDir.Watch(System.currentTimeMillis(), ProfileDir.current())) != null) {
            return;
        }
        status(capture.id, "waiting", "Built about a minute after the recording stops", null);
    }

    private static boolean failedOnThisNode(String id) {
        Path dir = TraceCollector.captureDir(id);
        if (dir == null) {
            return false;
        }
        try {
            JSONArray all = GlowrootData.statuses(dir);
            for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.optJSONObject(i);
                if (s != null && NODE.equals(s.optString("node")) && "failed".equals(s.optString("state"))) {
                    return true;
                }
            }
        } catch (IOException e) {
        }
        return false;
    }

    static long readyAt(long stoppedAt) {
        long minuteEnd = (stoppedAt / 60_000L + 1) * 60_000L;
        return minuteEnd + AFTER_MINUTE_MS;
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ProfileDir.Watch> e : WATCHED.entrySet()) {
            String id = e.getKey();
            ProfileDir.Watch watch = e.getValue();
            if (!ProfileDir.safely(GlowrootPackager.class, "check the Glowroot data of capture " + id, watch.dir,
                    () -> tick(id, watch.since, now))) {
                WATCHED.remove(id);
                ProfileDir.safely(GlowrootPackager.class, "save the Glowroot data status of capture " + id, watch.dir,
                        () -> status(id, "failed", "Could not read the capture's folder on " + NODE, null));
            }
        }
    }

    private static void tick(String id, long since, long now) {
        JSONObject meta = ServerCapture.store().readMeta(id);
        if (meta == null) {
            WATCHED.remove(id);
            return;
        }
        if (!meta.has("stoppedAt")) {
            if (now - since > MAX_WAIT_MS) {
                WATCHED.remove(id);
                status(id, "failed", "The recording did not stop within " + MAX_WAIT_MS / 3600_000L + " hours", null);
            }
            return;
        }
        if (now >= readyAt(meta.optLong("stoppedAt"))) {
            WATCHED.remove(id);
            build(id, meta.optLong("startedAt"), meta.optLong("stoppedAt"));
        }
    }

    private static void build(String id, long from, long to) {
        if (!BlackBoxPlugin.glowrootShared.getAsBoolean()) {
            status(id, "failed", "With virtual hosts, Glowroot data needs the server started with -D"
                    + BlackBoxPlugin.SHARED_GLOWROOT + "=true: Glowroot holds every profile's requests", null);
            return;
        }
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
            try (URLClassLoader cl = new URLClassLoader(new URL[]{embedded.toUri().toURL()}, Driver.class.getClassLoader())) {
                Driver h2 = (Driver) Class.forName(H2_DRIVER, true, cl).getDeclaredConstructor().newInstance();
                JSONObject meta = ServerCapture.store().readMeta(id);
                r = GlowrootData.slice(h2, agent.resolve("data"), data, from, to, id, meta != null && meta.optBoolean("otherTraces"));
            }
            String readme = GlowrootData.readme(id, NODE, version, from, to);

            Path dir = TraceCollector.captureDir(id);
            if (dir == null) {
                return;
            }
            Path zip = dir.resolve(GlowrootData.DIR).resolve(NODE + ".zip");
            GlowrootData.zip(data, readme, zip);
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

    static Path agentDir(Path glowroot) {
        List<Path> candidates = new ArrayList<>();
        try {
            InetAddress local = InetAddress.getLocalHost();
            candidates.add(glowroot.resolve("agent-" + local.getHostName()));
            candidates.add(glowroot.resolve("agent-" + local.getCanonicalHostName()));
        } catch (IOException e) {
        }
        candidates.add(glowroot);
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("data").resolve("data.h2.db"))) {
                return p;
            }
        }
        return null;
    }

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
                ServerData.refreshGlowroot(BlackBoxPlugin.store(), rec);
            }
        } catch (IOException | JSONException e) {
            LogUtil.warn(GlowrootPackager.class.getName(), "Could not save the Glowroot data status of capture " + id + ": " + e.getMessage());
        }
    }

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
