package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** BB2/BB5 with virtual hosts: the timer thread checks every profile's captures, each with its own test override. */
public class ProfilesCheckTest {

    private static final String A_ID = "2a8fad5b-d9cb-469f-a165-70867728950e";
    private static final String B_ID = "3b8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private Path shared;
    private Path a;
    private Path b;
    private Supplier<List<Path>> realDirs;

    @Before
    public void setUp() throws Exception {
        shared = Files.createTempDirectory("bb-profiles");
        a = shared.resolve("app_profiles").resolve("default").resolve("blackbox");
        b = shared.resolve("app_profiles").resolve("tenant2").resolve("blackbox");
        Files.createDirectories(a);
        Files.createDirectories(b);
        realDirs = ServerCapture.checkDirs;
        ServerCapture.checkDirs = () -> Arrays.asList(a, b);
    }

    @After
    public void tearDown() {
        ServerCapture.checkDirs = realDirs;
    }

    @Test
    public void everyProfilesCapturesAreChecked() throws Exception {
        long now = System.currentTimeMillis();
        ServerCaptureStore sa = new ServerCaptureStore(a.resolve("pending"));
        ServerCaptureStore sb = new ServerCaptureStore(b.resolve("pending"));
        assertTrue(sa.start(A_ID, "admin", 1024, DAY));
        assertTrue(sb.start(B_ID, "admin", 1024, DAY));
        assertTrue(sa.put(A_ID, "lastSeenAt", now - LivenessKnobs.LOST_MS - 1));
        assertTrue(sb.put(B_ID, "lastSeenAt", now - LivenessKnobs.LOST_MS - 1));

        ServerCapture.checkLost(now);

        assertEquals("browser-lost", sa.readMeta(A_ID).optString("stopReason"));
        assertEquals("the other profile too", "browser-lost", sb.readMeta(B_ID).optString("stopReason"));
    }

    @Test
    public void eachProfileUsesItsOwnTestOverride() throws Exception {
        long now = System.currentTimeMillis();
        Files.write(b.resolve(LivenessKnobs.FILE), "{\"lostMs\":5000,\"checkMs\":7000}".getBytes(StandardCharsets.UTF_8));
        ServerCaptureStore sa = new ServerCaptureStore(a.resolve("pending"));
        ServerCaptureStore sb = new ServerCaptureStore(b.resolve("pending"));
        assertTrue(sa.start(A_ID, "admin", 1024, DAY));
        assertTrue(sb.start(B_ID, "admin", 1024, DAY));
        assertTrue(sa.put(A_ID, "lastSeenAt", now - 10_000));
        assertTrue(sb.put(B_ID, "lastSeenAt", now - 10_000));

        ServerCapture.checkLost(now);

        assertFalse("default lostMs (3 min)", sa.readMeta(A_ID).has("stoppedAt"));
        assertEquals("its own lostMs (5 s)", "browser-lost", sb.readMeta(B_ID).optString("stopReason"));
        assertEquals("the shortest checkMs of all profiles", 7000L,
                ServerCapture.checkMs(Arrays.asList(a, b)));
    }

    @Test
    public void theFoldersAreEveryProfilesWithVirtualHostsElseTheBaseOne() throws Exception {
        Files.createFile(shared.resolve("app_profiles").resolve("not-a-folder"));
        List<Path> dirs = ServerCapture.blackboxDirs(true, "/ignored", shared.toString());
        assertEquals(2, dirs.size());
        assertTrue(dirs.contains(a));
        assertTrue(dirs.contains(b));
        assertEquals(Arrays.asList(a.getParent().resolve("blackbox")),
                ServerCapture.blackboxDirs(false, a.getParent().toString(), shared.toString()));
    }
}
