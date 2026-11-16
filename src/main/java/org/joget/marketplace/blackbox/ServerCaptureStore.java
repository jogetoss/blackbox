package org.joget.marketplace.blackbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ServerCaptureStore {

    public static final String FILE = "system-log.json";
    static final String SECRET_HASH = "secretHash";
    private static final String META = "meta.json";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final long FORWARD_MS = 24L * 3600 * 1000;
    static final long LIMIT_GRACE_MS = 2 * 60_000L;
    private static final Pattern NODE_FILE = Pattern.compile("^system-log\\.[A-Za-z0-9.-]+\\.jsonl$");
    private static final String META_LOCK = ".meta.lock";
    private static final ConcurrentHashMap<String, Object> META_LOCKS = new ConcurrentHashMap<>();

    private interface Locked<T> {
        T run() throws IOException, JSONException;
    }

    private final Path root;

    public ServerCaptureStore(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    public int openCount(String owner, String exceptId) throws IOException {
        int n = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (java.nio.file.DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (!isValidId(id) || id.equals(exceptId)) {
                    continue;
                }
                JSONObject meta = readMeta(id);
                if (meta != null && owner.equals(meta.optString("owner")) && !meta.has("stoppedAt") && !meta.has("recording")) {
                    n++;
                }
            }
        }
        return n;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs) throws IOException {
        return start(id, owner, maxBytes, keepMs, false, "", false);
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl, boolean gauges)
            throws IOException {
        return start(id, owner, maxBytes, keepMs, traces, glowrootUrl, gauges, false);
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl, boolean gauges,
            boolean glowrootData) throws IOException {
        return start(id, owner, maxBytes, keepMs, traces, glowrootUrl, gauges, glowrootData, false);
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl, boolean gauges,
            boolean glowrootData, boolean allServer) throws IOException {
        return start(id, owner, maxBytes, keepMs, traces, glowrootUrl, gauges, glowrootData, allServer, 0);
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl, boolean gauges,
            boolean glowrootData, boolean allServer, long maxRecordingMs) throws IOException {
        return start(id, owner, maxBytes, keepMs, traces, glowrootUrl, gauges, glowrootData, allServer, maxRecordingMs, false);
    }

    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl, boolean gauges,
            boolean glowrootData, boolean allServer, long maxRecordingMs, boolean otherTraces) throws IOException {
        if (!isValidId(id)) {
            return false;
        }
        Files.createDirectories(root.resolve(id));
        return locked(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta != null) {
                return owner.equals(meta.optString("owner"));
            }
            long now = System.currentTimeMillis();
            JSONObject fresh = new JSONObject()
                    .put("id", id)
                    .put("owner", owner)
                    .put("startedAt", now)
                    .put("expiresAt", now + keepMs)
                    .put("maxBytes", maxBytes)
                    .put("traces", traces)
                    .put("glowrootUrl", glowrootUrl == null ? "" : glowrootUrl)
                    .put("gauges", gauges)
                    .put("glowrootData", glowrootData)
                    .put("allServer", allServer)
                    .put("otherTraces", otherTraces);
            if (maxRecordingMs > 0) {
                fresh.put("maxUntil", now + maxRecordingMs);
            }
            writeMeta(id, fresh);
            return true;
        });
    }

    public boolean stop(String id, String owner) throws IOException {
        return stop(id, owner, -1, 0);
    }

    public boolean stop(String id, String owner, long clientStoppedAt, long lostMs) throws IOException {
        return Boolean.TRUE.equals(lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null || !owner.equals(meta.optString("owner"))) {
                return false;
            }
            if (meta.has("stoppedAt")) {
                return true;
            }
            meta.put("stoppedAt", stopTime(meta, clientStoppedAt, lostMs, System.currentTimeMillis()));
            writeMeta(id, meta);
            return true;
        }));
    }

    private static long stopTime(JSONObject meta, long clientStoppedAt, long lostMs, long now) {
        if (clientStoppedAt < 0) {
            return now;
        }
        boolean seen = meta.has("lastSeenAt");
        long upTo = seen ? meta.optLong("lastSeenAt") + lostMs : now;
        if (clientStoppedAt >= meta.optLong("startedAt") && clientStoppedAt <= upTo) {
            return clientStoppedAt;
        }
        return seen ? meta.optLong("lastSeenAt") : now;
    }

    public JSONObject ping(String id, String owner, long lastSavedAt, long videoBytes) throws IOException {
        return lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null || !owner.equals(meta.optString("owner"))) {
                return null;
            }
            if (meta.has("stoppedAt") || meta.has("recording")) {
                return new JSONObject().put("state", "stopped").put("reason", meta.optString("stopReason", "stopped"));
            }
            meta.put("lastSeenAt", System.currentTimeMillis());
            if (lastSavedAt >= 0) {
                meta.put("lastSavedAt", lastSavedAt);
            }
            if (videoBytes >= 0) {
                meta.put("videoBytes", videoBytes);
            }
            writeMeta(id, meta);
            return new JSONObject().put("state", "on");
        });
    }

    public boolean stopIfLost(String id, long now, long lostMs) throws IOException {
        return Boolean.TRUE.equals(lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null || meta.has("stoppedAt") || meta.has("recording") || !meta.has("lastSeenAt")) {
                return false;
            }
            long seen = meta.optLong("lastSeenAt");
            if (now - seen <= lostMs) {
                return false;
            }
            meta.put("stoppedAt", seen).put("stopReason", "browser-lost");
            writeMeta(id, meta);
            return true;
        }));
    }

    public boolean stopIfOverLimit(String id, long now) throws IOException {
        return Boolean.TRUE.equals(lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null || meta.has("stoppedAt") || meta.has("recording") || !meta.has("maxUntil")) {
                return false;
            }
            long maxUntil = meta.optLong("maxUntil");
            if (now - maxUntil <= LIMIT_GRACE_MS) {
                return false;
            }
            meta.put("stoppedAt", maxUntil).put("stopReason", "time-limit");
            writeMeta(id, meta);
            return true;
        }));
    }

    public String ownerBySecret(String id, String secret) {
        JSONObject meta = readMeta(id);
        return meta != null && OneTimeTokens.matches(secret, meta.optString(SECRET_HASH)) ? meta.optString("owner") : null;
    }

    public JSONObject readMeta(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path file = root.resolve(id).resolve(META);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    Path dir(String id) {
        return root.resolve(id);
    }

    Path nodeFile(String id, String node) {
        return root.resolve(id).resolve("system-log." + node + ".jsonl");
    }

    public JSONObject attach(String id, String owner, Path target) throws IOException {
        return attach(id, owner, target, true);
    }

    public JSONObject attach(String id, String owner, Path target, boolean withApps) throws IOException {
        return lockedIfExists(id, () -> attachLocked(id, owner, target, withApps));
    }

    private JSONObject attachLocked(String id, String owner, Path target, boolean withApps) throws IOException {
        JSONObject meta = readMeta(id);
        if (meta == null || !owner.equals(meta.optString("owner")) || meta.has("recording")) {
            return null;
        }
        Path dir = root.resolve(id);
        List<JSONObject> entries = new ArrayList<>();
        boolean truncated = entries(dir, entries);
        JSONArray list = new JSONArray();
        for (JSONObject e : entries) {
            list.put(e);
        }
        JSONObject summary;
        try {
            JSONObject body = new JSONObject()
                    .put("startedAt", meta.optLong("startedAt"))
                    .put("truncated", truncated)
                    .put("entries", list);
            long now = System.currentTimeMillis();
            if (!meta.has("stoppedAt")) {
                meta.put("stoppedAt", meta.has("lastSeenAt") ? meta.optLong("lastSeenAt") : now);
            }
            body.put("stoppedAt", meta.optLong("stoppedAt"));
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, body.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            summary = summary(list, truncated);
            meta.remove(SECRET_HASH);
            meta.put("recording", target.getParent().getFileName().toString())
                    .put("expiresAt", now + FORWARD_MS);
            writeMeta(id, meta);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        TraceStore.moveAll(dir, target.getParent());
        GaugeStore.moveAll(dir, target.getParent());
        GlowrootData.moveAll(dir, target.getParent());
        if (withApps) {
            TraceStore.moveFolder(dir, target.getParent(), AppExport.DIR, name -> true);
        }
        if (Files.isRegularFile(dir.resolve(ServerInfo.FILE))) {
            Files.move(dir.resolve(ServerInfo.FILE), target.resolveSibling(ServerInfo.FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (NODE_FILE.matcher(f.getFileName().toString()).matches()) {
                    Files.deleteIfExists(f);
                }
            }
        }
        return summary;
    }

    private static boolean entries(Path dir, List<JSONObject> entries) throws IOException {
        boolean truncated = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String file = f.getFileName().toString();
                if (!NODE_FILE.matcher(file).matches()) {
                    continue;
                }
                String node = file.substring("system-log.".length(), file.length() - ".jsonl".length());
                try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        JSONObject e;
                        try {
                            e = new JSONObject(line);
                        } catch (JSONException ex) {
                            continue;
                        }
                        if ("truncated".equals(e.optString("kind"))) {
                            truncated = true;
                        } else {
                            if ("request".equals(e.optString("kind"))) {
                                try {
                                    e.put("node", node);
                                } catch (JSONException ex) {
                                }
                            }
                            entries.add(e);
                        }
                    }
                }
            }
        }
        entries.sort(Comparator.comparingLong((JSONObject e) -> e.optLong("ts")));
        return truncated;
    }

    public void writeLines(String id, Path target) throws IOException {
        List<JSONObject> entries = new ArrayList<>();
        entries(root.resolve(id), entries);
        JSONArray list = new JSONArray();
        for (JSONObject e : entries) {
            list.put(e);
        }
        try {
            Files.write(target, new JSONObject().put("entries", list).toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public boolean put(String id, String key, Object value) throws IOException {
        return Boolean.TRUE.equals(lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null) {
                return false;
            }
            meta.put(key, value);
            writeMeta(id, meta);
            return true;
        }));
    }

    public boolean markExportLost(String id) throws IOException {
        return Boolean.TRUE.equals(lockedIfExists(id, () -> {
            JSONObject meta = readMeta(id);
            if (meta == null || !ExportJobs.lost(meta.optJSONObject("appExport"))) {
                return false;
            }
            meta.put("appExport", ExportJobs.lostState());
            writeMeta(id, meta);
            return true;
        }));
    }

    private <T> T lockedIfExists(String id, Locked<T> work) throws IOException {
        return isValidId(id) && Files.isDirectory(root.resolve(id)) ? locked(id, work) : null;
    }

    private <T> T locked(String id, Locked<T> work) throws IOException {
        Path dir = root.resolve(id);
        Object lock = META_LOCKS.computeIfAbsent(dir.toAbsolutePath().toString(), k -> new Object());
        synchronized (lock) {
            try {
                return FileLocks.locked(dir.resolve(META_LOCK), work::run);
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
    }

    static JSONObject summary(JSONArray entries, boolean truncated) throws JSONException {
        int errors = 0;
        int requests = 0;
        int other = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                continue;
            }
            if ("request".equals(e.optString("kind"))) {
                requests++;
            } else if (e.optBoolean("other")) {
                other++;
            } else if ("ERROR".equals(e.optString("level")) || "FATAL".equals(e.optString("level"))) {
                errors++;
            }
        }
        return new JSONObject()
                .put("status", "attached")
                .put("entries", entries.length() - requests - other)
                .put("otherEntries", other)
                .put("requests", requests)
                .put("errors", errors)
                .put("truncated", truncated);
    }

    public List<String> ids() throws IOException {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (isValidId(id) && Files.isDirectory(dir)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public int sweep(long now) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (!isValidId(id)) {
                    continue;
                }
                JSONObject meta = readMeta(id);
                long expiresAt = meta != null ? meta.optLong("expiresAt")
                        : Files.getLastModifiedTime(dir).toMillis() + 24L * 3600 * 1000;
                if (expiresAt < now) {
                    deleteDir(dir);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private void writeMeta(String id, JSONObject meta) throws IOException {
        Path dir = root.resolve(id);
        Path tmp = dir.resolve(META + "." + Long.toHexString(System.nanoTime()) + ".part");
        try {
            Files.write(tmp, meta.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, dir.resolve(META), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        RecordingStore.deleteTree(dir);
    }
}
