package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Virtual hosts: two profiles, each with its own blackbox folder. The background threads have no request (and so no
 * profile of their own); their work for a capture must run in the folder of the profile that opened it.
 */
public class VirtualHostsTest {

    private Path a;
    private Path b;
    private Supplier<ServerCaptureStore> realStores;
    private Supplier<List<Path>> realDirs;

    @Before
    public void setUp() throws Exception {
        a = Files.createTempDirectory("bb-profile-a").resolve("blackbox");
        b = Files.createTempDirectory("bb-profile-b").resolve("blackbox");
        Files.createDirectories(a);
        Files.createDirectories(b);
        realStores = ServerCapture.stores;
        realDirs = ServerCapture.checkDirs;
        ServerCapture.stores = () -> new ServerCaptureStore(ProfileDir.current().resolve("pending")); // the real one
        ServerCapture.checkDirs = () -> Arrays.asList(a, b);
        TraceCollector.stop();
    }

    @After
    public void tearDown() throws Exception {
        TraceCollector.stop();
        ServerCapture.stores = realStores;
        ServerCapture.checkDirs = realDirs;
        RecordingStore.deleteTree(a.getParent());
        RecordingStore.deleteTree(b.getParent());
    }

    private static String owed(Path profile, String id) throws Exception {
        ProfileDir.in(profile, () -> {
            ServerCaptureStore store = ServerCapture.store();
            assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, false, false, 0, true));
            store.put(id, TraceCollector.OTHERS_PENDING, true);
        });
        return id;
    }

    private static JSONArray traces(Path profile, String id) throws Exception {
        return TraceStore.merge(profile.resolve("pending").resolve(id), System.currentTimeMillis());
    }

    @Test
    public void glowrootsWholeJvmDataNeedsTheServersOptInWithVirtualHosts() {
        assertTrue("no virtual hosts: as before", BlackBoxPlugin.glowrootShared(false, null));
        assertFalse("virtual hosts: every profile's requests, so off", BlackBoxPlugin.glowrootShared(true, null));
        assertFalse(BlackBoxPlugin.glowrootShared(true, "yes"));
        assertTrue("-Dblackbox.sharedGlowroot=true", BlackBoxPlugin.glowrootShared(true, " TRUE "));
    }

    @Test
    public void onlyTheServersStartCommandTurnsTheOptInOn() {
        List<String> args = Arrays.asList("-Xmx2g", "-Dwflow.virtualhost=true", "-Dblackbox.sharedGlowroot=false",
                "-Dblackbox.sharedGlowroot=true");
        assertEquals("the last one wins, as in the JVM", "true", BlackBoxPlugin.startArg(args, BlackBoxPlugin.SHARED_GLOWROOT));
        assertTrue(BlackBoxPlugin.glowrootShared(true, BlackBoxPlugin.startArg(args, BlackBoxPlugin.SHARED_GLOWROOT)));
        assertNull(BlackBoxPlugin.startArg(Arrays.asList("-Dblackbox.sharedGlowrootX=true"), BlackBoxPlugin.SHARED_GLOWROOT));
        // Virtual hosts on, and a tenant's script sets the property at run time: still off (not in the start command)
        String vh = System.setProperty("wflow.virtualhost", "true");
        String shared = System.setProperty(BlackBoxPlugin.SHARED_GLOWROOT, "true");
        try {
            assertTrue(BlackBoxPlugin.virtualHosts());
            assertFalse("the live property is ignored", BlackBoxPlugin.glowrootShared.getAsBoolean());
        } finally {
            restore("wflow.virtualhost", vh);
            restore(BlackBoxPlugin.SHARED_GLOWROOT, shared);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    public void allServerLogLinesKeepOnlyTheCapturesOwnProfile() throws Exception {
        assertTrue(ServerCapture.sameProfile("tenant1", "tenant1"));
        assertFalse("another tenant's thread", ServerCapture.sameProfile("tenant1", "tenant2"));
        assertFalse("a thread with no profile yet", ServerCapture.sameProfile("tenant1", null));
        assertFalse("a capture started by plugin 8.2.2: no profile recorded", ServerCapture.sameProfile(null, null));
        String id = "00000000-0000-4000-8000-0000000000ee";
        ProfileDir.in(a, () -> {
            ServerCaptureStore store = ServerCapture.store();
            assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, false, true, 0, false));
            store.put(id, ServerCapture.PROFILE, "tenant1");
            assertEquals("tenant1", new ServerCapture.Capture(id, store.nodeFile(id, "n"), store.readMeta(id)).profile);
        });
    }

    @Test
    public void withoutTheOptInACaptureStartedBeforeGetsNoGlowrootDataNorOtherTraces() throws Exception {
        String id = "00000000-0000-4000-8000-0000000000cc";
        java.util.function.BooleanSupplier real = BlackBoxPlugin.glowrootShared;
        BlackBoxPlugin.glowrootShared = () -> false; // virtual hosts, no -Dblackbox.sharedGlowroot (e.g. removed)
        try {
            ProfileDir.in(b, () -> {
                ServerCaptureStore store = ServerCapture.store();
                assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, true, false, 0, true));
                java.lang.reflect.Method copy = TraceCollector.class.getDeclaredMethod("copyOthers", String.class,
                        org.json.JSONObject.class);
                copy.setAccessible(true);
                copy.invoke(null, id, store.readMeta(id));
                java.lang.reflect.Method build = GlowrootPackager.class.getDeclaredMethod("build", String.class, long.class, long.class);
                build.setAccessible(true);
                GlowrootPackager.start(); // build() checks it is not stopping
                build.invoke(null, id, 1L, 2L);
            });
            JSONArray notes = traces(b, id);
            assertEquals("none", notes.getJSONObject(0).getString("state"));
            assertTrue(notes.getJSONObject(0).getString("reason"), notes.getJSONObject(0).getString("reason").contains("sharedGlowroot"));
            JSONArray status = GlowrootData.statuses(b.resolve("pending").resolve(id));
            assertEquals("failed", status.getJSONObject(0).getString("state"));
            assertTrue(status.getJSONObject(0).getString("reason").contains("sharedGlowroot"));
        } finally {
            BlackBoxPlugin.glowrootShared = real;
            GlowrootPackager.stop();
        }
    }

    @Test
    public void aFailingCaptureIsDroppedAndTheJobGoesOn() throws Exception {
        assertFalse(ProfileDir.safely(getClass(), "test", a, () -> {
            throw new SecurityException("not within profile base directory");
        }));
        assertTrue(ProfileDir.safely(getClass(), "test", a, () -> { }));
        // A watched capture whose folder fails is no longer watched; the tick itself doesn't throw
        String id = "00000000-0000-4000-8000-0000000000dd";
        TraceCollector.start();
        ProfileDir.in(b, () -> {
            ServerCaptureStore store = ServerCapture.store();
            assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, false, false, 0, true));
            TraceCollector.watchOthers(new ServerCapture.Capture(id, store.nodeFile(id, ServerCapture.nodeName()), store.readMeta(id)));
        });
        java.util.function.Supplier<ServerCaptureStore> working = ServerCapture.stores;
        ServerCapture.stores = () -> {
            throw new SecurityException("not within profile base directory");
        };
        try {
            java.lang.reflect.Method tick = TraceCollector.class.getDeclaredMethod("othersTick");
            tick.setAccessible(true);
            tick.invoke(null);
        } finally {
            ServerCapture.stores = working;
        }
        assertEquals("dropped: recovering watches it again", 1,
                TraceCollector.recoverOthers(new ServerCaptureStore(b.resolve("pending")), b, 1).size());
    }

    @Test
    public void aGlowrootStatusWithNoNewsForTooLongReadsAsFailed() throws Exception {
        Path rec = Files.createDirectories(a.resolve("rec"));
        long now = 10 * 3600_000L;
        GlowrootData.writeStatus(rec, "n1", new org.json.JSONObject().put("node", "n1").put("state", "waiting").put("updated", 0));
        GlowrootData.writeStatus(rec, "n2", new org.json.JSONObject().put("node", "n2").put("state", "building")
                .put("updated", now - 60_000));
        JSONArray s = GlowrootData.statuses(rec, now);
        assertEquals("failed", s.getJSONObject(0).getString("state"));
        assertTrue(s.getJSONObject(0).getString("reason").contains("No news from n1"));
        assertEquals("a running build", "building", s.getJSONObject(1).getString("state"));
        assertEquals("failed", GlowrootData.statuses(rec, now + GlowrootData.BUILDING_STALE_MS).getJSONObject(1).getString("state"));
    }

    @Test
    public void inPutsThePreviousFolderBack() throws Exception {
        Path outside = ProfileDir.current();
        ProfileDir.in(a, () -> {
            assertEquals(a, ProfileDir.current());
            ProfileDir.in(b, () -> assertEquals(b, ProfileDir.current()));
            assertEquals(a, ProfileDir.current());
            ProfileDir.in(null, () -> assertEquals("null keeps the folder", a, ProfileDir.current()));
        });
        assertEquals(outside, ProfileDir.current());
    }

    @Test
    public void aRestartWatchesTheCapturesOfEveryProfile() throws Exception {
        String inA = owed(a, "00000000-0000-4000-8000-00000000000a");
        String inB = owed(b, "00000000-0000-4000-8000-00000000000b");
        TraceCollector.start(); // recovers after 1 s, from every profile's folder
        long until = System.currentTimeMillis() + 10_000;
        while ((traces(a, inA).length() == 0 || traces(b, inB).length() == 0) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertEquals("pending", traces(a, inA).getJSONObject(0).getString("state"));
        assertEquals("pending", traces(b, inB).getJSONObject(0).getString("state"));
        assertFalse("nothing written in the other profile", Files.exists(b.resolve("pending").resolve(inA)));
    }

    @Test
    public void theTickWorksInTheFolderOfTheProfileThatOpenedTheCapture() throws Exception {
        String id = "00000000-0000-4000-8000-0000000000bb";
        TraceCollector.start();
        ProfileDir.in(b, () -> {
            ServerCaptureStore store = ServerCapture.store();
            assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, false, false, 0, true));
            // a request of profile B opens it on this node
            TraceCollector.watchOthers(new ServerCapture.Capture(id, store.nodeFile(id, ServerCapture.nodeName()), store.readMeta(id)));
        });
        // The tick runs on a thread with another profile's folder: it must still find the capture in B, not drop it
        ProfileDir.in(a, () -> {
            java.lang.reflect.Method tick = TraceCollector.class.getDeclaredMethod("othersTick");
            tick.setAccessible(true);
            tick.invoke(null);
        });
        assertEquals("still watched: recovering adds nothing", 0,
                TraceCollector.recoverOthers(new ServerCaptureStore(b.resolve("pending")), b, 1).size());
        assertEquals("pending", traces(b, id).getJSONObject(0).getString("state"));
        assertFalse("nothing written in profile A", Files.exists(a.resolve("pending").resolve(id)));
    }
}
