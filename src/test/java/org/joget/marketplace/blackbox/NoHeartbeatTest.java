package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** BB8: a capture that never got a ping (no heartbeat) is never browser-lost; only the maxUntil + 2 min backup applies. */
public class NoHeartbeatTest {

    private static final String ID = "7b2f6c3e-2b7d-4c8e-9f10-1a2b3c4d5e6f";
    private static final long DAY = 24L * 3600 * 1000;
    private static final long MIN = 60_000L;
    private static final long GRACE = ServerCaptureStore.LIMIT_GRACE_MS;

    private ServerCaptureStore store;
    private Supplier<ServerCaptureStore> realStores;
    private Supplier<Path> realKnobs;

    @Before
    public void setUp() throws Exception {
        Path root = Files.createTempDirectory("bb-no-heartbeat");
        store = new ServerCaptureStore(root);
        realStores = ServerCapture.stores;
        ServerCapture.stores = () -> store;
        realKnobs = LivenessKnobs.fileSupplier;
        LivenessKnobs.fileSupplier = () -> root.resolve(LivenessKnobs.FILE); // missing: the defaults
    }

    @After
    public void tearDown() {
        ServerCapture.close(ID);
        ServerCapture.stores = realStores;
        LivenessKnobs.fileSupplier = realKnobs;
    }

    @Test
    public void noPingsPastLostMsStaysOpenButPastMaxUntilPlusTwoMinutesStops() throws Exception {
        assertTrue(store.start(ID, "admin", 1024, DAY, false, "", false, false, false, 5 * MIN)); // as the start action does
        JSONObject meta = store.readMeta(ID);
        assertFalse("no ping yet", meta.has("lastSeenAt"));
        long maxUntil = meta.getLong("maxUntil");

        ServerCapture.checkLost(meta.getLong("startedAt") + LivenessKnobs.lostMs() + MIN); // well past lostMs
        assertFalse("still open", store.readMeta(ID).has("stoppedAt"));
        ServerCapture.checkLost(maxUntil + GRACE);
        assertFalse("still open within the grace", store.readMeta(ID).has("stoppedAt"));

        ServerCapture.checkLost(maxUntil + GRACE + 1);
        JSONObject stopped = store.readMeta(ID);
        assertEquals("time-limit", stopped.getString("stopReason"));
        assertEquals(maxUntil, stopped.getLong("stoppedAt"));
    }
}
