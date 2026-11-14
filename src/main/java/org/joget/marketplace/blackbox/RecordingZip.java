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

public class RecordingZip {

    static final String META = "meta.json";
    private static final String[] META_KEYS = {"title", "notes", "owner", "ownerName", "uploadedAt", "recording", "summary", "importedFrom"};
    private static final long MAX_META_BYTES = 64 * 1024;
    private static final int MAX_TRACES = 1000;

    private RecordingZip() {
    }

    public static void write(OutputStream out, JSONObject meta, Path session, Path systemLog, Path video) throws IOException {
        write(out, meta, session, systemLog, video, null);
    }

    public static void write(OutputStream out, JSONObject meta, Path session, Path systemLog, Path video, Path traceDir)
            throws IOException {
        write(out, meta, session, systemLog, video, traceDir, null);
    }

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
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Path f : AppExport.files(appDir)) {
                add(zip, AppExport.DIR + "/" + f.getFileName().toString(), f);
            }
        }
        if (video != null) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            add(zip, RecordingStore.VIDEO, video);
        }
        zip.finish();
        zip.flush();
    }

    private static void add(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    public static JSONObject importZip(RecordingStore store, Path zipFile, long maxBytes, boolean keepServerData,
            String owner, String ownerName) throws IOException, RecordingStore.InvalidUpload {
        return importZip(store, zipFile, maxBytes, keepServerData, owner, ownerName, DEFAULT_TOTAL_FACTOR * maxBytes, DEFAULT_MAX_ENTRIES);
    }

    static final int DEFAULT_TOTAL_FACTOR = 3;
    static final int DEFAULT_MAX_ENTRIES = 5000;

    public static JSONObject importZip(RecordingStore store, Path zipFile, long maxBytes, boolean keepServerData,
            String owner, String ownerName, long maxTotalBytes, int maxEntries) throws IOException, RecordingStore.InvalidUpload {
        BudgetZip zip;
        try {
            zip = new BudgetZip(zipFile.toFile(), maxTotalBytes);
        } catch (IOException e) {
            throw new RecordingStore.InvalidUpload("This is not a ZIP file.");
        }
        int entries = zip.size();
        if (maxEntries > 0 && entries > maxEntries) {
            zip.close();
            throw new RecordingStore.InvalidUpload("The ZIP has " + entries + " files; the limit is " + maxEntries + ".");
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
            meta.remove("captureId");
            String id = meta.optString("id");
            try {
                ZipEntry videoEntry = zip.getEntry(RecordingStore.VIDEO);
                if (videoEntry != null) {
                    try (InputStream in = zip.getInputStream(videoEntry)) {
                        meta = store.writeVideo(id, in, maxBytes);
                    }
                }
                ZipEntry logEntry = zip.getEntry(RecordingStore.SYSTEM_LOG);
                if (logEntry != null && keepServerData) {
                    meta.put(RecordingStore.CAPTURE, importSystemLog(store, id, zip, logEntry, maxBytes));
                    importTraces(store, id, zip, maxBytes);
                    importGauges(store, id, zip, maxBytes);
                    importServerInfo(store, id, zip);
                    importGlowroot(store, id, zip, maxBytes);
                }
                if (keepServerData) {
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
                if (!keepServerData && (logEntry != null || zip.getEntry(AppExport.DIR + "/" + AppExport.LIST) != null)) {
                    meta.put("serverDataSkipped", true);
                }
                return meta;
            } catch (RecordingStore.InvalidUpload | IOException | RuntimeException e) {
                store.delete(id);
                throw e;
            }
        } catch (BudgetExceeded e) {
            throw new RecordingStore.InvalidUpload(e.getMessage());
        } finally {
            zip.close();
        }
    }

    static final class BudgetExceeded extends IOException {
        BudgetExceeded(long limit) {
            super("The ZIP unpacks to more than " + (limit / (1024 * 1024)) + " MB, the import limit.");
        }
    }

    static final class BudgetZip extends ZipFile {
        private final long limit;
        private long used;

        BudgetZip(java.io.File file, long limit) throws IOException {
            super(file);
            this.limit = limit;
        }

        @Override
        public InputStream getInputStream(ZipEntry entry) throws IOException {
            InputStream in = super.getInputStream(entry);
            if (in == null || limit <= 0) {
                return in;
            }
            return new java.io.FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b != -1) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                @Override
                public long skip(long n) throws IOException {
                    long k = super.skip(n);
                    if (k > 0) {
                        count(k);
                    }
                    return k;
                }
            };
        }

        private void count(long n) throws BudgetExceeded {
            used += n;
            if (used > limit) {
                throw new BudgetExceeded(limit);
            }
        }
    }

    private static JSONObject importSystemLog(RecordingStore store, String id, ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException, RecordingStore.InvalidUpload, JSONException {
        Path target = store.path(id, RecordingStore.SYSTEM_LOG);
        try (InputStream in = zip.getInputStream(entry)) {
            SessionFile.copy(in, target, maxBytes, RecordingStore.SYSTEM_LOG);
        }
        try {
            return SessionFile.systemLogSummary(target);
        } catch (RecordingStore.InvalidUpload | IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

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
            GaugeStore.write(store.dir(id), tmp);
        } catch (JsonStream.Malformed | JsonStream.TooLarge e) {
            throw new RecordingStore.InvalidUpload(GaugeStore.MERGED + " in the ZIP is not valid JSON.");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

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
                e.put("imported", true);
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
