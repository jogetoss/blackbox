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

/**
 * One recording cut out of a Glowroot 0.14 embedded data folder, in Glowroot's own format, so it opens in a standalone
 * Glowroot of the same version. Built by {@link GlowrootPackager} on each node, as glowroot/{node}.zip next to the
 * traces, with glowroot/{node}.json saying how far it got.
 *
 * Glowroot keeps its data in data.h2.db (H2 1.3, locked while the agent runs) and five *.capped.db ring files that
 * the H2 rows point into (a "capped id" is a position in the ring). The cut:
 * <ol>
 * <li>copies data.h2.db (the copy is not locked), then deletes every row outside the recording: traces not tagged
 * with its id ({@link GlowrootTag}), aggregates and gauges outside its time (keeping one rollup bucket more on each
 * side, which Glowroot's charts show), and incidents;</li>
 * <li>reads the blocks the kept rows point to straight from the live capped files, writes them one after another
 * into new capped files of the same ring size, and points the rows at the new positions;</li>
 * <li>compacts the H2 copy.</li>
 * </ol>
 * A block Glowroot overwrote before it could be read is left out (its column is set to null), which Glowroot shows as
 * missing detail, the same as it would itself.
 */
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
    private static final int HEADER = 20;       // currIndex (long), sizeKb (int), lastResizeBaseIndex (long)
    private static final int BLOCK_HEADER = 8;  // the block's length (long), then its LZF bytes
    private static final long OVERWRITE_MARGIN = 1024 * 1024; // at most this much of a ring (see CappedReader.margin)
    private static final int DELETE_BATCH = 20_000;
    private static final long KEEP_HOURS = 876_000L; // ~100 years: the viewer must not expire the copied data

    private GlowrootData() {
    }

    /**
     * Free space the build needs in the temp folder: one copy of data.h2.db (the batched deletes keep it from
     * growing), plus room for the copied capped blocks and the ZIP.
     */
    static long spaceNeeded(long h2Bytes) {
        return h2Bytes + Math.max(64L * 1024 * 1024, h2Bytes / 10);
    }

    /** The recording's overall state for the lists: building (any node not done), saved (any saved), failed, or null. */
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

    /** What the cut kept, for the status file. */
    static final class Result {
        int traces;
        int aggregates;
        int gauges;
        int lostBlocks;
        long cappedBytes;
    }

    /**
     * Cuts recording recId (tagged traces; aggregates and gauges between from and to, epoch ms) out of srcData (a
     * Glowroot data folder) into outData. h2 opens H2 1.3 files: Glowroot's own shaded driver.
     */
    static Result slice(Driver h2, Path srcData, Path outData, long from, long to, String recId) throws IOException, SQLException {
        Files.createDirectories(outData);
        // H2 first, then the capped files: every capped id in the copy then exists in the capped files
        Files.copy(srcData.resolve("data.h2.db"), outData.resolve("data.h2.db"), StandardCopyOption.REPLACE_EXISTING);
        Result r = new Result();
        Properties login = new Properties();
        login.setProperty("user", "sa");
        login.setProperty("password", "");
        // a small cache: this runs inside Joget's JVM
        // DB_CLOSE_ON_EXIT=FALSE: H2's own shutdown hook would close the copy under a build still running; the build
        // then stops with the JVM and stays "building", so it is built again after the restart (GlowrootPackager.recover)
        String url = "jdbc:h2:" + outData.resolve("data").toAbsolutePath() + ";CACHE_SIZE=8192;DB_CLOSE_ON_EXIT=FALSE";
        try (Connection c = h2.connect(url, login)) {
            if (c == null) {
                throw new SQLException("The H2 driver does not accept " + url);
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                deleteInBatches(c, s, "DELETE FROM TRACE_ATTRIBUTE WHERE TRACE_ID NOT IN (SELECT TRACE_ID FROM TRACE_ATTRIBUTE"
                        + " WHERE NAME = " + quote(TAG) + " AND VALUE = " + quote(recId) + ")");
                deleteInBatches(c, s, "DELETE FROM TRACE WHERE ID NOT IN (SELECT TRACE_ID FROM TRACE_ATTRIBUTE)");
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
        Files.deleteIfExists(outData.resolve("data.trace.db")); // H2's own log of the compaction
        return r;
    }

    /**
     * Deletes in batches, each committed and checkpointed: H2 keeps every deleted row in its transaction log inside
     * the file until the commit, so one big DELETE of most of the copy makes it about 1.4 times larger first.
     */
    private static void deleteInBatches(Connection c, Statement s, String delete) throws SQLException {
        while (s.executeUpdate(delete + " LIMIT " + DELETE_BATCH) > 0) {
            c.commit();
            s.execute("CHECKPOINT"); // lets H2 reuse the log's pages
        }
        c.commit();
    }

    /** Rows whose rollup bucket ends outside (from - one bucket, to + two buckets): a bucket's time is its end. */
    static String outside(long from, long to, long bucketMs) {
        return " WHERE CAPTURE_TIME <= " + (from - bucketMs) + " OR CAPTURE_TIME >= " + (to + 2 * bucketMs);
    }

    /** Copies each kept row's blocks into out and points the row at the copies. Returns the number of rows. */
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

    // ---------- capped files ----------

    /**
     * Reads raw blocks from a capped file as Glowroot's CappedDatabaseOutputStream writes it, while Glowroot may still
     * be writing: a block counts only if Glowroot did not overwrite it before or while it was read.
     */
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

        /**
         * Bytes Glowroot may have written past the position its header shows: it writes a chunk, then the header. A
         * chunk is one LZF block (up to 64 KB); rings are at least 1 MB, so 1/16 of the ring, at most 1 MB.
         */
        long margin() {
            return Math.min(OVERWRITE_MARGIN, size / 16);
        }

        /** The smallest id not overwritten yet, as Glowroot computes it. */
        static long smallestKept(long base, long currIndex, long size) {
            return Math.max(base, currIndex - size);
        }

        /** The block's bytes (LZF, as stored), or null when it was overwritten or is not there. */
        byte[] block(long id) throws IOException {
            if (id < smallestKept(base, currIndex, size) || id + BLOCK_HEADER > currIndex) {
                return null;
            }
            f.seek(HEADER + (id - base) % size);
            long len = f.readLong(); // Glowroot never splits a block's length across the ring's end
            if (len < 0 || len > size || id + BLOCK_HEADER + len > currIndex) {
                return null;
            }
            byte[] b = new byte[(int) len];
            int done = 0;
            while (done < len) {
                long pos = (id + BLOCK_HEADER + done - base) % size;
                int n = (int) Math.min(len - done, size - pos); // the bytes may wrap to the ring's start
                f.seek(HEADER + pos);
                f.readFully(b, done, n);
                done += n;
            }
            // Glowroot may have written over it meanwhile: check its header again
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

    /** Writes a new capped file of the given ring size, blocks one after another from 0, so none wraps. */
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

        /** Writes a block and returns its new capped id. */
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
            f.writeLong(cursor); // currIndex
            f.writeInt(sizeKb);
            f.writeLong(0L);     // lastResizeBaseIndex
            f.close();
        }
    }

    // ---------- the package ----------

    /**
     * The agent's config.json for the viewer: no gauges (the viewer would add samples of its own JVM), no alerts and
     * no synthetic monitors (they would send mail and call the live URLs). The rest stays as on the server.
     */
    static String viewerConfig(String agentConfig) throws JSONException {
        JSONObject c = agentConfig == null || agentConfig.trim().isEmpty() ? new JSONObject() : new JSONObject(agentConfig);
        c.put("gauges", new JSONArray());
        c.put("alerts", new JSONArray());
        c.put("syntheticMonitors", new JSONArray());
        return c.toString(2);
    }

    /**
     * admin.json for the viewer: only localhost, and nothing expires. Users, LDAP, SMTP and the other settings of the
     * server's admin.json are left out on purpose (Glowroot's defaults: anonymous admin).
     */
    static String viewerAdmin(int traceCappedMb, int[] rollupCappedMb) throws JSONException {
        JSONArray expire = new JSONArray();
        JSONArray sizes = new JSONArray();
        for (int mb : rollupCappedMb) {
            expire.put(KEEP_HOURS);
            sizes.put(mb);
        }
        JSONObject storage = new JSONObject()
                .put("rollupExpirationHours", expire)
                .put("traceExpirationHours", KEEP_HOURS)
                .put("fullQueryTextExpirationHours", KEEP_HOURS)
                .put("rollupCappedDatabaseSizesMb", sizes)
                .put("traceCappedDatabaseSizeMb", traceCappedMb);
        return new JSONObject()
                .put("web", new JSONObject().put("port", 4000).put("bindAddress", "127.0.0.1"))
                .put("storage", storage)
                .toString(2);
    }

    static String readme(String recordingId, String node, String agentVersion, long from, long to) {
        String range = "from=" + from + "&to=" + to;
        String version = agentVersion == null ? null : agentVersion.split(",")[0].trim(); // "0.14.2, built ..." -> "0.14.2"
        return "BlackBox recording " + recordingId + ": Glowroot data of node " + node + "\n"
                + "Cut out of Glowroot " + (version == null ? "0.14" : version) + " in its own format: the traces of the\n"
                + "recorded requests, plus the aggregates and gauges of all traffic on that node during the recording.\n"
                + "\n"
                + "Recording: " + java.time.Instant.ofEpochMilli(from) + " to " + java.time.Instant.ofEpochMilli(to) + " (UTC)\n"
                + "\n"
                + "Open it in a standalone Glowroot of the same version:\n"
                + "1. Download glowroot-" + (version == null ? "0.14.2" : version) + "-dist.zip from\n"
                + "   https://github.com/glowroot/glowroot/releases and unzip it.\n"
                + "2. Copy data/, config.json and admin.json from this ZIP into that glowroot folder.\n"
                + "3. Start any JVM with the agent, e.g. with the Sleep.java in this ZIP:\n"
                + "     java -javaagent:glowroot/glowroot.jar Sleep.java\n"
                + "4. Open http://localhost:4000/transaction/average?transaction-type=Web&" + range + "\n"
                + "   Traces: http://localhost:4000/transaction/traces?transaction-type=Web&" + range + "\n"
                + "\n"
                + "admin.json makes the viewer listen on localhost only and never expire this data. Port 4000 must be\n"
                + "free. The data holds SQL with its parameter values and request details: share it accordingly.\n";
    }

    static final String SLEEP_JAVA = "public class Sleep {\n"
            + "    public static void main(String[] args) throws Exception {\n"
            + "        Thread.sleep(Long.MAX_VALUE); // an idle JVM for the Glowroot agent; stop it with Ctrl+C\n"
            + "    }\n"
            + "}\n";

    /** Zips the cut data folder plus config.json, admin.json, README.txt and Sleep.java into zip (written atomically). */
    static void zip(Path data, String config, String admin, String readme, Path zip) throws IOException {
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
            entry(z, "config.json", config);
            entry(z, "admin.json", admin);
            entry(z, "README.txt", readme);
            entry(z, "Sleep.java", SLEEP_JAVA);
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void entry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    // ---------- status and files ----------

    /** glowroot/{node}.json: {state: waiting|building|saved|failed, reason, bytes, from, to, updated, ...}. */
    static void writeStatus(Path outer, String node, JSONObject status) throws IOException {
        Path dir = Files.createDirectories(outer.resolve(DIR));
        Path tmp = dir.resolve(node + ".json." + Long.toHexString(System.nanoTime()) + ".part");
        Files.write(tmp, status.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, dir.resolve(node + ".json"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** True when a node's status says waiting or building (not finished yet). */
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

    /** Every node's status, sorted by node, or an empty list. */
    static JSONArray statuses(Path outer) throws IOException {
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
                    // half-written by an older version: skip
                }
            }
        }
        all.sort(java.util.Comparator.comparing((JSONObject s) -> s.optString("node")));
        for (JSONObject s : all) {
            list.put(s);
        }
        return list;
    }

    /** A node's package, or null when there is none (or the name is not a node name). */
    static Path zipFile(Path outer, String node) {
        if (outer == null || node == null || !NODE_ZIP.matcher(node + ".zip").matches()) {
            return null;
        }
        Path f = outer.resolve(DIR).resolve(node + ".zip");
        return Files.isRegularFile(f) ? f : null;
    }

    /** Moves the glowroot/ folder's files from a capture's pending folder into the recording (upload). */
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
            Files.delete(src); // empty now, unless a build is writing its .part file
        } catch (IOException e) {
            // not empty: the build moves its file when done
        }
    }
}
