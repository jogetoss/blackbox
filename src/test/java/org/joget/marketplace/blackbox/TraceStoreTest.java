package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class TraceStoreTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("bb-traces");
    }

    private static JSONObject entry(String key, long ts, String state, long updated) throws Exception {
        return new JSONObject().put("key", key).put("ts", ts).put("state", state).put("updated", updated).put("reason", "r");
    }

    @Test
    public void mergeKeepsTheLatestStateOfEachKeyAcrossNodes() throws Exception {
        TraceStore.append(dir, "node-a", entry("a1", 200, "pending", 10));
        TraceStore.append(dir, "node-a", entry("a1", 200, "saved", 20));
        TraceStore.append(dir, "node-b", entry("b1", 100, "pending", 15));
        JSONArray list = TraceStore.merge(dir, 30);
        assertEquals(2, list.length());
        assertEquals("sorted by request time", "b1", list.getJSONObject(0).getString("key"));
        assertEquals("saved", list.getJSONObject(1).getString("state"));
        assertEquals(1, TraceStore.pending(list));
    }

    @Test
    public void pendingWithNoNewsBecomesFailed() throws Exception {
        TraceStore.append(dir, "n", entry("k", 1, "pending", 0));
        JSONObject e = TraceStore.merge(dir, TraceStore.STALE_MS + 1).getJSONObject(0);
        assertEquals("failed", e.getString("state"));
        assertTrue(e.getString("reason").contains("No news"));
    }

    @Test
    public void unansweredRetrySaysWhichNode() throws Exception {
        TraceStore.append(dir, "node-a", entry("k", 1, "failed", 0).put("node", "node-b"));
        TraceStore.append(dir, "node-a", entry("k", 1, "pending", 5).put("node", "node-b")
                .put("reason", TraceCollector.RETRY_PREFIX + ": waiting for node-b"));
        JSONObject e = TraceStore.merge(dir, 5 + TraceStore.STALE_MS + 1).getJSONObject(0);
        assertEquals("failed", e.getString("state"));
        assertEquals("Node node-b did not answer the retry within 10 minutes", e.getString("reason"));
    }

    @Test
    public void missingTraceFileIsDropped() throws Exception {
        TraceStore.append(dir, "n", entry("k", 1, "saved", 1).put("file", "n-abcdef0123456789.html"));
        assertFalse(TraceStore.merge(dir, 2).getJSONObject(0).has("file"));
        assertEquals(0, TraceStore.merge(null, 2).length());
    }

    @Test
    public void traceFileNamesCannotLeaveTheFolder() {
        assertTrue(TraceStore.isTraceFile("j82121-01a13d6b7c0b273455ad424f1bbcee42.html"));
        assertFalse(TraceStore.isTraceFile("../x-0123456789abcdef.html"));
        assertFalse(TraceStore.isTraceFile("n-0123456789abcdef.html/../../meta.json"));
        assertFalse(TraceStore.isTraceFile(null));
    }

    @Test
    public void matchesSameMethodPathAndNearStart() throws Exception {
        JSONObject h = new JSONObject().put("headline", "/jw/web/json/x").put("startTime", 1000)
                .put("detail", new JSONObject().put("Request http method", "POST"));
        assertTrue(TraceStore.matches(h, "POST", "/jw/web/json/x?a=1", 1500));
        assertFalse(TraceStore.matches(h, "GET", "/jw/web/json/x", 1500));
        assertFalse(TraceStore.matches(h, "POST", "/jw/web/json/y", 1500));
        assertFalse(TraceStore.matches(h, "POST", "/jw/web/json/x", 4000));
    }

    @Test
    public void summaryAddsUpSqlAndSelfTimes() throws Exception {
        JSONObject header = new JSONObject()
                .put("durationNanos", 7_000_000_000L)
                .put("entryCount", 12).put("queryCount", 2)
                .put("mainThreadStats", new JSONObject().put("totalCpuNanos", 3_000_000_000L))
                .put("error", new JSONObject().put("message", "boom"))
                .put("mainThreadRootTimer", new JSONObject().put("name", "http request").put("totalNanos", 7_000_000_000L)
                        .put("childTimers", new JSONArray().put(new JSONObject().put("name", "jdbc query").put("totalNanos", 5_000_000_000L))));
        JSONObject queries = new JSONObject()
                .put("queries", new JSONArray()
                        .put(new JSONObject().put("type", "SQL").put("sharedQueryTextIndex", 0).put("totalDurationNanos", 1_000_000_000L)
                                .put("executionCount", 10).put("totalRows", 100))
                        .put(new JSONObject().put("type", "SQL").put("sharedQueryTextIndex", 1).put("totalDurationNanos", 4_000_000_000L)
                                .put("executionCount", 1).put("totalRows", 1)))
                .put("sharedQueryTexts", new JSONArray()
                        .put(new JSONObject().put("fullText", "select 1"))
                        .put(new JSONObject().put("truncatedText", "select a").put("truncatedEndText", "from b")));
        JSONObject s = TraceStore.summary(header, queries);
        assertEquals(7000, s.getLong("durationMs"));
        assertEquals(3000, s.getLong("cpuMs"));
        assertEquals("boom", s.getString("error"));
        assertEquals(11, s.getInt("sqlCount"));
        assertEquals(5000, s.getLong("sqlMs"));
        assertEquals("slowest first", "select a … from b", s.getJSONArray("slowestQueries").getJSONObject(0).getString("text"));
        assertEquals("self time", "jdbc query", s.getJSONArray("timers").getJSONObject(0).getString("name"));
        assertEquals(2000, s.getJSONArray("timers").getJSONObject(1).getLong("ms"));
    }

    @Test
    public void settingsDecideWhichRequestsHaveATrace() {
        GlowrootClient.Settings s = new GlowrootClient.Settings(2000, false);
        assertTrue(s.keeps(2500, 200, false));
        assertTrue(s.keeps(10, 500, false));
        assertTrue(s.keeps(10, 200, true));
        assertFalse(s.keeps(10, 404, false));
        assertTrue(new GlowrootClient.Settings(2000, true).keeps(10, 404, false));
    }
}
