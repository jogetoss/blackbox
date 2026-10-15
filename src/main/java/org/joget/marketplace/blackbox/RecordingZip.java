package org.joget.marketplace.blackbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A recording as one ZIP, to move it to another Joget: meta.json (title, notes, who uploaded it), session.json,
 * server-log.json (if any) and recording.webm. {@link #importZip} turns such a ZIP into a new recording.
 */
public class RecordingZip {

    static final String META = "meta.json";
    private static final String[] META_KEYS = {"title", "notes", "owner", "ownerName", "uploadedAt", "recording", "summary", "importedFrom"};
    private static final long MAX_META_BYTES = 64 * 1024;

    private RecordingZip() {
    }

    /** serverLog and video may be null. */
    public static void write(OutputStream out, JSONObject meta, Path session, Path serverLog, Path video) throws IOException {
        JSONObject m = new JSONObject();
        try {
            for (String k : META_KEYS) {
                if (meta.has(k)) {
                    m.put(k, meta.get(k));
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(META));
        zip.write(m.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        add(zip, RecordingStore.SESSION, session);
        if (serverLog != null) {
            add(zip, RecordingStore.SERVER_LOG, serverLog);
        }
        if (video != null) {
            zip.setLevel(Deflater.NO_COMPRESSION); // WebM is compressed already
            add(zip, RecordingStore.VIDEO, video);
        }
        zip.finish(); // not close(): the caller owns the response stream
        zip.flush();
    }

    private static void add(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    /**
     * Creates a recording from a ZIP made by {@link #write} (or any ZIP with session.json at its top level).
     * The importer becomes the owner; who uploaded it first is kept as importedFrom. The server log is kept only
     * when keepServerLog is true. Each file is limited to maxBytes. Nothing is kept if any part is invalid.
     */
    public static JSONObject importZip(RecordingStore store, Path zipFile, long maxBytes, boolean keepServerLog,
            String owner, String ownerName) throws IOException, RecordingStore.InvalidUpload {
        ZipFile zip;
        try {
            zip = new ZipFile(zipFile.toFile());
        } catch (IOException e) {
            throw new RecordingStore.InvalidUpload("This is not a ZIP file.");
        }
        try {
            ZipEntry sessionEntry = zip.getEntry(RecordingStore.SESSION);
            if (sessionEntry == null) {
                throw new RecordingStore.InvalidUpload("The ZIP has no session.json at its top level.");
            }
            JSONObject old = new JSONObject();
            ZipEntry metaEntry = zip.getEntry(META);
            if (metaEntry != null) {
                try (InputStream in = zip.getInputStream(metaEntry)) {
                    old = new JSONObject(new String(read(in, MAX_META_BYTES, META), StandardCharsets.UTF_8));
                } catch (JSONException e) {
                    throw new RecordingStore.InvalidUpload("meta.json in the ZIP is not valid JSON.");
                }
            }

            JSONObject meta;
            try (InputStream in = zip.getInputStream(sessionEntry)) {
                meta = store.create(in, maxBytes, owner, ownerName, old.optString("title"), old.optString("notes"));
            }
            meta.remove("serverLogId"); // a capture id from the other server: nothing to attach here
            String id = meta.optString("id");
            try {
                ZipEntry videoEntry = zip.getEntry(RecordingStore.VIDEO);
                if (videoEntry != null) {
                    try (InputStream in = zip.getInputStream(videoEntry)) {
                        meta = store.writeVideo(id, in, maxBytes);
                    }
                }
                ZipEntry logEntry = zip.getEntry(RecordingStore.SERVER_LOG);
                if (logEntry != null && keepServerLog) {
                    meta.put("serverLog", importServerLog(store, id, zip, logEntry, maxBytes));
                }
                if (!old.optString("owner").isEmpty()) {
                    meta.put("importedFrom", new JSONObject()
                            .put("owner", old.optString("owner"))
                            .put("ownerName", old.optString("ownerName"))
                            .put("uploadedAt", old.optString("uploadedAt")));
                }
                store.updateMeta(id, meta);
                return meta;
            } catch (RecordingStore.InvalidUpload | IOException | RuntimeException e) { // JSONException is a RuntimeException
                store.delete(id);
                throw e;
            }
        } finally {
            zip.close();
        }
    }

    private static JSONObject importServerLog(RecordingStore store, String id, ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload, JSONException {
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = read(in, maxBytes, RecordingStore.SERVER_LOG);
        }
        JSONObject log;
        try {
            log = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new RecordingStore.InvalidUpload("server-log.json in the ZIP is not valid JSON.");
        }
        JSONArray entries = log.optJSONArray("entries");
        if (entries == null) {
            throw new RecordingStore.InvalidUpload("server-log.json in the ZIP has no entries list.");
        }
        Files.write(store.path(id, RecordingStore.SERVER_LOG), bytes);
        return ServerLogStore.summary(entries, log.optBoolean("truncated"));
    }

    private static byte[] read(InputStream in, long maxBytes, String name) throws IOException, RecordingStore.InvalidUpload {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long size = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            size += n;
            if (size > maxBytes) {
                throw new RecordingStore.InvalidUpload(name + " in the ZIP is larger than the limit.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
