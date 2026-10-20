package org.joget.marketplace.blackbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Server load samples ({@link GaugeSampler}) in a gauges/ folder next to traces/: gauges/{node}.jsonl, one sample per
 * line, appended only by that node. Moved into the recording on upload like the traces.
 */
final class GaugeStore {

    static final String DIR = "gauges";
    static final String MERGED = "gauges.json"; // in a recording ZIP
    private static final Object LOCK = new Object();

    private GaugeStore() {
    }

    static void append(Path outer, String node, JSONObject sample) throws IOException {
        Path dir = Files.createDirectories(outer.resolve(DIR));
        String line = sample.toString() + "\n";
        synchronized (LOCK) {
            try (Writer out = Files.newBufferedWriter(dir.resolve(node + ".jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(line);
            }
        }
    }

    /** {"nodes": {"node-a": [samples sorted by ts], ...}}, or null when there are none. */
    static JSONObject merge(Path outer) throws IOException {
        Path dir = outer == null ? null : outer.resolve(DIR);
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        JSONObject nodes = new JSONObject();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (!TraceStore.isIndexFile(name)) {
                    continue;
                }
                List<JSONObject> samples = new ArrayList<>();
                try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    for (String line; (line = in.readLine()) != null; ) {
                        try {
                            samples.add(new JSONObject(line));
                        } catch (JSONException e) {
                            // cut short by a crash
                        }
                    }
                }
                samples.sort(Comparator.comparingLong((JSONObject s) -> s.optLong("ts")));
                try {
                    nodes.put(name.substring(0, name.length() - ".jsonl".length()), new JSONArray(samples));
                } catch (JSONException e) {
                    throw new IOException(e);
                }
            }
        }
        if (nodes.length() == 0) {
            return null;
        }
        try {
            return new JSONObject().put("nodes", nodes);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static void moveAll(Path fromOuter, Path toOuter) throws IOException {
        TraceStore.moveFolder(fromOuter, toOuter, DIR, name -> false);
    }

    /** A ZIP's gauges.json back into gauges/{node}.jsonl files. */
    static void write(Path outer, JSONObject merged) throws IOException {
        JSONObject nodes = merged.optJSONObject("nodes");
        if (nodes == null) {
            return;
        }
        Path dir = Files.createDirectories(outer.resolve(DIR));
        for (java.util.Iterator<?> it = nodes.keys(); it.hasNext(); ) {
            String node = String.valueOf(it.next());
            JSONArray samples = nodes.optJSONArray(node);
            if (samples == null || !TraceStore.isIndexFile(node + ".jsonl")) {
                continue;
            }
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < samples.length(); i++) {
                JSONObject s = samples.optJSONObject(i);
                if (s != null) {
                    lines.append(s.toString()).append('\n');
                }
            }
            Files.write(dir.resolve(node + ".jsonl"), lines.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
