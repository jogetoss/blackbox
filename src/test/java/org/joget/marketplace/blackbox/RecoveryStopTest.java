package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** BB3: the stop the extension sends after recovering a crashed recording, with the client's stop time (lastSavedAt). */
public class RecoveryStopTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;
    private static final long LOST = 3 * 60 * 1000L;

    private ServerCaptureStore store;
    private long startedAt;

    @Before
    public void setUp() throws Exception {
        Path root = Files.createTempDirectory("bb-recovery-stop");
        store = new ServerCaptureStore(root);
        assertTrue(store.start(ID, "admin", 1024, DAY));
        startedAt = System.currentTimeMillis() - 60 * 60 * 1000L; // an hour ago
        assertTrue(store.put(ID, "startedAt", startedAt));
    }

    private JSONObject meta() {
        return store.readMeta(ID);
    }

    @Test
    public void aClientStopTimeWithinTheBoundsIsTaken() throws Exception {
        long seen = startedAt + 20 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        long saved = seen + 10 * 1000L; // the last save can come after the last ping
        assertTrue(store.stop(ID, "admin", saved, LOST));
        assertEquals(saved, meta().getLong("stoppedAt"));
    }

    @Test
    public void theUpperBoundIsInclusive() throws Exception {
        long seen = startedAt + 20 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        assertTrue(store.stop(ID, "admin", seen + LOST, LOST));
        assertEquals(seen + LOST, meta().getLong("stoppedAt"));
    }

    @Test
    public void theLowerBoundIsInclusive() throws Exception {
        assertTrue(store.put(ID, "lastSeenAt", startedAt + 1000));
        assertTrue(store.stop(ID, "admin", startedAt, LOST));
        assertEquals(startedAt, meta().getLong("stoppedAt"));
    }

    @Test
    public void aClientStopTimeAfterLastSeenPlusLostMsGivesLastSeenAt() throws Exception {
        long seen = startedAt + 20 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        assertTrue(store.stop(ID, "admin", seen + LOST + 1, LOST));
        assertEquals(seen, meta().getLong("stoppedAt"));
    }

    @Test
    public void aClientStopTimeBeforeTheStartGivesLastSeenAt() throws Exception {
        long seen = startedAt + 20 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        assertTrue(store.stop(ID, "admin", startedAt - 1, LOST));
        assertEquals(seen, meta().getLong("stoppedAt"));
    }

    @Test
    public void withoutLastSeenAtAnOutOfBoundsTimeGivesNow() throws Exception {
        long before = System.currentTimeMillis();
        assertTrue(store.stop(ID, "admin", startedAt - 1, LOST));
        long stopped = meta().getLong("stoppedAt");
        assertTrue(stopped >= before && stopped <= System.currentTimeMillis());
    }

    @Test
    public void withoutLastSeenAtATimeBetweenStartAndNowIsTaken() throws Exception {
        long saved = startedAt + 15 * 1000L; // crashed before the first ping
        assertTrue(store.stop(ID, "admin", saved, LOST));
        assertEquals(saved, meta().getLong("stoppedAt"));
    }

    @Test
    public void withoutLastSeenAtATimeInTheFutureGivesNow() throws Exception {
        long before = System.currentTimeMillis();
        assertTrue(store.stop(ID, "admin", before + DAY, LOST));
        long stopped = meta().getLong("stoppedAt");
        assertTrue(stopped >= before && stopped <= System.currentTimeMillis());
    }

    @Test
    public void aPlainStopStillUsesNow() throws Exception {
        assertTrue(store.put(ID, "lastSeenAt", startedAt + 1000));
        long before = System.currentTimeMillis();
        assertTrue(store.stop(ID, "admin"));
        long stopped = meta().getLong("stoppedAt");
        assertTrue(stopped >= before && stopped <= System.currentTimeMillis());
    }

    @Test
    public void aCaptureStoppedAsBrowserLostKeepsItsStopTime() throws Exception {
        long seen = startedAt + 20 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", seen));
        assertTrue(store.stopIfLost(ID, System.currentTimeMillis(), LOST));
        assertTrue("the owner's stop is still answered", store.stop(ID, "admin", seen + 5000, LOST));
        assertEquals(seen, meta().getLong("stoppedAt"));
        assertEquals("browser-lost", meta().getString("stopReason"));
        assertTrue(store.stop(ID, "admin"));
        assertEquals("a plain stop keeps it too", seen, meta().getLong("stoppedAt"));
    }

    @Test
    public void onlyTheOwnerCanStop() throws Exception {
        assertFalse(store.stop(ID, "bob", startedAt + 1000, LOST));
        assertFalse(meta().has("stoppedAt"));
        assertFalse("bad id", store.stop("../x", "admin", startedAt + 1000, LOST));
    }
}
