package org.joget.marketplace.blackbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Glowroot traces of a recording's requests, kept in a traces/ folder inside the capture's pending folder and, after
 * the upload, inside the recording's folder. Methods take that outer folder.
 * <ul>
 * <li>traces/{node}.jsonl: one line per state change of a request's trace, appended by the node that served it
 * (so two nodes never write the same file). The last line of a key wins.</li>
 * <li>traces/{node}-{traceId}.html: Glowroot's own export of the trace (a self-contained page).</li>
 * </ul>
 * States: pending (request running, or waiting for Glowroot), saved, none (Glowroot kept no trace), failed.
 */
final class TraceStore {

    static final String DIR = "traces";
    static final String MERGED = "traces.json"; // in a recording ZIP, next to traces/{file}
    static final String IMPORT_NODE = "import";
    static final long STALE_MS = 10 * 60 * 1000L; // a pending trace with no news for this long has failed
    private static final Pattern INDEX = Pattern.compile("^[A-Za-z0-9.-]+\\.jsonl$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9.-]+-[0-9a-f]{8,64}\\.html$");
    private static final Object LOCK = new Object();

    private TraceStore() {
    }

    static boolean isTraceFile(String name) {
        return name != null && FILE.matcher(name).matches();
    }

    static boolean isIndexFile(String name) {
        return name != null && INDEX.matcher(name).matches();
    }

    static String fileName(String node, String traceId) {
        return node + "-" + traceId.toLowerCase() + ".html";
    }

