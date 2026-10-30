package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** BB1: the recorder's heartbeat (recording-ping) and the timing in the start response. */
public class RecordingPingTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private Path root;
    private Path knobs;
    private ServerCaptureStore store;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-ping");
        store = new ServerCaptureStore(root);
        knobs = root.resolve(LivenessKnobs.FILE);
        LivenessKnobs.fileSupplier = () -> knobs;
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteIfExists(knobs);
    }

    @Test
    public void pingWritesLastSeenAtAndAnswersOn() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        long before = System.currentTimeMillis();
        JSONObject state = store.ping(ID, "admin", 12345L, 678L);
        assertEquals("on", state.getString("state"));
        JSONObject meta = store.readMeta(ID);
        assertTrue(meta.getLong("lastSeenAt") >= before);
        assertEquals(12345L, meta.getLong("lastSavedAt"));
        assertEquals(678L, meta.getLong("videoBytes"));
        assertEquals("the rest of the meta is kept", "admin", meta.getString("owner"));
    }

    @Test
    public void pingWithoutTheOptionalValuesOnlyWritesLastSeenAt() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        assertEquals("on", store.ping(ID, "admin", -1, -1).getString("state"));
        JSONObject meta = store.readMeta(ID);
        assertTrue(meta.has("lastSeenAt"));
        assertFalse(meta.has("lastSavedAt"));
        assertFalse(meta.has("videoBytes"));
    }

    @Test
    public void onlyTheOwnerCanPing() throws Exception {
        assertTrue(store.start(ID, "alice", 1024, DAY));
        assertNull(store.ping(ID, "bob", 1, 1));
        assertFalse(store.readMeta(ID).has("lastSeenAt"));
        assertNull("missing capture", store.ping("1f8fad5b-d9cb-469f-a165-70867728950e", "alice", 1, 1));
        assertNull("bad id", store.ping("../x", "alice", 1, 1));
    }

    @Test
    public void aStoppedCaptureAnswersStoppedWithItsReason() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        assertTrue(store.stop(ID, "admin"));
        JSONObject state = store.ping(ID, "admin", 1, 1);
        assertEquals("stopped", state.getString("state"));
        assertEquals("stopped", state.getString("reason"));
        assertFalse("a stopped capture is not marked seen", store.readMeta(ID).has("lastSeenAt"));

        assertTrue(store.put(ID, "stopReason", "browser-lost"));
        assertEquals("browser-lost", store.ping(ID, "admin", 1, 1).getString("reason"));
    }

    @Test
    public void anAttachedCaptureAnswersStopped() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY));
        Path rec = Files.createDirectories(root.resolve("rec"));
        assertNotNull(store.attach(ID, "admin", rec.resolve(ServerCaptureStore.FILE)));
        assertEquals("stopped", store.ping(ID, "admin", 1, 1).getString("state"));
    }

    @Test
    public void startResponseCarriesTheTiming() throws Exception {
        JSONObject started = BlackBoxPlugin.withLiveness(new JSONObject());
        assertEquals(30_000L, started.getLong("pingMs"));
        assertEquals(180_000L, started.getLong("lostMs"));
        Files.write(knobs, "{\"pingMs\":2000,\"lostMs\":6000}".getBytes(StandardCharsets.UTF_8));
        started = BlackBoxPlugin.withLiveness(new JSONObject());
        assertEquals("the test override is used", 2000L, started.getLong("pingMs"));
        assertEquals(6000L, started.getLong("lostMs"));
    }
}
