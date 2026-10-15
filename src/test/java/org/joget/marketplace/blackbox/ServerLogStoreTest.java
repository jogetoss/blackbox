package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ServerLogStoreTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private Path root;
    private ServerLogStore store;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-serverlog");
        store = new ServerLogStore(root);
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
                "{\"ts\":250,\"kind\":\"log\",\"le", // cut short by a crash
                "{\"kind\":\"truncated\",\"ts\":260}");
        assertTrue(store.stop(ID, "admin"));

        Path target = root.resolve("server-log.json");
        JSONObject summary = store.attach(ID, "admin", target);
        assertEquals("attached", summary.getString("status"));
        assertEquals(2, summary.getInt("entries"));
        assertEquals(1, summary.getInt("requests"));
        assertEquals(1, summary.getInt("errors"));
        assertTrue(summary.getBoolean("truncated"));

        JSONObject body = new JSONObject(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        JSONArray entries = body.getJSONArray("entries");
        assertEquals(3, entries.length());
        assertEquals(100, entries.getJSONObject(0).getLong("ts"));
        assertEquals(200, entries.getJSONObject(1).getLong("ts"));
        assertEquals(300, entries.getJSONObject(2).getLong("ts"));
        assertTrue(body.has("stoppedAt"));
        assertFalse("pending folder deleted", Files.exists(root.resolve(ID)));
        assertNull("attached once only", store.attach(ID, "admin", target));
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
        assertFalse(ServerLogStore.isValidId("../x"));
        assertFalse(ServerLogStore.isValidId("0F8FAD5B-D9CB-469F-A165-70867728950E")); // the extension sends lower case
        assertNull(store.readMeta("../../etc"));
    }
}