    static void append(Path dir, String node, JSONObject entry) throws IOException {
        String line = entry.toString() + "\n";
        Path traces = Files.createDirectories(dir.resolve(DIR));
        synchronized (LOCK) {
            try (Writer out = Files.newBufferedWriter(traces.resolve(node + ".jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(line);
            }
        }
    }

    static void writeTrace(Path dir, String name, byte[] html) throws IOException {
        Path traces = Files.createDirectories(dir.resolve(DIR));
        Path tmp = traces.resolve(name + ".part");
        Files.write(tmp, html);
        Files.move(tmp, traces.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The latest state of every trace in dir, sorted by request time. Pending ones with no news are marked failed. */
    static JSONArray merge(Path outer, long now) throws IOException {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        Path dir = outer == null ? null : outer.resolve(DIR);
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path f : files) {
                    if (isIndexFile(f.getFileName().toString())) {
                        read(f, latest);
                    }
                }
            }
        }
        List<JSONObject> list = new ArrayList<>(latest.values());
        list.sort(Comparator.comparingLong((JSONObject e) -> e.optLong("ts")));
        JSONArray result = new JSONArray();
        for (JSONObject e : list) {
            try {
                if ("pending".equals(e.optString("state")) && now - e.optLong("updated") > STALE_MS) {
                    e.put("state", "failed").put("reason", e.optString("reason").startsWith(TraceCollector.RETRY_PREFIX)
                            ? "Node " + e.optString("node") + " did not answer the retry within " + (STALE_MS / 60000) + " minutes"
                            : "No news for " + (STALE_MS / 60000) + " minutes: the server restarted or the request is stuck. Last: "
                            + e.optString("reason"));
                }
                if (!e.optString("file").isEmpty() && (!isTraceFile(e.optString("file")) || !Files.isRegularFile(dir.resolve(e.optString("file"))))) {
                    e.remove("file"); // never written or not copied
                }
            } catch (JSONException ex) {
                // keys and values are plain
            }
            result.put(e);
        }
        return result;
    }

    private static void read(Path f, Map<String, JSONObject> latest) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                JSONObject e;
                try {
                    e = new JSONObject(line);
                } catch (JSONException ex) {
                    continue; // cut short by a crash
                }
                String key = e.optString("key");
                JSONObject prev = latest.get(key);
                if (!key.isEmpty() && (prev == null || prev.optLong("updated") <= e.optLong("updated"))) {
                    latest.put(key, e);
                }
            }
        }
    }

    static int pending(JSONArray traces) {
        int n = 0;
        for (int i = 0; i < traces.length(); i++) {
            JSONObject e = traces.optJSONObject(i);
            if (e != null && "pending".equals(e.optString("state"))) {
                n++;
            }
        }
        return n;
    }

    /** Moves the trace files of from into to (the upload). Safe to run again and from two nodes at once. */
    static void moveAll(Path fromOuter, Path toOuter) throws IOException {
        moveFolder(fromOuter, toOuter, DIR, TraceStore::isTraceFile);
    }

    /**
     * Moves outer/dir/ of from into to: *.jsonl files are appended to the target's (a node may still be writing),
     * files accepted by other are moved. Safe to run again and from two nodes at once.
     */
    static void moveFolder(Path fromOuter, Path toOuter, String dir, java.util.function.Predicate<String> other) throws IOException {
        Path from = fromOuter.resolve(dir);
        if (!Files.isDirectory(from)) {
            return;
        }
        Path to = Files.createDirectories(toOuter.resolve(dir));
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> list = Files.newDirectoryStream(from)) {
            for (Path f : list) {
                String name = f.getFileName().toString();
                if (isIndexFile(name) || other.test(name)) {
                    files.add(f);
                }
            }
        }
        for (Path f : files) {
            Path target = to.resolve(f.getFileName().toString());
            try {
                if (isIndexFile(f.getFileName().toString())) {
                    synchronized (LOCK) { // appended as a stream: the index is never held whole
                        try (java.io.InputStream in = Files.newInputStream(f);
                                java.io.OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                            }
                        }
                    }
                    Files.delete(f);
                } else {
                    Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (NoSuchFileException e) {
                // moved by the other node
            }
        }
        try {
            Files.deleteIfExists(from);
        } catch (IOException e) {
            // a node wrote again meanwhile: the next move takes it
        }
    }

    /** The trace files of a recording folder (for the ZIP). */
    static List<Path> traceFiles(Path outer) throws IOException {
        List<Path> result = new ArrayList<>();
        Path dir = outer.resolve(DIR);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> list = Files.newDirectoryStream(dir)) {
                for (Path f : list) {
                    if (isTraceFile(f.getFileName().toString())) {
                        result.add(f);
                    }
                }
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    // ---------- Glowroot data ----------

    /**
     * Is header (Glowroot's trace header) the trace of this request? Same method and path, and it started within
     * 2 seconds of when the BlackBox filter saw the request start (Glowroot starts timing a little earlier).
     */
    static boolean matches(JSONObject header, String method, String url, long start) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        String headline = header.optString("headline");
        if (headline.contains("?")) {
            headline = headline.substring(0, headline.indexOf('?'));
        }
        JSONObject detail = header.optJSONObject("detail");
        String m = detail != null ? detail.optString("Request http method") : "";
        return path.equals(headline) && (m.isEmpty() || m.equalsIgnoreCase(method))
                && Math.abs(header.optLong("startTime") - start) <= 2000;
    }

    /**
     * The numbers the Traces tab shows: duration, CPU, SQL count and time, where the time went (self time per timer)
     * and the slowest queries. header and queries are Glowroot's JSON for one trace.
     */
    static JSONObject summary(JSONObject header, JSONObject queries) throws JSONException {
        JSONObject s = new JSONObject()
                .put("durationMs", Math.round(header.optLong("durationNanos") / 1e6))
                .put("entries", header.optInt("entryCount"))
                .put("queries", header.optInt("queryCount"));
        JSONObject stats = header.optJSONObject("mainThreadStats");
        if (stats != null && stats.has("totalCpuNanos")) {
            s.put("cpuMs", Math.round(stats.optLong("totalCpuNanos") / 1e6));
        }
        JSONObject error = header.optJSONObject("error");
        if (error != null) {
            s.put("error", error.optString("message"));
        }

        Map<String, Long> self = new HashMap<>();
        JSONObject root = header.optJSONObject("mainThreadRootTimer");
        if (root != null) {
            selfTimes(root, self);
        }
        List<Map.Entry<String, Long>> timers = new ArrayList<>(self.entrySet());
        timers.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JSONArray t = new JSONArray();
        for (Map.Entry<String, Long> e : timers.subList(0, Math.min(6, timers.size()))) {
            t.put(new JSONObject().put("name", e.getKey()).put("ms", Math.round(e.getValue() / 1e6)));
        }
        s.put("timers", t);

        JSONArray list = queries != null ? queries.optJSONArray("queries") : null;
        JSONArray texts = queries != null ? queries.optJSONArray("sharedQueryTexts") : null;
        List<JSONObject> qs = new ArrayList<>();
        long sqlNanos = 0;
        int sqlCount = 0;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject q = list.optJSONObject(i);
            if (q == null) {
                continue;
            }
            qs.add(q);
            sqlNanos += q.optLong("totalDurationNanos");
            sqlCount += q.optInt("executionCount");
        }
        s.put("sqlCount", sqlCount).put("sqlMs", Math.round(sqlNanos / 1e6));
        qs.sort((a, b) -> Long.compare(b.optLong("totalDurationNanos"), a.optLong("totalDurationNanos")));
        JSONArray slowest = new JSONArray();
        for (JSONObject q : qs.subList(0, Math.min(5, qs.size()))) {
            JSONObject text = texts != null ? texts.optJSONObject(q.optInt("sharedQueryTextIndex", -1)) : null;
            slowest.put(new JSONObject()
                    .put("type", q.optString("type"))
                    .put("text", queryText(text))
                    .put("ms", Math.round(q.optLong("totalDurationNanos") / 1e6))
                    .put("count", q.optInt("executionCount"))
                    .put("rows", q.optLong("totalRows")));
        }
        s.put("slowestQueries", slowest);
        return s;
    }

    private static long selfTimes(JSONObject timer, Map<String, Long> self) {
        long total = timer.optLong("totalNanos");
        long children = 0;
        JSONArray kids = timer.optJSONArray("childTimers");
        for (int i = 0; kids != null && i < kids.length(); i++) {
            JSONObject k = kids.optJSONObject(i);
            if (k != null) {
                children += selfTimes(k, self);
            }
        }
        self.merge(timer.optString("name"), Math.max(0, total - children), Long::sum);
        return total;
    }

    private static String queryText(JSONObject text) {
        if (text == null) {
            return "";
        }
        String full = text.optString("fullText");
        String s = !full.isEmpty() ? full : text.optString("truncatedText") + " … " + text.optString("truncatedEndText");
        return s.length() > 2000 ? s.substring(0, 2000) + " …" : s;
    }
}
