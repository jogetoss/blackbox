package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The recordings list's summary of a recording's server data, kept in meta.json as "serverData" so the list never
 * reads the trace or gauge files:
 * {slowMs, traces: {total, slow, pending}, load: {cpu, heap, threads, db (peak fractions 0-1), dbWaiting, gcMs}, info}.
 * Made on upload; the trace counts are refreshed whenever a trace of the recording changes (TraceCollector).
 */
final class ServerData {

    /** Glowroot's slow threshold when it can't be read, or when it is set to keep every request (under 100 ms). */
    static final long DEFAULT_SLOW_MS = 2000;

    private ServerData() {
    }

    /** "Slow" for the lists: Glowroot's slow threshold, unless it is so low that it means "trace everything". */
    static long slowMs(long glowrootThresholdMs) {
        return glowrootThresholdMs >= 100 ? glowrootThresholdMs : DEFAULT_SLOW_MS;
    }

    static JSONObject of(Path recDir, long slowMs) throws IOException {
        try {
            JSONObject d = new JSONObject().put("slowMs", slowMs).put("traces", traces(recDir, slowMs));
            JSONObject load = load(recDir);
            if (load != null) {
                d.put("load", load);
            }
            String glowroot = GlowrootData.overall(GlowrootData.statuses(recDir));
            if (glowroot != null) {
                d.put("glowroot", glowroot);
            }
            return d.put("info", Files.isRegularFile(recDir.resolve(ServerInfo.FILE)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static JSONObject traces(Path recDir, long slowMs) throws IOException, JSONException {
        JSONArray list = TraceStore.merge(recDir, System.currentTimeMillis());
        int slow = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e != null && e.optLong("ms", -1) >= slowMs) {
                slow++;
            }
        }
        return new JSONObject().put("total", list.length()).put("slow", slow).put("pending", TraceStore.pending(list));
    }

    /** Peaks over all nodes and samples, or null without samples. */
    static JSONObject load(Path recDir) throws IOException, JSONException {
        JSONObject merged = GaugeStore.merge(recDir);
        JSONObject nodes = merged != null ? merged.optJSONObject("nodes") : null;
        if (nodes == null) {
            return null;
        }
        double cpu = 0, heap = 0, threads = 0, db = 0;
        long dbWaiting = 0, gcMs = 0;
        for (Iterator<?> it = nodes.keys(); it.hasNext(); ) {
            JSONArray samples = nodes.optJSONArray(String.valueOf(it.next()));
            for (int i = 0; samples != null && i < samples.length(); i++) {
                JSONObject s = samples.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                cpu = Math.max(cpu, s.optDouble("cpu", 0));
                heap = Math.max(heap, ratio(s, "heapUsed", "heapMax"));
                threads = Math.max(threads, ratio(s, "threadsBusy", "threadsMax"));
                db = Math.max(db, ratio(s, "dbActive", "dbMax"));
                dbWaiting = Math.max(dbWaiting, s.optLong("dbWaiting"));
                gcMs = Math.max(gcMs, s.optLong("gcMs"));
            }
        }
        return new JSONObject().put("cpu", round(cpu)).put("heap", round(heap)).put("threads", round(threads)).put("db", round(db))
                .put("dbWaiting", dbWaiting).put("gcMs", gcMs);
    }

    /** Refreshes the trace counts in a recording's meta.json (a trace was saved, failed or retried). */
    static void refreshTraces(RecordingStore store, String recId) {
        try {
            JSONObject meta = store.readMeta(recId);
            Path dir = store.dir(recId);
            if (meta == null || dir == null) {
                return;
            }
            JSONObject d = meta.optJSONObject("serverData");
            long slowMs = d != null ? d.optLong("slowMs", DEFAULT_SLOW_MS) : DEFAULT_SLOW_MS;
            if (d == null) {
                d = new JSONObject().put("slowMs", slowMs);
            }
            d.put("traces", traces(dir, slowMs));
            meta.put("serverData", d);
            store.updateMeta(recId, meta);
        } catch (IOException | JSONException e) {
            // the list shows the old counts until the next change
        }
    }

    /** The Glowroot data state (GlowrootData.overall) changed: the recordings list shows it. */
    static void refreshGlowroot(RecordingStore store, String recId) {
        try {
            JSONObject meta = store.readMeta(recId);
            Path dir = store.dir(recId);
            if (meta == null || dir == null) {
                return;
            }
            JSONObject d = meta.optJSONObject("serverData");
            if (d == null) {
                d = new JSONObject().put("slowMs", DEFAULT_SLOW_MS);
            }
            String state = GlowrootData.overall(GlowrootData.statuses(dir));
            if (state == null) {
                d.remove("glowroot");
            } else {
                d.put("glowroot", state);
            }
            meta.put("serverData", d);
            store.updateMeta(recId, meta);
        } catch (IOException | JSONException e) {
            // the list shows the old state until the next change
        }
    }

    private static double ratio(JSONObject s, String used, String max) {
        long m = s.optLong(max);
        return m > 0 ? (double) s.optLong(used) / m : 0;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
