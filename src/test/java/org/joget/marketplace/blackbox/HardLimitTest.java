package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** BB5: the recording's hard limit (maxRecordingMinutes), and the server's backup stop past maxUntil + 2 min. */
public class HardLimitTest {

    private static final String ID = "6a1f6c3e-2b7d-4c8e-9f10-1a2b3c4d5e6f";
    private static final long DAY = 24L * 3600 * 1000;
    private static final long MIN = 60_000L;
    private static final long GRACE = ServerCaptureStore.LIMIT_GRACE_MS;

    private Path root;
    private Path knobs;
    private ServerCaptureStore store;
    private Supplier<ServerCaptureStore> realStores;
    private Supplier<Path> realKnobs;
    private Supplier<java.util.List<Path>> realDirs;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-hard-limit");
        knobs = root.resolve(LivenessKnobs.FILE);
        store = new ServerCaptureStore(root.resolve("pending")); // root: one profile's blackbox folder
        realStores = ServerCapture.stores;
        ServerCapture.stores = () -> store;
        realDirs = ServerCapture.checkDirs;
        ServerCapture.checkDirs = () -> java.util.Collections.singletonList(root);
        realKnobs = LivenessKnobs.fileSupplier;
        LivenessKnobs.fileSupplier = () -> knobs; // missing: the defaults
        staticMap("MISSES").clear();
    }

    @After
    public void tearDown() throws Exception {
        ServerCapture.close(ID);
        ServerCapture.stores = realStores;
        ServerCapture.checkDirs = realDirs;
        LivenessKnobs.fileSupplier = realKnobs;
    }

    private void start(long maxRecordingMs) throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY, false, "", false, false, false, maxRecordingMs));
    }

    @Test
    public void theSettingIsMinutesFrom5To120Default30() {
        assertEquals(30 * MIN, BlackBoxPlugin.maxRecordingMs(null));
        assertEquals(30 * MIN, BlackBoxPlugin.maxRecordingMs(""));
        assertEquals(30 * MIN, BlackBoxPlugin.maxRecordingMs("abc"));
        assertEquals(45 * MIN, BlackBoxPlugin.maxRecordingMs(" 45 "));
        assertEquals("below 5: 5", 5 * MIN, BlackBoxPlugin.maxRecordingMs("1"));
        assertEquals("above 120 (GaugeSampler's 2 h): 120", 120 * MIN, BlackBoxPlugin.maxRecordingMs("500"));
        assertEquals(5 * MIN, BlackBoxPlugin.maxRecordingMs("0"));
    }

    @Test
    public void theTestOverrideMayLowerTheLimit() throws Exception {
        Files.write(knobs, "{\"minLimitMs\":60000}".getBytes(StandardCharsets.UTF_8));
        assertEquals(MIN, BlackBoxPlugin.maxRecordingMs("30"));
        Files.write(knobs, "{\"minLimitMs\":99000000}".getBytes(StandardCharsets.UTF_8));
        assertEquals("never raises it", 30 * MIN, BlackBoxPlugin.maxRecordingMs("30"));
    }

    @Test
    public void startResponseCarriesTheLimit() throws Exception {
        JSONObject started = BlackBoxPlugin.withLiveness(new JSONObject(), 30 * MIN);
        assertEquals(30 * MIN, started.getLong("maxRecordingMs"));
        assertEquals(30_000L, started.getLong("pingMs"));
    }

    @Test
    public void startResponseCarriesTheSizeLimit() throws Exception {
        JSONObject started = BlackBoxPlugin.withLiveness(new JSONObject(), 30 * MIN, 500);
        assertEquals(500L, started.getLong("maxSizeMb"));
        assertEquals(30 * MIN, started.getLong("maxRecordingMs"));
    }

    @Test
    public void captureOffAnswerCarriesTheLimit() throws Exception {
        // Server capture off or not allowed: the recording still gets the time limit (BB6: only the time limit)
        JSONObject off = BlackBoxPlugin.captureOff(403, "Server data capture is turned off.", 45 * MIN);
        assertEquals("403", off.getJSONObject("error").getString("code"));
        assertEquals("Server data capture is turned off.", off.getJSONObject("error").getString("message"));
        assertEquals(45 * MIN, off.getJSONObject("serverCapture").getLong("maxRecordingMs"));
        assertFalse("no size guard without server capture", off.getJSONObject("serverCapture").has("maxSizeMb"));
    }

    @Test
    public void startStoresMaxUntil() throws Exception {
        long before = System.currentTimeMillis();
        start(30 * MIN);
        JSONObject meta = store.readMeta(ID);
        assertEquals(meta.getLong("startedAt") + 30 * MIN, meta.getLong("maxUntil"));
        assertTrue(meta.getLong("startedAt") >= before);
    }

    @Test
    public void startWithoutALimitHasNoMaxUntil() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        assertFalse(store.readMeta(ID).has("maxUntil"));
        assertFalse(store.stopIfOverLimit(ID, System.currentTimeMillis() + DAY));
    }

    @Test
    public void pastMaxUntilPlusTwoMinutesTheCaptureStopsAtMaxUntil() throws Exception {
        start(5 * MIN);
        long maxUntil = store.readMeta(ID).getLong("maxUntil");
        assertFalse("within the grace", store.stopIfOverLimit(ID, maxUntil + GRACE));
        assertFalse(store.readMeta(ID).has("stoppedAt"));
        assertTrue(store.stopIfOverLimit(ID, maxUntil + GRACE + 1));
        JSONObject meta = store.readMeta(ID);
        assertEquals(maxUntil, meta.getLong("stoppedAt"));
        assertEquals("time-limit", meta.getString("stopReason"));
        assertFalse("only once", store.stopIfOverLimit(ID, maxUntil + GRACE + 2));
        assertEquals("the ping now answers stopped", "time-limit", store.ping(ID, "admin", -1, -1).getString("reason"));
    }

    @Test
    public void pingsDontKeepItOpen() throws Exception {
        start(5 * MIN);
        long maxUntil = store.readMeta(ID).getLong("maxUntil");
        long now = maxUntil + GRACE + 1;
        assertTrue(store.put(ID, "lastSeenAt", now)); // the browser still pings
        assertTrue(store.stopIfOverLimit(ID, now));
        assertEquals("time-limit", store.readMeta(ID).getString("stopReason"));
    }

    @Test
    public void anEarlierStopIsKept() throws Exception {
        start(5 * MIN);
        long maxUntil = store.readMeta(ID).getLong("maxUntil");
        assertTrue(store.stop(ID, "admin"));
        long stoppedAt = store.readMeta(ID).getLong("stoppedAt");
        assertFalse(store.stopIfOverLimit(ID, maxUntil + GRACE + 1));
        assertEquals(stoppedAt, store.readMeta(ID).getLong("stoppedAt"));
        assertFalse(store.readMeta(ID).has("stopReason"));
    }

    @Test
    public void theServerCheckStopsItOnEveryNodeRun() throws Exception {
        start(5 * MIN);
        long maxUntil = store.readMeta(ID).getLong("maxUntil");
        assertTrue(store.put(ID, "lastSeenAt", maxUntil + GRACE)); // pings or not
        ServerCapture.checkLost(maxUntil + GRACE / 2);
        assertFalse(store.readMeta(ID).has("stoppedAt"));
        ServerCapture.checkLost(maxUntil + GRACE + 1);
        assertEquals("time-limit", store.readMeta(ID).getString("stopReason"));
        assertEquals(maxUntil, store.readMeta(ID).getLong("stoppedAt"));
    }

    @Test
    public void aLostBrowserIsStillBrowserLost() throws Exception {
        start(5 * MIN);
        long maxUntil = store.readMeta(ID).getLong("maxUntil");
        long seen = maxUntil - 4 * MIN;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        ServerCapture.checkLost(maxUntil + GRACE + 1); // both apply: lost first, at its last ping
        assertEquals("browser-lost", store.readMeta(ID).getString("stopReason"));
        assertEquals(seen, store.readMeta(ID).getLong("stoppedAt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> staticMap(String name) throws Exception {
        Field f = ServerCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, ?>) f.get(null);
    }
}
