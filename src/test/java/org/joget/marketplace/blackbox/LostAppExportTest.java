package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LostAppExportTest {

    private static final String SESSION = "{\"tool\":\"blackbox 1.3.0\",\"recording\":{\"durationMs\":1200,"
            + "\"tab\":{\"url\":\"http://x/jw\",\"title\":\"Joget DX\"}},\"summary\":{\"errors\":0},\"console\":[],\"network\":[]}";
    private static final String CAPTURE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long DAY = 24L * 3600 * 1000;

    private RecordingStore recordings;
    private ServerCaptureStore captures;
    private Supplier<RecordingStore> realRecordings;
    private Supplier<ServerCaptureStore> realCaptures;

    @Before
    public void setUp() throws Exception {
        Path root = Files.createTempDirectory("bb-lost");
        recordings = new RecordingStore(root);
        captures = new ServerCaptureStore(root.resolve("pending"));
        realRecordings = BlackBoxPlugin.stores;
        realCaptures = ServerCapture.stores;
        BlackBoxPlugin.stores = () -> recordings;
        ServerCapture.stores = () -> captures;
    }

    @After
    public void tearDown() {
        BlackBoxPlugin.stores = realRecordings;
        ServerCapture.stores = realCaptures;
        BlackBoxPlugin.afterLostCheck = () -> {
        };
    }

    private String recording() throws Exception {
        return recordings.create(new ByteArrayInputStream(SESSION.getBytes(StandardCharsets.UTF_8)), 1024 * 1024, "admin", "Admin", "", "")
                .getString("id");
    }

    private void capture(String rec, JSONObject state) throws Exception {
        assertTrue(captures.start(CAPTURE, "admin", 1024, DAY));
        assertTrue(captures.put(CAPTURE, "recording", rec));
        assertTrue(captures.put(CAPTURE, "appExport", state));
    }

    private static JSONObject exporting(long at) throws Exception {
        return new JSONObject().put("state", "exporting").put("at", at).put("node", "test-node");
    }

    private static JSONObject longAgo() throws Exception {
        return exporting(System.currentTimeMillis() - ExportJobs.MAX_EXPORT_MS - 60_000);
    }

    private static JSONObject done() throws Exception {
        return new JSONObject().put("state", "done").put("saved", new JSONArray().put(new JSONObject().put("file", "crm-1.jwa")));
    }

    private void exportedApp() throws Exception {
        Path dir = Files.createDirectories(captures.dir(CAPTURE).resolve(AppExport.DIR));
        Files.write(dir.resolve("crm-1.jwa"), new byte[] {1});
    }

    private JSONObject appExport(String rec) {
        return recordings.readMeta(rec).optJSONObject("appExport");
    }

    private void assertAppsDelivered(String rec) {
        JSONObject meta = recordings.readMeta(rec);
        assertEquals("the job's end, not lost: " + meta.optJSONObject("appExport"), "done", meta.getJSONObject("appExport").getString("state"));
        assertEquals(1, meta.getJSONArray("apps").length());
        assertTrue(Files.isRegularFile(recordings.dir(rec).resolve(AppExport.DIR).resolve("crm-1.jwa")));
    }

    @Test
    public void aLostMarkDoesNotOverwriteTheStopJobsEnd() throws Exception {
        String rec = recording();
        JSONObject old = longAgo();
        capture(rec, old);
        recordings.updateMeta(rec, new JSONObject().put("appExport", new JSONObject(old.toString()).put("capture", CAPTURE)));
        exportedApp();
        BlackBoxPlugin.afterLostCheck = () -> {
            try {
                assertTrue(captures.put(CAPTURE, "appExport", done()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        BlackBoxPlugin.captureMeta(CAPTURE);
        BlackBoxPlugin.afterLostCheck = () -> {
        };
        BlackBoxPlugin.deliverStopExport(CAPTURE);

        assertEquals("done", captures.readMeta(CAPTURE).getJSONObject("appExport").getString("state"));
        assertAppsDelivered(rec);
    }

    @Test
    public void aLostMarkDoesNotOverwriteTheUploadJobsEnd() throws Exception {
        String rec = recording();
        JSONObject meta = recordings.updateMeta(rec, new JSONObject().put("appExport", longAgo()));
        BlackBoxPlugin.afterLostCheck = () -> {
            try {
                recordings.updateMeta(rec, new JSONObject().put("appExport", new JSONObject().put("state", "done"))
                        .put("apps", new JSONArray().put(new JSONObject().put("file", "crm-1.jwa"))));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        BlackBoxPlugin.settleAppExport(recordings, rec, meta);

        assertEquals("done", appExport(rec).getString("state"));
        assertEquals(1, recordings.readMeta(rec).getJSONArray("apps").length());
    }

    @Test
    public void aFailedLateDeliveryIsRetriedWhenTheRecordingIsOpened() throws Exception {
        String rec = recording();
        capture(rec, ExportJobs.lostState());
        recordings.updateMeta(rec, new JSONObject().put("appExport", ExportJobs.lostState().put("capture", CAPTURE)));
        exportedApp();
        Path blocker = recordings.dir(rec).resolve(AppExport.DIR);
        Files.write(blocker, new byte[0]);
        BlackBoxPlugin.endStopExport(CAPTURE, done());
        assertTrue("setup: the late delivery failed", ExportJobs.wasLost(appExport(rec)));
        assertEquals("done", captures.readMeta(CAPTURE).getJSONObject("appExport").getString("state"));

        Files.delete(blocker);
        BlackBoxPlugin.settleAppExport(recordings, rec, recordings.readMeta(rec));

        assertAppsDelivered(rec);
    }

    @Test
    public void anUnreadableCaptureMetaDoesNotCloseTheRecording() throws Exception {
        String rec = recording();
        JSONObject running = exporting(System.currentTimeMillis());
        capture(rec, running);
        recordings.updateMeta(rec, new JSONObject().put("appExport", new JSONObject(running.toString()).put("capture", CAPTURE)));
        Path metaFile = captures.dir(CAPTURE).resolve("meta.json");
        byte[] good = Files.readAllBytes(metaFile);
        Files.write(metaFile, "{".getBytes(StandardCharsets.UTF_8));
        BlackBoxPlugin.settleAppExport(recordings, rec, recordings.readMeta(rec));
        Files.write(metaFile, good);

        exportedApp();
        BlackBoxPlugin.endStopExport(CAPTURE, done());

        assertAppsDelivered(rec);
    }

    @Test
    public void openingALostRecordingWhoseCaptureIsLostWritesNothing() throws Exception {
        String rec = recording();
        capture(rec, ExportJobs.lostState());
        recordings.updateMeta(rec, new JSONObject().put("appExport", ExportJobs.lostState().put("capture", CAPTURE)));
        Path metaFile = recordings.dir(rec).resolve(RecordingStore.META);
        Object before = Files.readAttributes(metaFile, BasicFileAttributes.class).fileKey();

        for (int i = 0; i < 3; i++) {
            BlackBoxPlugin.settleAppExport(recordings, rec, recordings.readMeta(rec));
        }

        assertEquals("meta.json saved again", before, Files.readAttributes(metaFile, BasicFileAttributes.class).fileKey());
        assertTrue(ExportJobs.wasLost(appExport(rec)));
    }
}
