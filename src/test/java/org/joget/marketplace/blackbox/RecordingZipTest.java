package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class RecordingZipTest {

    private static final String SESSION = "{\"recording\":{\"tab\":{\"title\":\"Tab\"},\"serverLog\":{\"status\":\"on\","
            + "\"id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\"}},\"console\":[],\"network\":[]}";
    private static final String SERVER_LOG = "{\"entries\":[{\"ts\":1,\"kind\":\"log\",\"level\":\"ERROR\"},"
            + "{\"ts\":2,\"kind\":\"request\"}],\"truncated\":false}";
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 9, 9};

    private RecordingStore from;
    private RecordingStore to;
    private Path tmp;

    @Before
    public void setUp() throws Exception {
        from = new RecordingStore(Files.createTempDirectory("bb-from"));
        to = new RecordingStore(Files.createTempDirectory("bb-to"));
        tmp = Files.createTempDirectory("bb-zip");
    }

    private Path zipOf(String id, boolean withLog) throws Exception {
        Path zip = tmp.resolve(id + ".zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            RecordingZip.write(out, from.readMeta(id), from.file(id, RecordingStore.SESSION),
                    withLog ? from.file(id, RecordingStore.SERVER_LOG) : null, from.file(id, RecordingStore.VIDEO));
        }
        return zip;
    }

    @Test
    public void tracesTravelWithTheServerLog() throws Exception {
        String id = from.create(new ByteArrayInputStream(SESSION.getBytes(StandardCharsets.UTF_8)), 1 << 20,
                "alice", "Alice", "T", "").getString("id");
        Files.write(from.path(id, RecordingStore.SERVER_LOG), SERVER_LOG.getBytes(StandardCharsets.UTF_8));
        Path dir = from.dir(id);
        String file = "node-a-0123456789abcdef.html";
        TraceStore.append(dir, "node-a", new JSONObject().put("key", "k1").put("ts", 3).put("state", "saved")
                .put("file", file).put("updated", 1));
        TraceStore.writeTrace(dir, file, "<html>trace</html>".getBytes(StandardCharsets.UTF_8));
        Path zip = tmp.resolve("t.zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            RecordingZip.write(out, from.readMeta(id), from.file(id, RecordingStore.SESSION),
                    from.file(id, RecordingStore.SERVER_LOG), null, dir);
        }

        String newId = RecordingZip.importZip(to, zip, 1 << 20, true, "bob", "Bob").getString("id");
        org.json.JSONArray traces = TraceStore.merge(to.dir(newId), 2);
        assertEquals(1, traces.length());
        assertEquals(file, traces.getJSONObject(0).getString("file"));
        assertEquals("<html>trace</html>", new String(Files.readAllBytes(to.traceFile(newId, file)), StandardCharsets.UTF_8));

        String noLogId = RecordingZip.importZip(to, zip, 1 << 20, false, "carol", "Carol").getString("id");
        assertEquals("no server log access, no traces", 0, TraceStore.merge(to.dir(noLogId), 2).length());
        assertTrue("delete removes traces/ too", to.delete(newId));
        assertNull(to.dir(newId));
    }

    @Test
    public void roundTrip() throws Exception {
        String id = from.create(new ByteArrayInputStream(SESSION.getBytes(StandardCharsets.UTF_8)), 1 << 20,
                "alice", "Alice", "My title", "My notes").getString("id");
        from.writeVideo(id, new ByteArrayInputStream(WEBM), 1024);
        Files.write(from.path(id, RecordingStore.SERVER_LOG), SERVER_LOG.getBytes(StandardCharsets.UTF_8));

        JSONObject meta = RecordingZip.importZip(to, zipOf(id, true), 1 << 20, true, "bob", "Bob");
        String newId = meta.getString("id");
        assertEquals("bob", meta.getString("owner"));
        assertEquals("My title", meta.getString("title"));
        assertEquals("My notes", meta.getString("notes"));
        assertEquals("alice", meta.getJSONObject("importedFrom").getString("owner"));
        assertEquals(1, meta.getJSONObject("serverLog").getInt("entries"));
        assertEquals(1, meta.getJSONObject("serverLog").getInt("errors"));
        assertFalse("capture id of the other server is not kept", meta.has("serverLogId"));
        assertEquals(WEBM.length, to.readMeta(newId).getLong("videoSize"));
        assertArrayEquals(WEBM, Files.readAllBytes(to.file(newId, RecordingStore.VIDEO)));
        assertEquals(SESSION, new String(Files.readAllBytes(to.file(newId, RecordingStore.SESSION)), StandardCharsets.UTF_8));

        // Not allowed to see server logs: the recording comes in without it
        JSONObject noLog = RecordingZip.importZip(to, zipOf(id, true), 1 << 20, false, "carol", "Carol");
        assertFalse(noLog.has("serverLog"));
        assertNull(to.file(noLog.getString("id"), RecordingStore.SERVER_LOG));
    }

    @Test
    public void badZipLeavesNothing() throws Exception {
        Path notZip = Files.write(tmp.resolve("x.zip"), "hello".getBytes(StandardCharsets.UTF_8));
        expectInvalid(notZip);

        Path noSession = tmp.resolve("empty.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(noSession))) {
            z.putNextEntry(new ZipEntry("other.txt"));
            z.write(1);
        }
        expectInvalid(noSession);

        Path badVideo = tmp.resolve("badvideo.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(badVideo))) {
            z.putNextEntry(new ZipEntry(RecordingStore.SESSION));
            z.write(SESSION.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry(RecordingStore.VIDEO));
            z.write("not a webm".getBytes(StandardCharsets.UTF_8));
        }
        expectInvalid(badVideo);
        assertEquals("the half-made recording is removed", 0, to.list().size());
    }

    private void expectInvalid(Path zip) throws Exception {
        try {
            RecordingZip.importZip(to, zip, 1 << 20, true, "u", "U");
            fail("expected InvalidUpload for " + zip.getFileName());
        } catch (RecordingStore.InvalidUpload e) {
            // expected
        }
    }
}
