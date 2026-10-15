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
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Server log lines captured while a recording runs, kept until the recording is uploaded:
 * {root}/{id}/meta.json + server-log.{node}.jsonl (one file per cluster node, so two nodes never write the same file).
 * On upload, {@link #attach} merges them into the recording's server-log.json and deletes the pending folder.
 *
 * The id is a random UUID made by the extension; only the user who started the capture can attach it.
 */
public class ServerLogStore {

    public static final String FILE = "server-log.json";
    private static final String META = "meta.json";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final long FORWARD_MS = 24L * 3600 * 1000; // > TraceCollector.GIVE_UP_MS
    private static final Pattern NODE_FILE = Pattern.compile("^server-log\\.[A-Za-z0-9.-]+\\.jsonl$");

    private final Path root;

    public ServerLogStore(Path root) {
        this.root = root;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Starts a capture. Returns false when the id already belongs to another user. Starting it again is fine. */
    public boolean start(String id, String owner, long maxBytes, long keepMs) throws IOException {
        return start(id, owner, maxBytes, keepMs, false, "");
    }

    /** traces: also copy the Glowroot traces of slow and failed requests, from glowrootUrl ("" = the local agent). */
    public boolean start(String id, String owner, long maxBytes, long keepMs, boolean traces, String glowrootUrl) throws IOException {
        JSONObject meta = readMeta(id);
        if (meta != null) {
            return owner.equals(meta.optString("owner"));
        }
        long now = System.currentTimeMillis();
        Files.createDirectories(root.resolve(id));
        try {
            writeMeta(id, new JSONObject()
                    .put("id", id)
                    .put("owner", owner)
                    .put("startedAt", now)
                    .put("expiresAt", now + keepMs)
                    .put("maxBytes", maxBytes)
                    .put("traces", traces)
                    .put("glowrootUrl", glowrootUrl == null ? "" : glowrootUrl));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return true;
    }

    /** Marks a capture as stopped. Returns false when it is missing or belongs to another user. */
    public boolean stop(String id, String owner) throws IOException {
        JSONObject meta = readMeta(id);
        if (meta == null || !owner.equals(meta.optString("owner"))) {
            return false;
        }
        try {
            meta.put("stoppedAt", System.currentTimeMillis());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        writeMeta(id, meta);
        return true;
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

    /** The capture's pending folder (it may not exist). id must be valid. */
    Path dir(String id) {
        return root.resolve(id);
    }

    /** The file one node appends to. node must match [A-Za-z0-9.-]+. */
    Path nodeFile(String id, String node) {
        return root.resolve(id).resolve("server-log." + node + ".jsonl");
    }

    /**
     * Merges the capture's node files into target (sorted by time) and moves its traces next to it.
     * The pending folder then only keeps meta.json, pointing at the recording, for {@link #FORWARD_MS}: traces that
     * are still being collected go to the recording (see {@link TraceCollector}).
     * Returns a summary for the recording's meta, or null when the capture is missing, expired, attached or not owner's.
     */
    public JSONObject attach(String id, String owner, Path target) throws IOException {
        JSONObject meta = readMeta(id);
        if (meta == null || !owner.equals(meta.optString("owner")) || meta.has("recording")) {
            return null;
        }
        Path dir = root.resolve(id);
        List<JSONObject> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (!NODE_FILE.matcher(f.getFileName().toString()).matches()) {
                    continue;
                }
                try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        JSONObject e;
                        try {
                            e = new JSONObject(line);
                        } catch (JSONException ex) {
                            continue; // a line cut short by a crash
                        }
                        if ("truncated".equals(e.optString("kind"))) {
                            truncated = true;
                        } else {
                            entries.add(e);
                        }
                    }
                }
            }
        }
        entries.sort(Comparator.comparingLong((JSONObject e) -> e.optLong("ts")));

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
            if (meta.has("stoppedAt")) {
                body.put("stoppedAt", meta.optLong("stoppedAt"));
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, body.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            summary = summary(list, truncated);
            long now = System.currentTimeMillis();
            meta.put("recording", target.getParent().getFileName().toString())
                    .put("expiresAt", now + FORWARD_MS);
            if (!meta.has("stoppedAt")) {
                meta.put("stoppedAt", now);
            }
            writeMeta(id, meta);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        TraceStore.moveAll(dir, target.getParent());
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (NODE_FILE.matcher(f.getFileName().toString()).matches()) {
                    Files.deleteIfExists(f);
                }
            }
        }
        return summary;
    }

    /** The summary kept in the recording's meta.json: log lines, request entries, error lines, cut off or not. */
    static JSONObject summary(JSONArray entries, boolean truncated) throws JSONException {
        int errors = 0;
        int requests = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                continue;
            }
            if ("request".equals(e.optString("kind"))) {
                requests++;
            } else if ("ERROR".equals(e.optString("level")) || "FATAL".equals(e.optString("level"))) {
                errors++;
            }
        }
        return new JSONObject()
                .put("status", "attached")
                .put("entries", entries.length() - requests)
                .put("requests", requests)
                .put("errors", errors)
                .put("truncated", truncated);
    }

    /** Deletes captures past their expiry time (never uploaded). Returns how many were deleted. */
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
                // No readable meta: a start that failed half way; give it a day
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
        RecordingStore.deleteTree(dir); // traces/ too
    }
}
