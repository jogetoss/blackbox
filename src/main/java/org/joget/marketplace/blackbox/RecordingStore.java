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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class RecordingStore {

    public static final String META = "meta.json";
    public static final String SESSION = "session.json";
    public static final String VIDEO = "recording.webm";
    public static final String SYSTEM_LOG = ServerCaptureStore.FILE;
    public static final String CAPTURE = "serverCapture";
    static final String LEGACY_CAPTURE = "systemLog";

    private static final Pattern ID = Pattern.compile("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$");
    private static final byte[] WEBM_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
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

    public static class InvalidUpload extends Exception {
        public InvalidUpload(String message) {
            super(message);
        }
    }

    public static class Busy extends InvalidUpload {
        public Busy(String message) {
            super(message);
        }
    }

    public interface MetaChange {
        void apply(JSONObject meta) throws IOException, InvalidUpload, JSONException;
    }

    private static final Semaphore PARSING = new Semaphore(2);
    private static final long PARSE_WAIT_S = 30;
    private static final String META_LOCK = ".meta.lock";
    private static final ConcurrentHashMap<String, Object> META_LOCKS = new ConcurrentHashMap<>();

    public JSONObject create(InputStream session, long maxBytes, String owner, String ownerName, String title, String notes)
            throws IOException, InvalidUpload {
        String id = newId();
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        SessionFile.Head head;
        boolean ok = false;
        try {
            Path tmp = dir.resolve(SESSION + ".part");
            SessionFile.copy(session, tmp, maxBytes, "session.json");
            acquireParsing();
            try {
                head = SessionFile.check(tmp);
            } finally {
                PARSING.release();
            }
            move(tmp, dir.resolve(SESSION));
            ok = true;
        } finally {
            if (!ok) {
                deleteTree(dir);
            }
        }

        JSONObject rec = head.recording;
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
            meta.put("tool", head.tool);
            meta.put("sessionSize", head.bytes);
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
            JSONObject summary = head.summary;
            if (summary != null) {
                meta.put("summary", summary);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        writeMeta(id, meta);
        JSONObject capture = rec == null ? null
                : rec.has(CAPTURE) ? rec.optJSONObject(CAPTURE) : rec.optJSONObject(LEGACY_CAPTURE);
        if (capture != null && ServerCaptureStore.isValidId(capture.optString("id"))) {
            try {
                meta.put("captureId", capture.optString("id"));
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
        return meta;
    }

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
            long videoSize = size;
            return modifyMeta(id, m -> {
                if (m.optLong("videoSize") > 0) {
                    throw new InvalidUpload("This recording already has a video.");
                }
                move(tmp, dir.resolve(VIDEO));
                m.put("videoSize", videoSize);
            });
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void acquireParsing() throws Busy {
        try {
            if (!PARSING.tryAcquire(PARSE_WAIT_S, TimeUnit.SECONDS)) {
                throw new Busy("The server is busy reading other uploads. Try again in a minute.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy("Interrupted while waiting to read the upload.");
        }
    }

    static void releaseParsing() {
        PARSING.release();
    }

    public JSONObject modifyMeta(String id, MetaChange change) throws IOException, InvalidUpload {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid recording id");
        }
        Path dir = root.resolve(id);
        Object lock = META_LOCKS.computeIfAbsent(dir.toAbsolutePath().toString(), k -> new Object());
        synchronized (lock) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("Recording not found: " + id);
            }
            return FileLocks.<JSONObject, InvalidUpload>locked(dir.resolve(META_LOCK), () -> {
                JSONObject meta = readMeta(id);
                if (meta == null) {
                    throw new IOException("Recording not found: " + id);
                }
                try {
                    change.apply(meta);
                } catch (JSONException e) {
                    throw new IOException(e);
                }
                writeMeta(id, meta);
                return meta;
            });
        }
    }

    static JSONObject renameLegacy(JSONObject meta) throws JSONException {
        Object old = meta.remove(LEGACY_CAPTURE);
        if (old != null && !meta.has(CAPTURE)) {
            meta.put(CAPTURE, old);
        }
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
            return renameLegacy(new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public long usedBytes(String owner) throws IOException {
        long total = 0;
        for (JSONObject meta : list()) {
            Path dir = owner.equals(meta.optString("owner")) ? dir(meta.optString("id")) : null;
            if (dir != null && Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                    total += files.filter(Files::isRegularFile).mapToLong(f -> {
                        try {
                            return Files.size(f);
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
                }
            }
        }
        return total;
    }

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

    public Path file(String id, String name) {
        if (!isValidId(id) || !(SESSION.equals(name) || VIDEO.equals(name) || SYSTEM_LOG.equals(name) || ServerInfo.FILE.equals(name))) {
            return null;
        }
        Path file = root.resolve(id).resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    public Path dir(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path dir = root.resolve(id);
        return Files.isDirectory(dir) ? dir : null;
    }

    public Path traceFile(String id, String name) {
        Path dir = dir(id);
        if (dir == null || !TraceStore.isTraceFile(name)) {
            return null;
        }
        Path file = dir.resolve(TraceStore.DIR).resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    public Path path(String id, String name) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid recording id");
        }
        return root.resolve(id).resolve(name);
    }

    public JSONObject updateMeta(String id, JSONObject changes) throws IOException {
        try {
            return modifyMeta(id, m -> {
                for (java.util.Iterator<?> it = changes.keys(); it.hasNext();) {
                    String k = String.valueOf(it.next());
                    Object v = changes.opt(k);
                    if (v == null || v == JSONObject.NULL) {
                        m.remove(k);
                    } else {
                        m.put(k, v);
                    }
                }
            });
        } catch (InvalidUpload e) {
            throw new IOException(e.getMessage());
        }
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
