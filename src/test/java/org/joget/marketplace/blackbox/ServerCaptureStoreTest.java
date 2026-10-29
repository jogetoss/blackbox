package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ServerCaptureStoreTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private Path root;
    private ServerCaptureStore store;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-systemlog");
        store = new ServerCaptureStore(root);
    }

    private void lines(String node, String... json) throws Exception {
        Files.write(store.nodeFile(ID, node), (String.join("\n", json) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void attachMergesNodesInTimeOrder() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        assertTrue("same user may start again", store.start(ID, "admin", 1024, DAY));
        lines("node-a",
                "{\"ts\":300,\"kind\":\"log\",\"level\":\"ERROR\",\"message\":\"boom\"}",
                "{\"ts\":100,\"kind\":\"request\",\"method\":\"GET\",\"url\":\"/jw/web/x\",\"status\":200}");
        lines("node-b",
                "{\"ts\":200,\"kind\":\"log\",\"level\":\"INFO\",\"message\":\"hi\"}",
                "{\"ts\":210,\"kind\":\"log\",\"level\":\"ERROR\",\"message\":\"scheduler\",\"other\":true}",
                "{\"ts\":250,\"kind\":\"log\",\"le", // cut short by a crash
                "{\"kind\":\"truncated\",\"ts\":260}");
        assertTrue(store.stop(ID, "admin"));

        Path target = root.resolve("system-log.json");
        JSONObject summary = store.attach(ID, "admin", target);
        assertEquals("attached", summary.getString("status"));
        assertEquals(2, summary.getInt("entries"));
        assertEquals("other threads' lines counted apart", 1, summary.getInt("otherEntries"));
        assertEquals(1, summary.getInt("requests"));
        assertEquals(1, summary.getInt("errors"));
        assertTrue(summary.getBoolean("truncated"));

        JSONObject body = new JSONObject(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        JSONArray entries = body.getJSONArray("entries");
        assertEquals(4, entries.length());
        assertEquals(100, entries.getJSONObject(0).getLong("ts"));
        assertEquals(200, entries.getJSONObject(1).getLong("ts"));
        assertEquals(300, entries.getJSONObject(3).getLong("ts"));
        assertEquals("request entries say which node served them", "node-a", entries.getJSONObject(0).getString("node"));
        assertFalse("log lines don't", entries.getJSONObject(1).has("node"));
        assertTrue(body.has("stoppedAt"));
        assertFalse("node files deleted", Files.exists(store.nodeFile(ID, "node-a")));
        assertEquals("pending meta points at the recording", root.getFileName().toString(), store.readMeta(ID).getString("recording"));
        assertNull("attached once only", store.attach(ID, "admin", target));
    }

    @Test
    public void attachMovesTracesAndSweepDeletesTheForward() throws Exception {
        store.start(ID, "admin", 1024, DAY, true, "", true);
        GaugeStore.append(store.dir(ID), "node-a", new JSONObject().put("ts", 1).put("cpu", 0.2));
        TraceStore.append(store.dir(ID), "node-a", new JSONObject().put("key", "k1").put("ts", 5).put("state", "saved")
                .put("file", "node-a-0123456789abcdef.html").put("updated", 1));
        TraceStore.writeTrace(store.dir(ID), "node-a-0123456789abcdef.html", "<html></html>".getBytes(StandardCharsets.UTF_8));
        Path rec = Files.createDirectories(root.getParent().resolve(root.getFileName() + "-rec"));
        assertNotNull(store.attach(ID, "admin", rec.resolve(ServerCaptureStore.FILE)));

        assertTrue(Files.isRegularFile(rec.resolve("traces/node-a.jsonl")));
        assertTrue(Files.isRegularFile(rec.resolve("traces/node-a-0123456789abcdef.html")));
        assertFalse(Files.exists(store.dir(ID).resolve("traces")));
        assertTrue("gauges move too", Files.isRegularFile(rec.resolve("gauges/node-a.jsonl")));
        assertTrue(store.readMeta(ID).getBoolean("gauges"));
        assertEquals(1, TraceStore.merge(rec, 2).length());

        assertEquals(1, store.sweep(System.currentTimeMillis() + 2 * DAY));
        assertFalse("forward deleted with its folder", Files.exists(store.dir(ID)));
    }

    @Test
    public void appExportAtStopMovesWithTheAttach() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        lines("node-a", "{\"ts\":200,\"kind\":\"log\",\"level\":\"INFO\",\"message\":\"b\"}",
                "{\"ts\":100,\"kind\":\"request\",\"method\":\"GET\",\"url\":\"/jw/web/x\",\"status\":200}");
        assertTrue(store.stop(ID, "admin"));

        // What the export at stop reads: the lines so far, nothing moved
        Path lines = root.resolve("lines.json");
        store.writeLines(ID, lines);
        JSONArray entries = new JSONObject(new String(Files.readAllBytes(lines), StandardCharsets.UTF_8)).getJSONArray("entries");
        assertEquals(2, entries.length());
        assertEquals(100, entries.getJSONObject(0).getLong("ts"));
        assertTrue(Files.exists(store.nodeFile(ID, "node-a")));

        assertTrue(store.put(ID, "appExport", new JSONObject().put("state", "done")));
        assertEquals("done", store.readMeta(ID).getJSONObject("appExport").getString("state"));
        assertFalse("no such capture", store.put("1f8fad5b-d9cb-469f-a165-70867728950e", "appExport", "x"));

        Path app = Files.createDirectories(store.dir(ID).resolve(AppExport.DIR));
        Files.write(app.resolve("crm-3.jwa"), new byte[] {1});
        Files.write(app.resolve(AppExport.LIST), "{}".getBytes(StandardCharsets.UTF_8));
        Path rec = Files.createDirectories(root.resolve("rec"));
        assertNotNull(store.attach(ID, "admin", rec.resolve(ServerCaptureStore.FILE)));
        assertTrue(Files.isRegularFile(rec.resolve(AppExport.DIR).resolve("crm-3.jwa")));
        assertNotNull(AppExport.list(rec));
        assertFalse(Files.exists(app.resolve("crm-3.jwa")));
    }

    @Test
    public void attachLeavesAnUnusedStopExportBehind() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        Path app = Files.createDirectories(store.dir(ID).resolve(AppExport.DIR));
        Files.write(app.resolve("crm-3.jwa"), new byte[] {1}); // a stop export still running when the upload gave up
        Path rec = Files.createDirectories(root.resolve("rec"));
        assertNotNull(store.attach(ID, "admin", rec.resolve(ServerCaptureStore.FILE), false));
        assertFalse("the upload exports the apps itself", Files.exists(rec.resolve(AppExport.DIR)));
        assertTrue("left in the pending folder, deleted with it", Files.exists(app.resolve("crm-3.jwa")));
    }

    @Test
    public void onlyTheOwnerCanUseACapture() throws Exception {
        assertTrue(store.start(ID, "alice", 1024, DAY));
        assertFalse(store.start(ID, "bob", 1024, DAY));
        assertFalse(store.stop(ID, "bob"));
        assertNull(store.attach(ID, "bob", root.resolve("x.json")));
        assertTrue(Files.exists(root.resolve(ID)));
    }

    @Test
    public void concurrentPutsAndAttachKeepEveryChange() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        lines("node-a", "{\"ts\":100,\"kind\":\"log\",\"level\":\"INFO\",\"message\":\"a\"}");
        Path rec = Files.createDirectories(root.resolve("rec"));
        int n = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = "k" + i;
            done.add(pool.submit(() -> {
                go.await();
                return store.put(ID, key, true);
            }));
            if (i == n / 2) {
                done.add(pool.submit(() -> {
                    go.await();
                    return store.attach(ID, "admin", rec.resolve(ServerCaptureStore.FILE));
                }));
            }
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        JSONObject meta = store.readMeta(ID);
        assertEquals("rec", meta.optString("recording"));
        for (int i = 0; i < n; i++) {
            assertTrue("k" + i + " kept", meta.optBoolean("k" + i));
        }
    }

    @Test
    public void sweepDeletesExpiredCaptures() throws Exception {
        String other = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";
        store.start(ID, "u", 1024, DAY);
        store.start(other, "u", 1024, 10 * DAY);
        assertEquals(1, store.sweep(System.currentTimeMillis() + 2 * DAY));
        assertNull(store.readMeta(ID));
        assertNotNull(store.readMeta(other));
    }

    @Test
    public void rejectsBadIds() throws Exception {
        assertFalse(ServerCaptureStore.isValidId("../x"));
        assertFalse(ServerCaptureStore.isValidId("0F8FAD5B-D9CB-469F-A165-70867728950E")); // the extension sends lower case
        assertNull(store.readMeta("../../etc"));
    }

    @Test
    public void nodeNameIsJogetsNameElseTheHost() {
        assertEquals("n1", ServerCapture.nodeName("n1", "host-a"));
        assertEquals("host-a", ServerCapture.nodeName("", "host-a"));
        assertEquals("my-node", ServerCapture.nodeName("my node", null)); // safe for the file name
        assertEquals("node", ServerCapture.nodeName(null, null));
    }
}
