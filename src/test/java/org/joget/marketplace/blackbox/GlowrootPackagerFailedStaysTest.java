package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GlowrootPackagerFailedStaysTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private Path dir;
    private Supplier<ServerCaptureStore> oldStores;
    private ScheduledExecutorService jobs;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("glowroot-packager-test");
        oldStores = ServerCapture.stores;
        ServerCaptureStore store = new ServerCaptureStore(dir.resolve("pending"));
        ServerCapture.stores = () -> store;
        jobs = Executors.newSingleThreadScheduledExecutor();
        field("jobs").set(null, jobs);
    }

    @After
    public void tearDown() throws Exception {
        GlowrootPackager.stop();
        ServerCapture.stores = oldStores;
        RecordingStore.deleteTree(dir);
    }

    @Test
    public void failedPackageStaysFailedOnReopen() throws Exception {
        ServerCaptureStore store = ServerCapture.store();
        assertTrue(store.start(ID, "admin", 1024, 24 * 3600_000L, false, "", false, true));
        String node = ServerCapture.nodeName();

        GlowrootPackager.watch(capture(store));
        assertEquals("waiting", state(store, node).getString("state"));

        watched().put(ID, new ProfileDir.Watch(System.currentTimeMillis() - 3 * 3600_000L - 1, null));
        tick();
        JSONObject failed = state(store, node);
        assertEquals("failed", failed.getString("state"));
        assertFalse(watched().containsKey(ID));

        GlowrootPackager.watch(capture(store));
        JSONObject after = state(store, node);
        assertEquals("the failed status is kept", "failed", after.getString("state"));
        assertEquals(failed.getString("reason"), after.getString("reason"));
        assertFalse("not watched again, so no build runs", watched().containsKey(ID));

        store.stop(ID, "admin");
        tick();
        assertEquals("failed", state(store, node).getString("state"));
        assertEquals(failed.getString("reason"), state(store, node).getString("reason"));
    }

    private static ServerCapture.Capture capture(ServerCaptureStore store) {
        return new ServerCapture.Capture(ID, store.nodeFile(ID, ServerCapture.nodeName()), store.readMeta(ID));
    }

    private static JSONObject state(ServerCaptureStore store, String node) throws Exception {
        for (int i = 0; i < GlowrootData.statuses(store.dir(ID)).length(); i++) {
            JSONObject s = GlowrootData.statuses(store.dir(ID)).getJSONObject(i);
            if (node.equals(s.optString("node"))) {
                return s;
            }
        }
        fail("no status for node " + node);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ProfileDir.Watch> watched() throws Exception {
        return (Map<String, ProfileDir.Watch>) field("WATCHED").get(null);
    }

    private static void tick() throws Exception {
        Method m = GlowrootPackager.class.getDeclaredMethod("tick");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static Field field(String name) throws Exception {
        Field f = GlowrootPackager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
