package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** BB2: the server check that stops a capture whose browser stopped pinging. */
public class BrowserLostTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;
    private static final long LOST = LivenessKnobs.LOST_MS;

    private Path root;
    private ServerCaptureStore store;
    private Supplier<ServerCaptureStore> realStores;
    private Supplier<Path> realKnobs;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-lost-browser");
        store = new ServerCaptureStore(root);
        realStores = ServerCapture.stores;
        ServerCapture.stores = () -> store;
        realKnobs = LivenessKnobs.fileSupplier;
        LivenessKnobs.fileSupplier = () -> root.resolve(LivenessKnobs.FILE); // missing: the defaults
        staticMap("MISSES").clear(); // an earlier test's close() remembers the id for 10 s
    }

    @After
    public void tearDown() throws Exception {
        ServerCapture.close(ID);
        setRunning(false);
        ServerCapture.stores = realStores;
        LivenessKnobs.fileSupplier = realKnobs;
    }

    private void seenAt(long t) throws Exception {
        assertTrue(store.put(ID, "lastSeenAt", t));
    }

    @Test
    public void aCaptureNotSeenForLostMsIsStoppedAtItsLastPing() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long seen = System.currentTimeMillis() - LOST - 1;
        seenAt(seen);
        assertTrue(store.stopIfLost(ID, System.currentTimeMillis(), LOST));
        JSONObject meta = store.readMeta(ID);
        assertEquals(seen, meta.getLong("stoppedAt"));
        assertEquals("browser-lost", meta.getString("stopReason"));
        assertEquals("the ping now answers stopped", "browser-lost", store.ping(ID, "admin", -1, -1).getString("reason"));
    }

    @Test
    public void aRecentPingKeepsItOpen() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long now = System.currentTimeMillis();
        seenAt(now - LOST);
        assertFalse("exactly lostMs is not older than lostMs", store.stopIfLost(ID, now, LOST));
        assertFalse(store.readMeta(ID).has("stoppedAt"));
    }

    @Test
    public void aCaptureThatNeverGotAPingIsNeverBrowserLost() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        assertFalse(store.stopIfLost(ID, System.currentTimeMillis() + DAY, LOST));
        assertFalse(store.readMeta(ID).has("stoppedAt"));
    }

    @Test
    public void itStopsOnlyOnceAndKeepsAnEarlierStop() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        seenAt(1000L);
        assertTrue(store.stopIfLost(ID, 1000L + LOST + 1, LOST));
        assertFalse("second node", store.stopIfLost(ID, 1000L + 2 * LOST, LOST));
        assertEquals(1000L, store.readMeta(ID).getLong("stoppedAt"));

        String other = "1f8fad5b-d9cb-469f-a165-70867728950e";
        assertTrue(store.start(other, "admin", 1024, DAY));
        assertTrue(store.put(other, "lastSeenAt", 1000L));
        assertTrue(store.stop(other, "admin"));
        long stoppedAt = store.readMeta(other).getLong("stoppedAt");
        assertFalse(store.stopIfLost(other, stoppedAt + DAY, LOST));
        JSONObject meta = store.readMeta(other);
        assertEquals(stoppedAt, meta.getLong("stoppedAt"));
        assertFalse(meta.has("stopReason"));
    }

    @Test
    public void missingOrBadIdsAreIgnored() throws Exception {
        assertFalse(store.stopIfLost(ID, 0, LOST));
        assertFalse(store.stopIfLost("../x", 0, LOST));
    }

    @Test
    public void theCheckStopsALostCaptureAndClosesItOnThisNode() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long now = System.currentTimeMillis();
        seenAt(now - LOST - 1);
        open();
        ServerCapture.checkLost(now);
        assertEquals(now - LOST - 1, store.readMeta(ID).getLong("stoppedAt"));
        assertFalse("closed on this node", openIds().containsKey(ID));
        assertNull("not reopened by a later request", ServerCapture.find(ID));
    }

    @Test
    public void theCheckClosesACaptureAnotherNodeStopped() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        open();
        assertTrue(store.stop(ID, "admin")); // the stop request landed on another node
        ServerCapture.checkLost(System.currentTimeMillis());
        assertFalse(openIds().containsKey(ID));
        assertFalse(store.readMeta(ID).has("stopReason"));
    }

    @Test
    public void theCheckStopsALostCaptureNoNodeHasOpen() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long now = System.currentTimeMillis();
        seenAt(now - LOST - 1);
        ServerCapture.checkLost(now); // closed when idle, or the node restarted
        assertEquals("browser-lost", store.readMeta(ID).getString("stopReason"));
    }

    @Test
    public void theCheckLeavesALiveCaptureOpen() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long now = System.currentTimeMillis();
        seenAt(now - 1000);
        open();
        ServerCapture.checkLost(now);
        assertTrue(openIds().containsKey(ID));
        assertFalse(store.readMeta(ID).has("stoppedAt"));
    }

    @Test
    public void theCheckUsesLostMsFromTheOverrideFile() throws Exception {
        Files.write(root.resolve(LivenessKnobs.FILE), "{\"lostMs\": 5000}".getBytes("UTF-8"));
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long now = System.currentTimeMillis();
        seenAt(now - 6000);
        ServerCapture.checkLost(now);
        assertEquals("browser-lost", store.readMeta(ID).getString("stopReason"));
    }

    /** Opens the capture on this node as a tagged request would (find needs the plugin running). */
    private void open() throws Exception {
        setRunning(true);
        assertNotNull(ServerCapture.find(ID));
        assertTrue(openIds().containsKey(ID));
    }

    private static void setRunning(boolean on) throws Exception {
        Field f = ServerCapture.class.getDeclaredField("running");
        f.setAccessible(true);
        f.setBoolean(null, on);
    }

    private static Map<String, ?> openIds() throws Exception {
        return staticMap("OPEN");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> staticMap(String name) throws Exception {
        Field f = ServerCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, ?>) f.get(null);
    }
}
