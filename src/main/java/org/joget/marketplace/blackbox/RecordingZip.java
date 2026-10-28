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
 * system-log.json (if any), the Glowroot traces (traces.json and traces/*.html, if any), the server load samples
 * (gauges.json, if any), the System Info snapshot (server-info.json, if any), each node's Glowroot data
 * (glowroot/{node}.zip with glowroot/{node}.json, if any), the exported apps (app/apps.json and app/*.jwa, if any)
 * and recording.webm.
 * {@link #importZip} turns such a ZIP into a new recording.
 */
public class RecordingZip {

    static final String META = "meta.json";
    private static final String[] META_KEYS = {"title", "notes", "owner", "ownerName", "uploadedAt", "recording", "summary", "importedFrom"};
    private static final long MAX_META_BYTES = 64 * 1024;
    private static final int MAX_TRACES = 1000;

    private RecordingZip() {
    }

    /** systemLog and video may be null. */
    public static void write(OutputStream out, JSONObject meta, Path session, Path systemLog, Path video) throws IOException {
        write(out, meta, session, systemLog, video, null);
    }

    /** traceDir: the folder whose Glowroot traces go in (the recording's), or null for none. */
    public static void write(OutputStream out, JSONObject meta, Path session, Path systemLog, Path video, Path traceDir)
            throws IOException {
        write(out, meta, session, systemLog, video, traceDir, null);
    }

    /** appDir: the folder whose exported apps go in (the recording's), or null for none. */
    public static void write(OutputStream out, JSONObject meta, Path session, Path systemLog, Path video, Path traceDir,
            Path appDir) throws IOException {
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
        if (systemLog != null) {
            add(zip, RecordingStore.SYSTEM_LOG, systemLog);
        }
        if (traceDir != null) {
            JSONArray traces = TraceStore.merge(traceDir, System.currentTimeMillis());
            if (traces.length() > 0) {
                zip.putNextEntry(new ZipEntry(TraceStore.MERGED));
                zip.write(traces.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Path f : TraceStore.traceFiles(traceDir)) {
                    add(zip, TraceStore.DIR + "/" + f.getFileName().toString(), f);
                }
            }
            Path info = traceDir.resolve(ServerInfo.FILE);
            if (Files.isRegularFile(info)) {
                add(zip, ServerInfo.FILE, info);
            }
            JSONObject gauges = GaugeStore.merge(traceDir);
            if (gauges != null) {
                zip.putNextEntry(new ZipEntry(GaugeStore.MERGED));
                zip.write(gauges.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        if (traceDir != null) {
            // Glowroot data: built packages only, with their status; stored as they are (ZIPs already)
            zip.setLevel(Deflater.NO_COMPRESSION);
            JSONArray statuses = GlowrootData.statuses(traceDir);
            for (int i = 0; i < statuses.length(); i++) {
                JSONObject s = statuses.optJSONObject(i);
                Path pkg = s != null && "saved".equals(s.optString("state")) ? GlowrootData.zipFile(traceDir, s.optString("node")) : null;
                if (pkg != null) {
                    add(zip, GlowrootData.DIR + "/" + s.optString("node") + ".zip", pkg);
                    zip.putNextEntry(new ZipEntry(GlowrootData.DIR + "/" + s.optString("node") + ".json"));
                    zip.write(s.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
        Path apps = appDir != null ? AppExport.list(appDir) : null;
        if (apps != null) {
            add(zip, AppExport.DIR + "/" + AppExport.LIST, apps);
            zip.setLevel(Deflater.NO_COMPRESSION); // a .jwa is a ZIP already
            for (Path f : AppExport.files(appDir)) {
                add(zip, AppExport.DIR + "/" + f.getFileName().toString(), f);
            }
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
     * The importer becomes the owner; who uploaded it first is kept as importedFrom. The system log is kept only
     * when keepSystemLog is true. Each file is limited to maxBytes. Nothing is kept if any part is invalid.
     */
    public static JSONObject importZip(RecordingStore store, Path zipFile, long maxBytes, boolean keepSystemLog,
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
            meta.remove("systemLogId"); // a capture id from the other server: nothing to attach here
            String id = meta.optString("id");
            try {
                ZipEntry videoEntry = zip.getEntry(RecordingStore.VIDEO);
                if (videoEntry != null) {
                    try (InputStream in = zip.getInputStream(videoEntry)) {
                        meta = store.writeVideo(id, in, maxBytes);
                    }
                }
                ZipEntry logEntry = zip.getEntry(RecordingStore.SYSTEM_LOG);
                if (logEntry != null && keepSystemLog) {
                    meta.put("systemLog", importSystemLog(store, id, zip, logEntry, maxBytes));
                    importTraces(store, id, zip, maxBytes);
                    importGauges(store, id, zip, maxBytes);
                    importServerInfo(store, id, zip);
                    importGlowroot(store, id, zip, maxBytes);
                }
                if (keepSystemLog) {
                    JSONArray apps = importApps(store, id, zip, maxBytes);
                    if (apps.length() > 0) {
                        meta.put("apps", apps);
                    }
                }
                if (!old.optString("owner").isEmpty()) {
                    meta.put("importedFrom", new JSONObject()
                            .put("owner", old.optString("owner"))
                            .put("ownerName", old.optString("ownerName"))
                            .put("uploadedAt", old.optString("uploadedAt")));
                }
                store.updateMeta(id, meta);
                if (!keepSystemLog && (logEntry != null || zip.getEntry(AppExport.DIR + "/" + AppExport.LIST) != null)) {
                    meta.put("serverDataSkipped", true); // in the response only, so the page can say why it's missing
                }
                return meta;
            } catch (RecordingStore.InvalidUpload | IOException | RuntimeException e) { // JSONException is a RuntimeException
                store.delete(id);
                throw e;
            }
        } finally {
            zip.close();
        }
    }

    private static JSONObject importSystemLog(RecordingStore store, String id, ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload, JSONException {
        Path target = store.path(id, RecordingStore.SYSTEM_LOG);
        try (InputStream in = zip.getInputStream(entry)) {
            SessionFile.copy(in, target, maxBytes, RecordingStore.SYSTEM_LOG); // to disk, then checked as a stream
        }
        try {
            return SessionFile.systemLogSummary(target);
        } catch (RecordingStore.InvalidUpload | IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    /** glowroot/{node}.zip with its glowroot/{node}.json, as written; a package without a valid saved status is skipped. */
    private static void importGlowroot(RecordingStore store, String id, ZipFile zip, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload {
        java.util.Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry e = all.nextElement();
            String name = e.getName();
            if (!name.startsWith(GlowrootData.DIR + "/") || !name.endsWith(".zip")) {
                continue;
            }
            String file = name.substring(GlowrootData.DIR.length() + 1);
            String node = file.substring(0, file.length() - 4);
            ZipEntry statusEntry = zip.getEntry(GlowrootData.DIR + "/" + node + ".json");
            if (!GlowrootData.NODE_ZIP.matcher(file).matches() || statusEntry == null) {
                continue;
            }
            JSONObject status;
            try (InputStream in = zip.getInputStream(statusEntry)) {
                status = new JSONObject(new String(read(in, MAX_META_BYTES, statusEntry.getName()), StandardCharsets.UTF_8));
            } catch (JSONException ex) {
                continue;
            }
            if (!"saved".equals(status.optString("state")) || !node.equals(status.optString("node"))) {
                continue;
            }
            Path dir = Files.createDirectories(store.dir(id).resolve(GlowrootData.DIR));
            try (InputStream in = zip.getInputStream(e)) {
                copy(in, dir.resolve(file), maxBytes, name);
            }
            try {
                status.put("imported", true);
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
            GlowrootData.writeStatus(store.dir(id), node, status);
        }
    }

    /** app/apps.json and the .jwa files it lists as saved; returns the meta.json summary of those. */
    private static JSONArray importApps(RecordingStore store, String id, ZipFile zip, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload, JSONException {
        JSONArray summary = new JSONArray();
        ZipEntry index = zip.getEntry(AppExport.DIR + "/" + AppExport.LIST);
        if (index == null) {
            return summary;
        }
        JSONObject body;
        try (InputStream in = zip.getInputStream(index)) {
            body = new JSONObject(new String(read(in, MAX_META_BYTES, AppExport.LIST), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new RecordingStore.InvalidUpload(AppExport.LIST + " in the ZIP is not valid JSON.");
        }
        Path dir = Files.createDirectories(store.dir(id).resolve(AppExport.DIR));
        JSONArray apps = body.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            String file = a != null ? a.optString("file") : "";
            if (!"saved".equals(a != null ? a.optString("state") : null) || !file.matches(AppExport.FILE_NAME)) {
                continue;
            }
            ZipEntry e = zip.getEntry(AppExport.DIR + "/" + file);
            if (e == null) {
                a.put("state", "missing").put("reason", "Not in the imported ZIP.");
                continue;
            }
            try (InputStream in = zip.getInputStream(e)) {
                copy(in, dir.resolve(file), maxBytes, file);
            }
            summary.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("name")).put("version", a.optLong("version")));
        }
        Files.write(dir.resolve(AppExport.LIST), body.toString().getBytes(StandardCharsets.UTF_8));
        return summary;
    }

    private static void importServerInfo(RecordingStore store, String id, ZipFile zip) throws IOException, RecordingStore.InvalidUpload {
        ZipEntry entry = zip.getEntry(ServerInfo.FILE);
        if (entry == null) {
            return;
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = read(in, MAX_META_BYTES * 16, ServerInfo.FILE);
        }
        try {
            new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new RecordingStore.InvalidUpload(ServerInfo.FILE + " in the ZIP is not valid JSON.");
        }
        Files.write(store.path(id, ServerInfo.FILE), bytes);
    }

    private static void importGauges(RecordingStore store, String id, ZipFile zip, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload {
        ZipEntry entry = zip.getEntry(GaugeStore.MERGED);
        if (entry == null) {
            return;
        }
        Path tmp = Files.createTempFile(store.dir(id), "gauges-", ".json");
        try {
            try (InputStream in = zip.getInputStream(entry)) {
                copy(in, tmp, maxBytes, GaugeStore.MERGED);
            }
            GaugeStore.write(store.dir(id), tmp); // as a stream, one sample at a time
        } catch (JsonStream.Malformed | JsonStream.TooLarge e) {
            throw new RecordingStore.InvalidUpload(GaugeStore.MERGED + " in the ZIP is not valid JSON.");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** traces.json becomes the recording's traces/import.jsonl; trace files are copied as they are. */
    private static void importTraces(RecordingStore store, String id, ZipFile zip, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload {
        ZipEntry index = zip.getEntry(TraceStore.MERGED);
        if (index == null) {
            return;
        }
        Path dir = Files.createDirectories(store.dir(id).resolve(TraceStore.DIR));
        Path tmp = Files.createTempFile(store.dir(id), "traces-", ".json");
        try {
            try (InputStream in = zip.getInputStream(index)) {
                copy(in, tmp, maxBytes, TraceStore.MERGED);
            }
            importTraceEntries(zip, tmp, dir);
        } catch (JsonStream.Malformed | JsonStream.TooLarge | JSONException e) {
            throw new RecordingStore.InvalidUpload(TraceStore.MERGED + " in the ZIP is not valid JSON.");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** traces.json's entries, read as a stream: each trace file copied, each entry a line of traces/import.jsonl. */
    private static void importTraceEntries(ZipFile zip, Path index, Path dir) throws IOException, RecordingStore.InvalidUpload {
        Path out = dir.resolve(TraceStore.IMPORT_NODE + ".jsonl");
        int count = 0;
        try (JsonStream js = new JsonStream(new java.io.BufferedReader(new java.io.InputStreamReader(Files.newInputStream(index),
                StandardCharsets.UTF_8)));
                java.io.Writer lines = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            js.beginArray();
            while (js.hasNext()) {
                if (++count > MAX_TRACES) {
                    throw new RecordingStore.InvalidUpload("The ZIP has more than " + MAX_TRACES + " traces.");
                }
                Object v = js.nextValue(SessionFile.MAX_HEAD_CHARS);
                JSONObject e = v instanceof JSONObject ? (JSONObject) v : null;
                if (e == null || e.optString("key").isEmpty()) {
                    continue;
                }
            String file = e.optString("file");
            if (!file.isEmpty()) {
                ZipEntry f = TraceStore.isTraceFile(file) ? zip.getEntry(TraceStore.DIR + "/" + file) : null;
                if (f == null) {
                    e.remove("file");
                } else {
                    try (InputStream in = zip.getInputStream(f)) {
                        copy(in, dir.resolve(file), GlowrootClient.MAX_EXPORT_BYTES, file);
                    }
                }
            }
            lines.write(e.toString());
            lines.write('\n');
            }
            js.endArray();
            js.end();
        }
    }

    /** Copies a ZIP entry to a file as a stream, at most maxBytes (else InvalidUpload, and the file is removed). */
    private static void copy(InputStream in, Path target, long maxBytes, String name) throws IOException, RecordingStore.InvalidUpload {
        boolean ok = false;
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
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
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(target);
            }
        }
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
