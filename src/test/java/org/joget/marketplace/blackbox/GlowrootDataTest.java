package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GlowrootDataTest {

    private static final String REC = "11111111-2222-3333-4444-555555555555";
    private static final long FROM = 1_800_000_000_000L; // the recording: 2 minutes
    private static final long TO = FROM + 120_000L;

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("glowroot-data-test");
    }

    @After
    public void tearDown() throws IOException {
        RecordingStore.deleteTree(dir);
    }

    // ---------- capped files ----------

    /** Writes blocks the way Glowroot's CappedDatabaseOutputStream does, into a tiny ring. */
    static final class GlowrootRing {
        final RandomAccessFile f;
        final long size;
        long currIndex;

        GlowrootRing(Path p, int sizeKb) throws IOException {
            f = new RandomAccessFile(p.toFile(), "rw");
            size = sizeKb * 1024L;
            f.writeLong(0);
            f.writeInt(sizeKb);
            f.writeLong(0);
        }

        long write(byte[] b) throws IOException {
            long pos = currIndex % size;
            if (size - pos < 8) {
                currIndex += size - pos; // no room for the block's length: it starts at the ring's start
            }
            long id = currIndex;
            f.seek(20 + currIndex % size);
            f.writeLong(b.length);
            currIndex += 8;
            long at = currIndex % size;
            int first = (int) Math.min(b.length, size - at);
            f.seek(20 + at);
            f.write(b, 0, first);
            if (first < b.length) {
                f.seek(20);
                f.write(b, first, b.length - first); // wraps past the ring's end
            }
            currIndex += b.length;
            f.seek(0);
            f.writeLong(currIndex);
            return id;
        }
    }

    @Test
    public void readsBlocksThatWrapAndSkipsOverwrittenOnes() throws IOException {
        int wrapped = 0;
        for (int seed = 1; seed <= 20; seed++) {
            wrapped += ringRoundTrip(seed);
        }
        assertTrue("some kept blocks wrap past the ring's end (" + wrapped + ")", wrapped > 0);
    }

    /** Fills a 4 KB ring until it wrapped several times, copies what survived, reads the copy back. Returns wraps. */
    private int ringRoundTrip(int seed) throws IOException {
        Path p = dir.resolve("ring-" + seed + ".capped.db");
        Random rnd = new Random(seed);
        List<long[]> ids = new ArrayList<>(); // id, block seed
        GlowrootRing ring = new GlowrootRing(p, 4);
        for (int i = 0; i < 60; i++) {
            ids.add(new long[]{ring.write(bytes(seed * 1000 + i, 100 + rnd.nextInt(300))), seed * 1000 + i});
        }
        ring.f.close();
        int kept = 0;
        int gone = 0;
        int wrapped = 0;
        Path copy = dir.resolve("copy-" + seed + ".capped.db");
        List<long[]> copied = new ArrayList<>(); // new id, block seed
        try (GlowrootData.CappedReader in = new GlowrootData.CappedReader(p);
                GlowrootData.CappedWriter out = new GlowrootData.CappedWriter(copy, in.sizeKb)) {
            assertTrue("the ring wrapped several times", in.currIndex > 3 * in.size);
            for (long[] e : ids) {
                byte[] b = in.block(e[0]);
                if (b == null) {
                    // overwritten, or so close to being overwritten that a write in progress may have reached it
                    assertTrue("only overwritten blocks are missing",
                            e[0] < GlowrootData.CappedReader.smallestKept(in.base, in.currIndex + in.margin(), in.size));
                    gone++;
                    continue;
                }
                assertArrayEquals(bytes((int) e[1], b.length), b);
                if ((e[0] + 8) % in.size + b.length > in.size) {
                    wrapped++;
                }
                copied.add(new long[]{out.write(b), e[1]});
                kept++;
            }
        }
        assertTrue(kept > 0 && gone > 0);
        try (GlowrootData.CappedReader again = new GlowrootData.CappedReader(copy)) {
            assertEquals("same ring size", 4, again.sizeKb);
            for (long[] e : copied) {
                assertArrayEquals(bytes((int) e[1], (int) again.block(e[0]).length), again.block(e[0]));
            }
        }
        return wrapped;
    }

    private static byte[] bytes(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ---------- the cut ----------

    @Test
    public void keepsOnlyTheRecordingAndRewritesCappedIds() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        GlowrootRing traceRing = new GlowrootRing(src.resolve(GlowrootData.TRACE_CAPPED), 64);
        GlowrootRing[] rollups = new GlowrootRing[4];
        for (int i = 0; i < 4; i++) {
            rollups[i] = new GlowrootRing(src.resolve(GlowrootData.rollupCapped(i)), 64);
        }
        rollups[0].write(bytes(99, 500)); // not the recording's: must not be copied
        long other = traceRing.write(bytes(1, 300));
        long entries = traceRing.write(bytes(2, 400));
        long profile = traceRing.write(bytes(3, 200));
        long queries = rollups[0].write(bytes(4, 250));
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + src.resolve("data") + ";MV_STORE=FALSE", "sa", "");
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE TRACE (ID VARCHAR, CAPTURE_TIME BIGINT, ENTRIES_CAPPED_ID BIGINT, QUERIES_CAPPED_ID BIGINT,"
                    + " SHARED_QUERY_TEXTS_CAPPED_ID BIGINT, MAIN_THREAD_PROFILE_CAPPED_ID BIGINT, AUX_THREAD_PROFILE_CAPPED_ID BIGINT)");
            s.execute("CREATE TABLE TRACE_ATTRIBUTE (TRACE_ID VARCHAR, NAME VARCHAR, VALUE VARCHAR, CAPTURE_TIME BIGINT)");
            for (int i = 0; i < 4; i++) {
                for (String k : new String[]{"TT", "TN"}) {
                    s.execute("CREATE TABLE AGGREGATE_" + k + "_ROLLUP_" + i + " (CAPTURE_TIME BIGINT, QUERIES_CAPPED_ID BIGINT,"
                            + " SERVICE_CALLS_CAPPED_ID BIGINT, MAIN_THREAD_PROFILE_CAPPED_ID BIGINT, AUX_THREAD_PROFILE_CAPPED_ID BIGINT)");
                }
            }
            for (int i = 0; i < 5; i++) {
                s.execute("CREATE TABLE GAUGE_VALUE_ROLLUP_" + i + " (GAUGE_ID BIGINT, CAPTURE_TIME BIGINT, VALUE DOUBLE, WEIGHT BIGINT)");
            }
            s.execute("CREATE TABLE INCIDENT (OPEN_TIME BIGINT)");
            s.execute("INSERT INTO TRACE VALUES ('mine', " + (FROM + 5000) + ", " + entries + ", NULL, NULL, " + profile + ", NULL)");
            s.execute("INSERT INTO TRACE VALUES ('other', " + (FROM + 6000) + ", " + other + ", NULL, NULL, NULL, NULL)");
            s.execute("INSERT INTO TRACE_ATTRIBUTE VALUES ('mine', 'BlackBox recording', '" + REC + "', 0)");
            s.execute("INSERT INTO TRACE_ATTRIBUTE VALUES ('other', 'BlackBox recording', 'someone-else', 0)");
            // minute buckets (time = end of the minute): kept from FROM - 1 min to TO + 2 min (exclusive)
            long[] minutes = {FROM - 120_000, FROM - 30_000, FROM + 60_000, TO + 60_000, TO + 120_000, TO + 600_000};
            for (long t : minutes) {
                s.execute("INSERT INTO AGGREGATE_TT_ROLLUP_0 VALUES (" + t + ", " + (t == FROM + 60_000 ? queries : -1) + ", NULL, NULL, NULL)");
            }
            for (long t = FROM - 60_000; t <= TO + 60_000; t += 5000) {
                s.execute("INSERT INTO GAUGE_VALUE_ROLLUP_0 VALUES (1, " + t + ", 1.0, 1)");
            }
            s.execute("INSERT INTO INCIDENT VALUES (1)");
        }
        traceRing.f.close();
        for (GlowrootRing r : rollups) {
            r.f.close();
        }

        Path out = dir.resolve("out");
        GlowrootData.Result r = GlowrootData.slice(new org.h2.Driver(), src, out, FROM, TO, REC);

        assertEquals("only the tagged trace", 1, r.traces);
        assertEquals("aggregate rows in the window (one -1 id is not a block)", 3, r.aggregates);
        assertEquals("the -1 capped ids of the other kept rows are not there", 2, r.lostBlocks);
        assertFalse("H2's compaction log is removed", Files.exists(out.resolve("data.trace.db")));
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + out.resolve("data"), "sa", "");
                Statement s = c.createStatement()) {
            assertEquals("mine", one(s, "SELECT GROUP_CONCAT(ID) FROM TRACE"));
            assertEquals("1", one(s, "SELECT COUNT(*) FROM TRACE_ATTRIBUTE"));
            assertEquals(String.valueOf(FROM - 30_000) + "," + (FROM + 60_000) + "," + (TO + 60_000),
                    one(s, "SELECT GROUP_CONCAT(CAPTURE_TIME ORDER BY CAPTURE_TIME) FROM AGGREGATE_TT_ROLLUP_0"));
            assertEquals("0", one(s, "SELECT COUNT(*) FROM INCIDENT"));
            assertEquals("gauges from FROM - 5 s (exclusive) to TO + 10 s (exclusive), 5 s apart",
                    String.valueOf((TO + 5000 - (FROM)) / 5000 + 1), one(s, "SELECT COUNT(*) FROM GAUGE_VALUE_ROLLUP_0"));
            long newEntries = Long.parseLong(one(s, "SELECT ENTRIES_CAPPED_ID FROM TRACE"));
            long newProfile = Long.parseLong(one(s, "SELECT MAIN_THREAD_PROFILE_CAPPED_ID FROM TRACE"));
            long newQueries = Long.parseLong(one(s, "SELECT QUERIES_CAPPED_ID FROM AGGREGATE_TT_ROLLUP_0 WHERE CAPTURE_TIME = " + (FROM + 60_000)));
            assertEquals("blocks start from 0 in the new file", 0, newEntries);
            try (GlowrootData.CappedReader t = new GlowrootData.CappedReader(out.resolve(GlowrootData.TRACE_CAPPED));
                    GlowrootData.CappedReader a = new GlowrootData.CappedReader(out.resolve(GlowrootData.rollupCapped(0)))) {
                assertArrayEquals(bytes(2, 400), t.block(newEntries));
                assertArrayEquals(bytes(3, 200), t.block(newProfile));
                assertArrayEquals(bytes(4, 250), a.block(newQueries));
                assertEquals("the other trace's block is not copied", 8 + 400 + 8 + 200, t.currIndex);
                assertEquals("the ring size stays", 64, t.sizeKb);
            }
        }
    }

    private static String one(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    // ---------- the package ----------

    @Test
    public void viewerConfigDropsGaugesAlertsAndMonitors() throws Exception {
        JSONObject c = new JSONObject(GlowrootData.viewerConfig("{\"transactions\":{\"slowThresholdMillis\":2000},"
                + "\"gauges\":[{\"mbeanObjectName\":\"java.lang:type=Memory\"}],\"alerts\":[{}],\"syntheticMonitors\":[{}]}"));
        assertEquals(0, c.getJSONArray("gauges").length());
        assertEquals(0, c.getJSONArray("alerts").length());
        assertEquals(0, c.getJSONArray("syntheticMonitors").length());
        assertEquals(2000, c.getJSONObject("transactions").getInt("slowThresholdMillis"));
        assertEquals("no config.json", 0, new JSONObject(GlowrootData.viewerConfig("")).getJSONArray("gauges").length());
    }

    @Test
    public void viewerAdminIsLocalAndNeverExpires() throws Exception {
        JSONObject a = new JSONObject(GlowrootData.viewerAdmin(500, new int[]{500, 400, 300, 200}));
        assertEquals("127.0.0.1", a.getJSONObject("web").getString("bindAddress"));
        JSONObject st = a.getJSONObject("storage");
        assertEquals(500, st.getInt("traceCappedDatabaseSizeMb"));
        assertEquals(300, st.getJSONArray("rollupCappedDatabaseSizesMb").getInt(2));
        assertTrue(st.getLong("traceExpirationHours") > 24L * 365 * 50);
        assertFalse("no users, LDAP or SMTP from the server", a.has("users") || a.has("ldap") || a.has("smtp"));
    }

    @Test
    public void overallStateAndSpaceNeeded() throws Exception {
        JSONArray s = new JSONArray();
        assertNull(GlowrootData.overall(s));
        s.put(new JSONObject().put("state", "failed"));
        assertEquals("failed", GlowrootData.overall(s));
        s.put(new JSONObject().put("state", "saved"));
        assertEquals("saved", GlowrootData.overall(s));
        s.put(new JSONObject().put("state", "waiting"));
        assertEquals("any node not done", "building", GlowrootData.overall(s));
        long mb = 1024 * 1024;
        assertEquals("small: one copy + 64 MB", 100 * mb + 64 * mb, GlowrootData.spaceNeeded(100 * mb));
        assertEquals("large: one copy + 10%", 2000 * mb + 200 * mb, GlowrootData.spaceNeeded(2000 * mb));
    }

    @Test
    public void buildsAMinuteAfterTheStop() {
        long stop = 1_800_000_010_000L; // 10 s into a minute
        assertEquals(1_800_000_060_000L + 30_000L, GlowrootPackager.readyAt(stop));
    }

    @Test
    public void zipsDataAndViewerFiles() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.write(data.resolve("data.h2.db"), new byte[]{1, 2, 3});
        Path zip = dir.resolve("rec").resolve(GlowrootData.DIR).resolve("node-a.zip");
        String readme = GlowrootData.readme(REC, "node-a", "0.14.2, built 2024-04-06 19:02:00 +0000", FROM, TO);
        assertTrue(readme.contains("Cut out of Glowroot 0.14.2 in its own format"));
        assertTrue(readme.contains("glowroot-0.14.2-dist.zip"));
        assertTrue("the recording's time range", readme.contains("from=" + FROM + "&to=" + TO));
        GlowrootData.zip(data, "{}", "{}", readme, zip);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (String name : Arrays.asList("data/data.h2.db", "config.json", "admin.json", "README.txt", "Sleep.java")) {
                assertNotNull(name, z.getEntry(name));
            }
        }
        assertEquals(zip, GlowrootData.zipFile(dir.resolve("rec"), "node-a"));
        assertNull("not a node name", GlowrootData.zipFile(dir.resolve("rec"), "../x"));
        assertFalse("no half-written file left", Files.exists(zip.resolveSibling("node-a.zip.part")));
    }

    @Test
    public void statusesAndMoveOnUpload() throws Exception {
        Path pending = dir.resolve("pending");
        GlowrootData.writeStatus(pending, "node-b", new JSONObject().put("node", "node-b").put("state", "saved"));
        GlowrootData.writeStatus(pending, "node-a", new JSONObject().put("node", "node-a").put("state", "building"));
        Files.write(pending.resolve(GlowrootData.DIR).resolve("node-b.zip"), new byte[]{1});
        Path rec = dir.resolve("rec");
        GlowrootData.moveAll(pending, rec);
        JSONArray list = GlowrootData.statuses(rec);
        assertEquals(2, list.length());
        assertEquals("sorted by node", "node-a", list.getJSONObject(0).getString("node"));
        assertNotNull(GlowrootData.zipFile(rec, "node-b"));
        assertEquals(0, GlowrootData.statuses(pending).length());
        assertFalse("the empty folder is removed", Files.exists(pending.resolve(GlowrootData.DIR)));
    }
}
