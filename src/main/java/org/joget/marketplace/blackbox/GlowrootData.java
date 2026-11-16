package org.joget.marketplace.blackbox;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GlowrootData {

    static final String DIR = "glowroot";
    static final String TAG = GlowrootTag.ATTRIBUTE;
    static final Pattern NODE_ZIP = Pattern.compile("^[A-Za-z0-9.-]+\\.zip$");

    static final String TRACE_CAPPED = "trace-detail.capped.db";
    static final long[] AGGREGATE_MS = {60_000L, 300_000L, 1_800_000L, 14_400_000L};
    static final long[] GAUGE_MS = {5_000L, 60_000L, 300_000L, 1_800_000L, 14_400_000L};
    private static final String[] TRACE_IDS = {"ENTRIES_CAPPED_ID", "QUERIES_CAPPED_ID",
        "SHARED_QUERY_TEXTS_CAPPED_ID", "MAIN_THREAD_PROFILE_CAPPED_ID", "AUX_THREAD_PROFILE_CAPPED_ID"};
    private static final String[] AGGREGATE_IDS = {"QUERIES_CAPPED_ID", "SERVICE_CALLS_CAPPED_ID",
        "MAIN_THREAD_PROFILE_CAPPED_ID", "AUX_THREAD_PROFILE_CAPPED_ID"};
    private static final int HEADER = 20;
    private static final int BLOCK_HEADER = 8;
    private static final long OVERWRITE_MARGIN = 1024 * 1024;
    private static final int DELETE_BATCH = 20_000;
    private static final int OPEN_TRIES = 3;
    static final String PAGE_STORE_FILE = "data.h2.db";
    static final String MV_STORE_FILE = "data.mv.db";

    private GlowrootData() {
    }

    static long spaceNeeded(long h2Bytes) {
        return h2Bytes + Math.max(64L * 1024 * 1024, h2Bytes / 10);
    }

    static String overall(org.json.JSONArray statuses) {
        boolean saved = false;
        boolean failed = false;
        for (int i = 0; i < statuses.length(); i++) {
            String state = statuses.optJSONObject(i) != null ? statuses.optJSONObject(i).optString("state") : "";
            if ("waiting".equals(state) || "building".equals(state)) {
                return "building";
            }
            saved |= "saved".equals(state);
            failed |= "failed".equals(state);
        }
        return saved ? "saved" : failed ? "failed" : null;
    }

    static String rollupCapped(int level) {
        return "rollup-" + level + "-detail.capped.db";
    }

    static final class Result {
        int traces;
        int aggregates;
        int gauges;
        int lostBlocks;
        long cappedBytes;
    }

    /** The file the driver keeps "data" in: H2 1.x as Glowroot 0.14.2 to 0.14.4 uses it writes data.h2.db, H2 2.x (0.14.6) data.mv.db. */
    static String dbFile(Driver h2) {
        return h2.getMajorVersion() >= 2 ? MV_STORE_FILE : PAGE_STORE_FILE;
    }

    static String dbFile(Path dataDir) {
        return Files.isRegularFile(dataDir.resolve(MV_STORE_FILE)) ? MV_STORE_FILE
                : Files.isRegularFile(dataDir.resolve(PAGE_STORE_FILE)) ? PAGE_STORE_FILE : null;
    }

    // Glowroot is writing the file while it is copied. A copy caught in the middle of a write may not open: copy again.
    private static Connection openCopy(Driver h2, Path srcData, Path outData) throws IOException, SQLException {
        String file = dbFile(h2);
        Properties login = new Properties();
        login.setProperty("user", "sa");
        login.setProperty("password", "");
        String url = "jdbc:h2:" + outData.resolve("data").toAbsolutePath() + ";CACHE_SIZE=8192;DB_CLOSE_ON_EXIT=FALSE";
        for (int attempt = 1;; attempt++) {
            Files.copy(srcData.resolve(file), outData.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            try {
                Connection c = h2.connect(url, login);
                if (c == null) {
                    throw new IOException("The H2 driver does not accept " + url);
                }
                return c;
            } catch (SQLException e) {
                Files.deleteIfExists(outData.resolve("data.trace.db"));
                if (attempt >= OPEN_TRIES || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
            }
        }
    }

    static Result slice(Driver h2, Path srcData, Path outData, long from, long to, String recId) throws IOException, SQLException {
        return slice(h2, srcData, outData, from, to, recId, false);
    }

    static Result slice(Driver h2, Path srcData, Path outData, long from, long to, String recId, boolean allTraces)
            throws IOException, SQLException {
        Files.createDirectories(outData);
        Result r = new Result();
        try (Connection c = openCopy(h2, srcData, outData)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                if (allTraces) {
                    deleteInBatches(c, s, "DELETE FROM TRACE WHERE CAPTURE_TIME < " + from + " OR START_TIME > " + to);
                    deleteInBatches(c, s, "DELETE FROM TRACE_ATTRIBUTE WHERE TRACE_ID NOT IN (SELECT ID FROM TRACE)");
                } else {
                    deleteInBatches(c, s, "DELETE FROM TRACE_ATTRIBUTE WHERE TRACE_ID NOT IN (SELECT TRACE_ID FROM TRACE_ATTRIBUTE"
                            + " WHERE NAME = " + quote(TAG) + " AND \"VALUE\" = " + quote(recId) + ")");
                    deleteInBatches(c, s, "DELETE FROM TRACE WHERE ID NOT IN (SELECT TRACE_ID FROM TRACE_ATTRIBUTE)");
                }
                for (int i = 0; i < AGGREGATE_MS.length; i++) {
                    for (String kind : new String[]{"TT", "TN"}) {
                        deleteInBatches(c, s, "DELETE FROM AGGREGATE_" + kind + "_ROLLUP_" + i + outside(from, to, AGGREGATE_MS[i]));
                    }
                }
                for (int i = 0; i < GAUGE_MS.length; i++) {
                    deleteInBatches(c, s, "DELETE FROM GAUGE_VALUE_ROLLUP_" + i + outside(from, to, GAUGE_MS[i]));
                }
                deleteInBatches(c, s, "DELETE FROM INCIDENT");
            }
            try (CappedReader in = new CappedReader(srcData.resolve(TRACE_CAPPED));
                    CappedWriter out = new CappedWriter(outData.resolve(TRACE_CAPPED), in.sizeKb)) {
                r.traces = remap(c, "TRACE", "ID", TRACE_IDS, in, out, r);
                r.cappedBytes += out.length();
            }
            for (int i = 0; i < AGGREGATE_MS.length; i++) {
                Path capped = srcData.resolve(rollupCapped(i));
                if (!Files.isRegularFile(capped)) {
                    continue;
                }
                try (CappedReader in = new CappedReader(capped);
                        CappedWriter out = new CappedWriter(outData.resolve(rollupCapped(i)), in.sizeKb)) {
                    r.aggregates += remap(c, "AGGREGATE_TT_ROLLUP_" + i, "_ROWID_", AGGREGATE_IDS, in, out, r);
                    r.aggregates += remap(c, "AGGREGATE_TN_ROLLUP_" + i, "_ROWID_", AGGREGATE_IDS, in, out, r);
                    r.cappedBytes += out.length();
                }
            }
            try (Statement s = c.createStatement()) {
                for (int i = 0; i < GAUGE_MS.length; i++) {
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM GAUGE_VALUE_ROLLUP_" + i)) {
                        r.gauges += rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }
            c.commit();
            try (Statement s = c.createStatement()) {
                s.execute("SHUTDOWN COMPACT");
            }
        }
        Files.deleteIfExists(outData.resolve("data.trace.db"));
        return r;
    }

    private static void deleteInBatches(Connection c, Statement s, String delete) throws SQLException {
        while (s.executeUpdate(delete + " LIMIT " + DELETE_BATCH) > 0) {
            c.commit();
            s.execute("CHECKPOINT");
        }
        c.commit();
    }

    static String outside(long from, long to, long bucketMs) {
        return " WHERE CAPTURE_TIME <= " + (from - bucketMs) + " OR CAPTURE_TIME >= " + (to + 2 * bucketMs);
    }

    private static int remap(Connection c, String table, String key, String[] cols, CappedReader in, CappedWriter out, Result r)
            throws SQLException, IOException {
        String select = "SELECT " + key + ", " + String.join(", ", cols) + " FROM " + table;
        String update = "UPDATE " + table + " SET " + String.join(" = ?, ", cols) + " = ? WHERE " + key + " = ?";
        int rows = 0;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select); PreparedStatement u = c.prepareStatement(update)) {
            while (rs.next()) {
                for (int i = 0; i < cols.length; i++) {
                    long id = rs.getLong(i + 2);
                    boolean none = rs.wasNull();
                    byte[] block = none ? null : in.block(id);
                    if (block == null) {
                        if (!none) {
                            r.lostBlocks++;
                        }
                        u.setNull(i + 1, Types.BIGINT);
                    } else {
                        u.setLong(i + 1, out.write(block));
                    }
                }
                u.setObject(cols.length + 1, rs.getObject(1));
                u.addBatch();
                rows++;
            }
            u.executeBatch();
        }
        return rows;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static final class CappedReader implements Closeable {
        private final RandomAccessFile f;
        final long currIndex;
        final int sizeKb;
        final long base;
        final long size;

        CappedReader(Path p) throws IOException {
            f = new RandomAccessFile(p.toFile(), "r");
            currIndex = f.readLong();
            sizeKb = f.readInt();
            base = f.readLong();
            size = sizeKb * 1024L;
            if (size <= 0) {
                f.close();
                throw new IOException("Not a Glowroot capped file: " + p.getFileName());
            }
        }

        long margin() {
            return Math.min(OVERWRITE_MARGIN, size / 16);
        }

        static long smallestKept(long base, long currIndex, long size) {
            return Math.max(base, currIndex - size);
        }

        byte[] block(long id) throws IOException {
            if (id < smallestKept(base, currIndex, size) || id + BLOCK_HEADER > currIndex) {
                return null;
            }
            f.seek(HEADER + (id - base) % size);
            long len = f.readLong();
            if (len < 0 || len > size || id + BLOCK_HEADER + len > currIndex) {
                return null;
            }
            byte[] b = new byte[(int) len];
            int done = 0;
            while (done < len) {
                long pos = (id + BLOCK_HEADER + done - base) % size;
                int n = (int) Math.min(len - done, size - pos);
                f.seek(HEADER + pos);
                f.readFully(b, done, n);
                done += n;
            }
            f.seek(0);
            long now = f.readLong();
            int nowSizeKb = f.readInt();
            long nowBase = f.readLong();
            if (nowSizeKb != sizeKb || nowBase != base || id < smallestKept(base, now + margin(), size)) {
                return null;
            }
            return b;
        }

        @Override
        public void close() throws IOException {
            f.close();
        }
    }

    static final class CappedWriter implements Closeable {
        private final RandomAccessFile f;
        private final int sizeKb;
        private long cursor;

        CappedWriter(Path p, int sizeKb) throws IOException {
            Files.deleteIfExists(p);
            this.f = new RandomAccessFile(p.toFile(), "rw");
            this.sizeKb = sizeKb;
            f.seek(HEADER);
        }

        long write(byte[] block) throws IOException {
            if (cursor + BLOCK_HEADER + block.length > sizeKb * 1024L) {
                throw new IOException("The kept blocks do not fit in the capped file's size");
            }
            long id = cursor;
            f.writeLong(block.length);
            f.write(block);
            cursor += BLOCK_HEADER + block.length;
            return id;
        }

        long length() {
            return HEADER + cursor;
        }

        @Override
        public void close() throws IOException {
            f.seek(0);
            f.writeLong(cursor);
            f.writeInt(sizeKb);
            f.writeLong(0L);
            f.close();
        }
    }

    static String readme(String recordingId, String node, String agentVersion, long from, long to) {
        String range = "from=" + from + "&to=" + to;
        String version = agentVersion == null ? null : agentVersion.split(",")[0].trim();
        return "BlackBox recording " + recordingId + ": Glowroot data of node " + node + "\n"
                + "Cut out of Glowroot " + (version == null ? "0.14" : version) + " in its own format: the traces of the\n"
                + "recorded requests, plus the aggregates and gauges of all traffic on that node during the recording.\n"
                + "\n"
                + "Recording: " + java.time.Instant.ofEpochMilli(from) + " to " + java.time.Instant.ofEpochMilli(to) + " (UTC)\n"
                + "\n"
                + "Open it in Glowroot's offline viewer, of the same version:\n"
                + "1. Download glowroot-" + (version == null ? "0.14.2" : version) + "-dist.zip from\n"
                + "   https://github.com/glowroot/glowroot/releases and unzip it.\n"
                + "2. Copy data/ from this ZIP into that glowroot folder (next to glowroot.jar).\n"
                + "3. In that folder run: java -jar glowroot.jar\n"
                + "   It prints \"Offline viewer listening on 127.0.0.1:4000\". Port 4000 must be free.\n"
                + "4. Open http://localhost:4000/transaction/average?transaction-type=Web&" + range + "\n"
                + "   Traces: http://localhost:4000/transaction/traces?transaction-type=Web&" + range + "\n"
                + "\n"
                + "The offline viewer only reads the data: it records nothing and deletes nothing, however old. On its\n"
                + "first start it writes Glowroot's default admin.json; change its bindAddress to 0.0.0.0 only to open the\n"
                + "viewer from another machine or a container. The data holds SQL with its parameter values and request\n"
                + "details: share it accordingly.\n";
    }

    static void zip(Path data, String readme, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        try (OutputStream o = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(o)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(data)) {
                for (Path f : files) {
                    if (Files.isRegularFile(f)) {
                        z.putNextEntry(new ZipEntry("data/" + f.getFileName()));
                        Files.copy(f, z);
                        z.closeEntry();
                    }
                }
            }
            entry(z, "README.txt", readme);
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void entry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static void writeStatus(Path outer, String node, JSONObject status) throws IOException {
        Path dir = Files.createDirectories(outer.resolve(DIR));
        Path tmp = dir.resolve(node + ".json." + Long.toHexString(System.nanoTime()) + ".part");
        Files.write(tmp, status.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, dir.resolve(node + ".json"), StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean unfinished(Path outer, String node) {
        Path f = outer == null ? null : outer.resolve(DIR).resolve(node + ".json");
        if (f == null || !Files.isRegularFile(f)) {
            return false;
        }
        try {
            String state = new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).optString("state");
            return "waiting".equals(state) || "building".equals(state);
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    static final long WAITING_STALE_MS = 3 * 3600 * 1000L + 15 * 60_000L;
    static final long BUILDING_STALE_MS = 2 * 3600 * 1000L;

    static JSONArray statuses(Path outer) throws IOException {
        return statuses(outer, System.currentTimeMillis());
    }

    static JSONArray statuses(Path outer, long now) throws IOException {
        Path dir = outer == null ? null : outer.resolve(DIR);
        JSONArray list = new JSONArray();
        if (dir == null || !Files.isDirectory(dir)) {
            return list;
        }
        java.util.List<JSONObject> all = new java.util.ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try {
                    all.add(new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
                } catch (JSONException e) {
                }
            }
        }
        all.sort(java.util.Comparator.comparing((JSONObject s) -> s.optString("node")));
        for (JSONObject s : all) {
            String state = s.optString("state");
            long updated = s.optLong("updated", now);
            if ("waiting".equals(state) && now - updated > WAITING_STALE_MS
                    || "building".equals(state) && now - updated > BUILDING_STALE_MS) {
                try {
                    s.put("state", "failed").put("reason", "No news from " + s.optString("node") + " since it was "
                            + state + ": Joget there restarted or stopped, or couldn't reach the recording's folder");
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            list.put(s);
        }
        return list;
    }

    static Path zipFile(Path outer, String node) {
        if (outer == null || node == null || !NODE_ZIP.matcher(node + ".zip").matches()) {
            return null;
        }
        Path f = outer.resolve(DIR).resolve(node + ".zip");
        return Files.isRegularFile(f) ? f : null;
    }

    static void moveAll(Path from, Path to) throws IOException {
        Path src = from.resolve(DIR);
        if (!Files.isDirectory(src)) {
            return;
        }
        Path dst = Files.createDirectories(to.resolve(DIR));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(src)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (Files.isRegularFile(f) && !name.endsWith(".part")) {
                    Files.move(f, dst.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try {
            Files.delete(src);
        } catch (IOException e) {
        }
    }
}
