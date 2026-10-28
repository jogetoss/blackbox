package org.joget.marketplace.blackbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * session.json (and system-log.json) read as a stream ({@link JsonStream}): checked and walked entry by entry, never
 * held whole in memory, so the file limit is the only size limit.
 */
final class SessionFile {

    /** One console or network entry (a response body is kept up to 1 MB by the extension). */
    static final long MAX_ENTRY_CHARS = 16L * 1024 * 1024;
    /** recording, summary, tool: small objects kept in meta.json. */
    static final long MAX_HEAD_CHARS = 1024L * 1024;

    /** What meta.json takes from a checked session.json. */
    static final class Head {
        JSONObject recording;
        JSONObject summary;
        String tool = "";
        long bytes;
    }

    /** One entry of a top-level array. */
    interface Each {
        void accept(JSONObject entry) throws IOException;
    }

    private SessionFile() {
    }

    /** Copies in to target, at most maxBytes (else InvalidUpload, and target is deleted). Returns the size. */
    static long copy(InputStream in, Path target, long maxBytes, String what) throws IOException, RecordingStore.InvalidUpload {
        long size = 0;
        boolean ok = false;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                size += n;
                if (size > maxBytes) {
                    throw new RecordingStore.InvalidUpload(what + " is larger than the limit (" + (maxBytes / (1024 * 1024)) + " MB).");
                }
                out.write(buf, 0, n);
            }
            if (size == 0) {
                throw new RecordingStore.InvalidUpload(what + " is empty.");
            }
            ok = true;
            return size;
        } finally {
            if (!ok) {
                Files.deleteIfExists(target);
            }
        }
    }

    /**
     * Checks a session.json file without loading it: one JSON object with console and network lists, each entry at
     * most {@link #MAX_ENTRY_CHARS}, valid JSON throughout. Returns what meta.json takes from it.
     */
    static Head check(Path file) throws IOException, RecordingStore.InvalidUpload {
        Head head = new Head();
        head.bytes = Files.size(file);
        boolean console = false;
        boolean network = false;
        try (JsonStream js = open(file)) {
            if (js.peekKind() != '{') {
                throw new RecordingStore.InvalidUpload("session.json is not a JSON object.");
            }
            js.beginObject();
            while (js.hasNext()) {
                String name = js.nextName();
                if ("console".equals(name) || "network".equals(name)) {
                    if (js.peekKind() != '[') {
                        throw new RecordingStore.InvalidUpload("This is not a BlackBox session.json (" + name + " is not a list).");
                    }
                    js.beginArray();
                    while (js.hasNext()) {
                        js.skipValue(MAX_ENTRY_CHARS);
                    }
                    js.endArray();
                    console |= "console".equals(name);
                    network |= "network".equals(name);
                } else if ("recording".equals(name) || "summary".equals(name)) {
                    Object v = js.nextValue(MAX_HEAD_CHARS);
                    if (v instanceof JSONObject) {
                        if ("recording".equals(name)) {
                            head.recording = (JSONObject) v;
                        } else {
                            head.summary = (JSONObject) v;
                        }
                    }
                } else if ("tool".equals(name)) {
                    Object v = js.nextValue(MAX_HEAD_CHARS);
                    head.tool = v instanceof String ? (String) v : "";
                } else {
                    js.skipValue();
                }
            }
            js.endObject();
            js.end();
        } catch (JsonStream.TooLarge e) {
            throw new RecordingStore.InvalidUpload("session.json has an entry larger than " + MAX_ENTRY_CHARS / (1024 * 1024) + " MB.");
        } catch (JsonStream.Malformed e) {
            throw new RecordingStore.InvalidUpload("session.json is not valid JSON (" + e.getMessage() + ").");
        }
        if (!console || !network) {
            throw new RecordingStore.InvalidUpload("This is not a BlackBox session.json (no console or network list).");
        }
        return head;
    }

    /**
     * Calls each with every object entry of the top-level list key (e.g. network, console, entries), built one at a
     * time. A missing or unreadable file, or a missing list, gives none; reading stops at a broken or oversized entry.
     */
    static void each(Path file, String key, Each each) throws IOException {
        each(file, key, null, each);
    }

    /**
     * As {@link #each(Path, String, Each)}, building only the given fields of each entry (null: all); the others, such
     * as response bodies, are skipped without being kept.
     */
    static void each(Path file, String key, java.util.Set<String> fields, Each each) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (JsonStream js = open(file)) {
            if (js.peekKind() != '{') {
                return;
            }
            js.beginObject();
            while (js.hasNext()) {
                String name = js.nextName();
                if (key.equals(name) && js.peekKind() == '[') {
                    js.beginArray();
                    while (js.hasNext()) {
                        if (fields == null || js.peekKind() != '{') {
                            Object v = js.nextValue(MAX_ENTRY_CHARS);
                            if (v instanceof JSONObject) {
                                each.accept((JSONObject) v);
                            }
                            continue;
                        }
                        JSONObject e = new JSONObject();
                        js.beginObject();
                        while (js.hasNext()) {
                            String f = js.nextName();
                            if (fields.contains(f)) {
                                try {
                                    e.put(f, js.nextValue(MAX_ENTRY_CHARS));
                                } catch (org.json.JSONException ex) {
                                    throw new JsonStream.Malformed(ex.getMessage());
                                }
                            } else {
                                js.skipValue(MAX_ENTRY_CHARS);
                            }
                        }
                        js.endObject();
                        each.accept(e);
                    }
                    return; // the rest of the file isn't needed
                }
                js.skipValue();
            }
        } catch (JsonStream.Malformed | JsonStream.TooLarge e) {
            // written before entries were checked, or damaged: what was read so far
        }
    }

    /**
     * Checks a system-log.json file as a stream (an object with an entries list) and returns its summary for meta.json,
     * like {@link ServerCaptureStore#summary}, counting one entry at a time.
     */
    static JSONObject systemLogSummary(Path file) throws IOException, RecordingStore.InvalidUpload {
        int[] n = new int[4]; // entries, requests, other, errors
        boolean truncated = false;
        boolean entries = false;
        try (JsonStream js = open(file)) {
            if (js.peekKind() != '{') {
                throw new RecordingStore.InvalidUpload("system-log.json is not a JSON object.");
            }
            js.beginObject();
            while (js.hasNext()) {
                String name = js.nextName();
                if ("entries".equals(name) && js.peekKind() == '[') {
                    entries = true;
                    js.beginArray();
                    while (js.hasNext()) {
                        Object v = js.nextValue(MAX_ENTRY_CHARS);
                        if (!(v instanceof JSONObject)) {
                            continue;
                        }
                        JSONObject e = (JSONObject) v;
                        n[0]++;
                        if ("request".equals(e.optString("kind"))) {
                            n[1]++;
                        } else if (e.optBoolean("other")) {
                            n[2]++;
                        } else if ("ERROR".equals(e.optString("level")) || "FATAL".equals(e.optString("level"))) {
                            n[3]++;
                        }
                    }
                    js.endArray();
                } else if ("truncated".equals(name)) {
                    truncated = Boolean.TRUE.equals(js.nextValue(16));
                } else {
                    js.skipValue();
                }
            }
            js.endObject();
            js.end();
        } catch (JsonStream.TooLarge e) {
            throw new RecordingStore.InvalidUpload("system-log.json has an entry larger than " + MAX_ENTRY_CHARS / (1024 * 1024) + " MB.");
        } catch (JsonStream.Malformed e) {
            throw new RecordingStore.InvalidUpload("system-log.json is not valid JSON (" + e.getMessage() + ").");
        }
        if (!entries) {
            throw new RecordingStore.InvalidUpload("system-log.json in the ZIP has no entries list.");
        }
        try {
            return new JSONObject().put("status", "attached").put("entries", n[0] - n[1] - n[2]).put("otherEntries", n[2])
                    .put("requests", n[1]).put("errors", n[3]).put("truncated", truncated);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static JsonStream open(Path file) throws IOException {
        return new JsonStream(new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 64 * 1024));
    }
}
