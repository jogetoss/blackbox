package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/** Disk and rate limits: ZIP import budget and entry cap, storage per user, open captures, starts per minute. */
public class LimitsTest {

    private static final String SESSION = "{\"recording\":{\"startedAt\":\"2026-01-01T00:00:00Z\"},\"console\":[],\"network\":[]}";
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    /** session.json, then a video padded to videoBytes: compresses to almost nothing. */
    private static Path zip(Path dir, int videoBytes, int extraEntries) throws Exception {
        Path zip = dir.resolve("in-" + videoBytes + "-" + extraEntries + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(RecordingStore.SESSION));
            z.write(SESSION.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry(RecordingStore.VIDEO));
            z.write(WEBM);
            z.write(new byte[videoBytes]);
            for (int i = 0; i < extraEntries; i++) {
                z.putNextEntry(new ZipEntry("x/" + i));
            }
        }
        return zip;
    }

    @Test
    public void importStopsAtTheTotalBudget() throws Exception {
        Path tmp = Files.createTempDirectory("bb-lim");
        RecordingStore store = new RecordingStore(Files.createTempDirectory("bb-store"));
        Path z = zip(tmp, 3 << 20, 0); // 3 MB unpacked, a few KB packed
        assertTrue("a zip bomb in miniature", Files.size(z) < 64 * 1024);
        try {
            RecordingZip.importZip(store, z, 10 << 20, false, "u", "U", 1 << 20, 0);
            fail("over the 1 MB budget");
        } catch (RecordingStore.InvalidUpload e) {
            assertTrue(e.getMessage(), e.getMessage().contains("import limit"));
        }
        assertEquals("nothing kept", 0, store.list().size());
        assertNotNull("0 = no limit", RecordingZip.importZip(store, z, 10 << 20, false, "u", "U", 0, 0));
    }

    @Test
    public void importStopsAtTheEntryCap() throws Exception {
        Path tmp = Files.createTempDirectory("bb-lim");
        RecordingStore store = new RecordingStore(Files.createTempDirectory("bb-store"));
        Path z = zip(tmp, 0, 20);
        try {
            RecordingZip.importZip(store, z, 1 << 20, false, "u", "U", 0, 10);
            fail("22 entries, cap 10");
        } catch (RecordingStore.InvalidUpload e) {
            assertTrue(e.getMessage(), e.getMessage().contains("the limit is 10"));
        }
        assertNotNull(RecordingZip.importZip(store, z, 1 << 20, false, "u", "U", 0, 22));
    }

    @Test
    public void limitSettingsParse() {
        assertEquals(10, BlackBoxPlugin.limitValue(null, 10));
        assertEquals(10, BlackBoxPlugin.limitValue(" ", 10));
        assertEquals(10, BlackBoxPlugin.limitValue("abc", 10));
        assertEquals(10, BlackBoxPlugin.limitValue("-5", 10));
        assertEquals("0 = no limit", 0, BlackBoxPlugin.limitValue("0", 10));
        assertEquals(50, BlackBoxPlugin.limitValue(" 50 ", 10));
    }

    @Test
    public void startsPerMinute() {
        BlackBoxPlugin.StartLimiter l = new BlackBoxPlugin.StartLimiter();
        long t = 1_000_000;
        for (int i = 0; i < 3; i++) {
            assertTrue(l.allow("cat", 3, t + i));
        }
        assertFalse(l.allow("cat", 3, t + 10));
        assertTrue("per user", l.allow("dog", 3, t + 10));
        assertTrue("a minute later", l.allow("cat", 3, t + 60_001));
    }

    @Test
    public void openCapturesPerOwner() throws Exception {
        ServerCaptureStore s = new ServerCaptureStore(Files.createTempDirectory("bb-cap"));
        String a = "11111111-1111-4111-8111-111111111111", b = "22222222-2222-4222-8222-222222222222",
                c = "33333333-3333-4333-8333-333333333333";
        assertTrue(s.start(a, "cat", 1 << 20, 60_000));
        assertTrue(s.start(b, "cat", 1 << 20, 60_000));
        assertTrue(s.start(c, "dog", 1 << 20, 60_000));
        assertEquals(2, s.openCount("cat", null));
        assertEquals("the capture being started again doesn't count", 1, s.openCount("cat", a));
        s.stop(a, "cat");
        assertEquals(1, s.openCount("cat", null));
    }

    @Test
    public void usedBytesCountsOnlyTheOwnersRecordings() throws Exception {
        RecordingStore store = new RecordingStore(Files.createTempDirectory("bb-store"));
        byte[] session = SESSION.getBytes(StandardCharsets.UTF_8);
        store.create(new ByteArrayInputStream(session), 1 << 20, "cat", "Cat", "t", "");
        store.create(new ByteArrayInputStream(session), 1 << 20, "dog", "Dog", "t", "");
        long cat = store.usedBytes("cat");
        assertTrue(cat >= session.length);
        assertEquals(0, store.usedBytes("nobody"));
    }
}
