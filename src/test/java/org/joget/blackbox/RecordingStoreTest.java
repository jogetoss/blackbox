package org.joget.blackbox;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class RecordingStoreTest {

    private static final String SESSION = "{\"tool\":\"blackbox 1.3.0\",\"recording\":{\"durationMs\":1200,"
            + "\"tab\":{\"url\":\"http://x/jw\",\"title\":\"Joget DX\"}},\"summary\":{\"errors\":2},\"console\":[],\"network\":[]}";
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3};

    private Path root;
    private RecordingStore store;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-test");
        store = new RecordingStore(root);
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void createReadListDelete() throws Exception {
        JSONObject meta = store.create(in(SESSION), 1024 * 1024, "admin", "Admin User", "", "notes");
        String id = meta.getString("id");
        assertTrue(RecordingStore.isValidId(id));
        assertEquals("Joget DX", meta.getString("title")); // falls back to the tab title
        assertEquals("admin", meta.getString("owner"));
        assertEquals(0, meta.getLong("videoSize"));
        assertEquals(2, meta.getJSONObject("summary").getInt("errors"));
        assertEquals(SESSION, new String(Files.readAllBytes(store.file(id, RecordingStore.SESSION)), StandardCharsets.UTF_8));
        assertNull(store.file(id, RecordingStore.VIDEO));

        store.writeVideo(id, new ByteArrayInputStream(WEBM), 1024);
        assertEquals(WEBM.length, store.readMeta(id).getLong("videoSize"));
        assertEquals(1, store.list().size());

        assertTrue(store.delete(id));
        assertNull(store.readMeta(id));
        assertEquals(0, store.list().size());
    }

    @Test
    public void rejectsBadInput() throws Exception {
        expectInvalid(() -> store.create(in("not json"), 1024, "u", "u", null, null));
        expectInvalid(() -> store.create(in("{\"a\":1}"), 1024, "u", "u", null, null));
        expectInvalid(() -> store.create(in(SESSION), 10, "u", "u", null, null)); // too large
        expectInvalid(() -> store.create(in(""), 1024, "u", "u", null, null));

        String id = store.create(in(SESSION), 1024 * 1024, "u", "u", "t", null).getString("id");
        expectInvalid(() -> store.writeVideo(id, in("not a webm"), 1024));
        expectInvalid(() -> store.writeVideo(id, new ByteArrayInputStream(WEBM), 5)); // too large
        store.writeVideo(id, new ByteArrayInputStream(WEBM), 1024);
        expectInvalid(() -> store.writeVideo(id, new ByteArrayInputStream(WEBM), 1024)); // only once
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve(id))) {
            assertFalse("no .part files left", files.anyMatch(p -> p.toString().endsWith(".part")));
        }
    }

    @Test
    public void rejectsPathTricks() throws Exception {
        assertFalse(RecordingStore.isValidId("../etc"));
        assertFalse(RecordingStore.isValidId("20261014-010203-abcdef0/"));
        assertNull(store.readMeta(".."));
        assertNull(store.file("20261014-010203-abcdef01", "meta.json"));
        assertFalse(store.delete("../x"));
    }

    @Test
    public void parsesRanges() {
        assertArrayEquals(new long[]{0, 99}, BlackBoxPlugin.parseRange("bytes=0-", 100));
        assertArrayEquals(new long[]{10, 19}, BlackBoxPlugin.parseRange("bytes=10-19", 100));
        assertArrayEquals(new long[]{90, 99}, BlackBoxPlugin.parseRange("bytes=-10", 100));
        assertArrayEquals(new long[]{50, 99}, BlackBoxPlugin.parseRange("bytes=50-500", 100));
        assertNull(BlackBoxPlugin.parseRange("bytes=100-", 100));
        assertNull(BlackBoxPlugin.parseRange("bytes=5-2", 100));
        assertNull(BlackBoxPlugin.parseRange("bytes=0-1,5-6", 100));
        assertNull(BlackBoxPlugin.parseRange("bytes=-", 100));
    }

    interface Call {
        void run() throws Exception;
    }

    private static void expectInvalid(Call c) throws Exception {
        try {
            c.run();
            fail("expected InvalidUpload");
        } catch (RecordingStore.InvalidUpload expected) {
            // ok
        }
    }
}
