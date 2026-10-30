package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** BB4: an upload that attaches a capture with no stoppedAt uses lastSeenAt, not the upload time. */
public class AttachStopTimeTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private ServerCaptureStore store;
    private Path target;

    @Before
    public void setUp() throws Exception {
        Path root = Files.createTempDirectory("bb-attach-stop");
        store = new ServerCaptureStore(root);
        assertTrue(store.start(ID, "admin", 1024, DAY));
        Path rec = Files.createDirectory(root.resolve("rec-1"));
        target = rec.resolve("system-log.json");
    }

    @Test
    public void aCapturePingedThenUploadedLaterGetsLastSeenAt() throws Exception {
        long seen = System.currentTimeMillis() - 90 * 60 * 1000L; // pinged 90 min ago, uploaded now
        assertTrue(store.put(ID, "lastSeenAt", seen));
        assertNotNull(store.attach(ID, "admin", target));
        assertEquals(seen, store.readMeta(ID).getLong("stoppedAt"));
        // the recording's own system-log.json carries the same time
        assertEquals(seen, new JSONObject(new String(Files.readAllBytes(target), "UTF-8")).getLong("stoppedAt"));
    }

    @Test
    public void aCaptureNeverPingedGetsNow() throws Exception {
        long before = System.currentTimeMillis();
        assertNotNull(store.attach(ID, "admin", target));
        long stopped = store.readMeta(ID).getLong("stoppedAt");
        assertTrue(stopped >= before && stopped <= System.currentTimeMillis());
    }

    @Test
    public void anExistingStoppedAtIsKept() throws Exception {
        long stop = System.currentTimeMillis() - 5 * 60 * 1000L;
        assertTrue(store.put(ID, "lastSeenAt", stop - 1000));
        assertTrue(store.put(ID, "stoppedAt", stop));
        assertNotNull(store.attach(ID, "admin", target));
        assertEquals(stop, store.readMeta(ID).getLong("stoppedAt"));
    }
}
