package org.joget.marketplace.blackbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps each recording in its own folder: {root}/{id}/meta.json, session.json, recording.webm.
 * No database: the folder is the record, so a shared wflow folder works for clustered nodes too.
 */
public class RecordingStore {

    public static final String META = "meta.json";
    public static final String SESSION = "session.json";
    public static final String VIDEO = "recording.webm";
    public static final String SYSTEM_LOG = SystemLogStore.FILE;

    // yyyyMMdd-HHmmss-<8 hex>: sorts by upload time and can never contain a path separator
    private static final Pattern ID = Pattern.compile("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$");
    private static final byte[] WEBM_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}; // EBML header
    private static final int MAX_TITLE = 200;
    private static final int MAX_NOTES = 5000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    public RecordingStore(Path root) {
        this.root = root;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Thrown for bad client input; the message is safe to show to the user. */
    public static class InvalidUpload extends Exception {
        public InvalidUpload(String message) {
            super(message);
        }
    }

    /**
     * Saves session.json and creates the recording. The video is added later with {@link #writeVideo}.
     */
    public JSONObject create(InputStream session, long maxBytes, String owner, String ownerName, String title, String notes)
            throws IOException, InvalidUpload {
        byte[] bytes = readLimited(session, maxBytes);
        JSONObject report;
        try {
            report = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new InvalidUpload("session.json is not valid JSON.");
        }
        if (!(report.opt("console") instanceof JSONArray) || !(report.opt("network") instanceof JSONArray)) {
            throw new InvalidUpload("This is not a BlackBox session.json (no console or network list).");
        }

        String id = newId();
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        Files.write(dir.resolve(SESSION), bytes);

        JSONObject rec = report.optJSONObject("recording");
        JSONObject tab = rec != null ? rec.optJSONObject("tab") : null;
        if (isBlank(title) && tab != null) {
            title = !isBlank(tab.optString("title")) ? tab.optString("title") : tab.optString("url");
        }
        if (isBlank(title)) {
            title = "Recording " + id;
        }

        JSONObject meta = new JSONObject();
        try {
            meta.put("id", id);
            meta.put("title", cut(title.trim(), MAX_TITLE));
            meta.put("notes", cut(notes == null ? "" : notes.trim(), MAX_NOTES));
            meta.put("owner", owner);
            meta.put("ownerName", ownerName);
            meta.put("uploadedAt", isoNow());
            meta.put("tool", report.optString("tool"));
            meta.put("sessionSize", bytes.length);
            meta.put("videoSize", 0);
            if (rec != null) {
                JSONObject r = new JSONObject();
                for (String k : new String[]{"startedAt", "stoppedAt", "durationMs", "stopReason", "capturedSurface", "userAgent"}) {
                    if (rec.has(k)) {
                        r.put(k, rec.get(k));
                    }
                }
                if (tab != null) {
                    r.put("tab", new JSONObject().put("url", tab.optString("url")).put("title", tab.optString("title")));
                }
                meta.put("recording", r);
            }
            JSONObject summary = report.optJSONObject("summary");
            if (summary != null) {
                meta.put("summary", summary);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        writeMeta(id, meta);
        // Not saved in meta.json: the caller attaches the system log captured under this id (session.json has it)
        JSONObject systemLog = rec != null ? rec.optJSONObject("systemLog") : null;
        if (systemLog != null && SystemLogStore.isValidId(systemLog.optString("id"))) {
            try {
                meta.put("systemLogId", systemLog.optString("id"));
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
        return meta;
    }

    /** Stores recording.webm for a recording that has none yet. Returns the updated meta. */
    public JSONObject writeVideo(String id, InputStream in, long maxBytes) throws IOException, InvalidUpload {
        JSONObject meta = readMeta(id);
        if (meta == null) {
            throw new InvalidUpload("Recording not found.");
        }
        if (meta.optLong("videoSize") > 0) {
            throw new InvalidUpload("This recording already has a video.");
        }
        Path dir = root.resolve(id);
        Path tmp = dir.resolve(VIDEO + "." + Long.toHexString(RANDOM.nextLong()) + ".part");
        long size = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(tmp)) {
                // A network stream may return fewer bytes per read, so collect the header first
                int head = 0;
                int n;
                while (head < WEBM_MAGIC.length && (n = in.read(buf, head, buf.length - head)) != -1) {
                    head += n;
                }
                if (head == 0) {
                    throw new InvalidUpload("The video is empty.");
                }
                if (!startsWithMagic(buf, head)) {
                    throw new InvalidUpload("The video is not a WebM file.");
                }
                n = head;
                do {
                    size += n;
                    if (size > maxBytes) {
                        throw new InvalidUpload("The video is larger than the limit (" + (maxBytes / (1024 * 1024)) + " MB).");
                    }
                    out.write(buf, 0, n);
                } while ((n = in.read(buf)) != -1);
            }
            move(tmp, dir.resolve(VIDEO));
        } finally {
            Files.deleteIfExists(tmp);
        }
        try {
            meta.put("videoSize", size);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        writeMeta(id, meta);
        return meta;
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

    /** All recordings, newest first. Folders without a readable meta.json are skipped. */
    public List<JSONObject> list() throws IOException {
        List<JSONObject> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                JSONObject meta = readMeta(dir.getFileName().toString());
                if (meta != null) {
                    result.add(meta);
                }
            }
        }
        result.sort(Comparator.comparing((JSONObject m) -> m.optString("id")).reversed());
        return result;
    }

    /** Path of a stored file, or null when the id is invalid or the file is missing. */
    public Path file(String id, String name) {
        if (!isValidId(id) || !(SESSION.equals(name) || VIDEO.equals(name) || SYSTEM_LOG.equals(name) || ServerInfo.FILE.equals(name))) {
            return null;
        }
        Path file = root.resolve(id).resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    /** A recording's folder, or null when the id is invalid or it does not exist. */
    public Path dir(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path dir = root.resolve(id);
        return Files.isDirectory(dir) ? dir : null;
    }

    /** A Glowroot trace file of a recording (see {@link TraceStore}), or null when the name is invalid or it is missing. */
    public Path traceFile(String id, String name) {
        Path dir = dir(id);
        if (dir == null || !TraceStore.isTraceFile(name)) {
            return null;
        }
        Path file = dir.resolve(TraceStore.DIR).resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    /** Where a recording's file goes (it may not exist yet). id must be valid. */
    public Path path(String id, String name) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid recording id");
        }
        return root.resolve(id).resolve(name);
    }

    public void updateMeta(String id, JSONObject meta) throws IOException {
        if (readMeta(id) == null) {
            throw new IOException("Recording not found: " + id);
        }
        writeMeta(id, meta);
    }

    public boolean delete(String id) throws IOException {
        if (!isValidId(id)) {
            return false;
        }
        Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        deleteTree(dir);
        return true;
    }

    /** Deletes a folder and everything in it (traces/ too). Links are deleted, never followed. */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.deleteIfExists(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private void writeMeta(String id, JSONObject meta) throws IOException {
        Path dir = root.resolve(id);
        Path tmp = dir.resolve(META + "." + Long.toHexString(RANDOM.nextLong()) + ".part");
        try {
            Files.write(tmp, meta.toString().getBytes(StandardCharsets.UTF_8));
            move(tmp, dir.resolve(META));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException, InvalidUpload {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long size = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            size += n;
            if (size > maxBytes) {
                throw new InvalidUpload("session.json is larger than the limit (" + (maxBytes / (1024 * 1024)) + " MB).");
            }
            out.write(buf, 0, n);
        }
        if (size == 0) {
            throw new InvalidUpload("session.json is empty.");
        }
        return out.toByteArray();
    }

    private static boolean startsWithMagic(byte[] buf, int n) {
        if (n < WEBM_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < WEBM_MAGIC.length; i++) {
            if (buf[i] != WEBM_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String newId() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date()) + "-" + String.format("%08x", RANDOM.nextInt());
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
