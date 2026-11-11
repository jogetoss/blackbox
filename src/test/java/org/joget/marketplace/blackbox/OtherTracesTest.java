package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Other users' traces (capture option otherTraces): TraceCollector.collectOthers against a fake Glowroot 0.14. A
 * recording ran from 10 s to 20 s; Glowroot kept traces of its own request, another user's, a background job and a
 * request that started after the stop.
 */
public class OtherTracesTest {

    private static final String CAPTURE = "cap1";
    private static final long FROM = 10_000;
    private static final long TO = 20_000;

    private HttpServer server;
    private final Map<String, JSONObject> headers = new HashMap<>();
    private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

    @Before
    public void start() throws Exception {
        headers.put("own", header(12_000, 500, "cat").put("attributes",
                new JSONObject().put(GlowrootTag.ATTRIBUTE, new JSONArray().put(CAPTURE))));
        headers.put("bob", header(14_000, 3000, "bob"));
        headers.put("job", header(15_000, 1000, "").put("transactionType", "Background").put("headline", "Email tool"));
        headers.put("late", header(21_000, 1000, "bob"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static JSONObject header(long start, long ms, String user) throws Exception {
        return new JSONObject().put("startTime", start).put("durationNanos", ms * 1_000_000).put("user", user)
                .put("transactionType", "Web").put("headline", "/jw/web/json/x")
                .put("detail", new JSONObject().put("Request http method", "POST")).put("queriesExistence", "no");
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        calls.add(path);
        Map<String, String> q = new HashMap<>();
        for (String kv : ex.getRequestURI().getRawQuery().split("&")) {
            int eq = kv.indexOf('=');
            q.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
        }
        byte[] body;
        if (path.equals("/backend/transaction/points")) {
            // [end, duration, ?, id]: the Background list has only the job
            List<String> ids = "Background".equals(q.get("transaction-type")) ? java.util.Arrays.asList("job") : java.util.Arrays.asList("own", "bob", "late");
            JSONArray pts = new JSONArray();
            for (String id : ids) {
                JSONObject h = headers.get(id);
                long ms = h.getLong("durationNanos") / 1_000_000;
                pts.put(new JSONArray().put(h.getLong("startTime") + ms).put(ms).put("").put(id));
            }
            body = new JSONObject().put("normalPoints", pts).toString().getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/backend/error/points")) {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/backend/trace/header")) {
            body = headers.get(q.get("trace-id")).toString().getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/export/trace")) {
            ByteArrayOutputStream zip = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(zip)) {
                z.putNextEntry(new ZipEntry("trace.html"));
                z.write(("<html>" + q.get("trace-id") + "</html>").getBytes(StandardCharsets.UTF_8));
            }
            body = zip.toByteArray();
        } else {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @Test
    public void copiesOtherUsersAndJobsButNotItsOwnOrLaterOnes() throws Exception {
        GlowrootClient g = new GlowrootClient("http://127.0.0.1:" + server.getAddress().getPort());
        Path dir = Files.createTempDirectory("bb-others");
        List<JSONObject> out = new ArrayList<>();
        TraceCollector.collectOthers(g, CAPTURE, FROM, TO, () -> dir, out::add, Long.MAX_VALUE);

        assertEquals("bob's request, the background job and the closing note", 3, out.size());
        assertEquals("none", out.get(2).getString("state"));
        assertTrue(out.get(2).getString("reason"), out.get(2).getString("reason").startsWith("2 other traces copied"));
        JSONObject bob = out.get(0);
        assertEquals("bob", bob.getString("user"));
        assertTrue(bob.getBoolean("other"));
        assertEquals("saved", bob.getString("state"));
        assertEquals(14_000, bob.getLong("ts"));
        assertEquals(3000, bob.getLong("ms"));
        assertEquals("POST", bob.getString("method"));
        assertEquals(CAPTURE, bob.getString("capture"));
        assertFalse("copied once, never retried", TraceCollector.retryable(bob));
        assertEquals("<html>bob</html>", new String(Files.readAllBytes(dir.resolve(TraceStore.DIR).resolve(bob.getString("file"))),
                StandardCharsets.UTF_8));
        JSONObject job = out.get(1);
        assertEquals("Background", job.getString("type"));
        assertEquals("Email tool", job.getString("url"));
    }

    @Test
    public void aSlowGlowrootOnlyGetsCountedOnceTheTimeIsUp() throws Exception {
        GlowrootClient g = new GlowrootClient("http://127.0.0.1:" + server.getAddress().getPort());
        List<JSONObject> out = new ArrayList<>();
        Path dir = Files.createTempDirectory("bb-others");
        TraceCollector.collectOthers(g, CAPTURE, FROM, TO, () -> dir, out::add, 0);

        assertEquals("only the note", 1, out.size());
        assertEquals("none", out.get(0).getString("state"));
        assertTrue(out.get(0).getString("reason"), out.get(0).getString("reason").contains("too slow")
                && out.get(0).getString("reason").contains("0 copied, up to 3 more"));
        for (String c : calls) {
            assertTrue("no header or export call after the deadline: " + c, c.endsWith("/points"));
        }
    }

    @Test
    public void aRestartPicksUpTheCapturesThisNodeStillOwesOtherTracesFor() throws Exception {
        ServerCaptureStore store = new ServerCaptureStore(Files.createTempDirectory("bb-pending"));
        String owed = "00000000-0000-4000-8000-000000000001";
        String done = "00000000-0000-4000-8000-000000000002";
        String plain = "00000000-0000-4000-8000-000000000003";
        for (String id : new String[] {owed, done, plain}) {
            assertTrue(store.start(id, "admin", 1 << 20, 60_000, true, "", false, false, false, 0, true));
        }
        store.put(owed, TraceCollector.OTHERS_PENDING, true);
        store.put(done, TraceCollector.OTHERS_PENDING, false);
        store.put(plain, "othersPending-another-node", true);
        TraceCollector.stop(); // clears what is watched
        java.util.function.Supplier<ServerCaptureStore> real = ServerCapture.stores;
        ServerCapture.stores = () -> store;
        try {
            assertEquals(java.util.Arrays.asList(owed), TraceCollector.recoverOthers(store, 1));
            assertTrue("already watched: not added twice", TraceCollector.recoverOthers(store, 2).isEmpty());
            // its "pending" note keeps the replay page polling until the copy
            JSONArray notes = TraceStore.merge(store.dir(owed), 3);
            assertEquals(1, notes.length());
            assertEquals("pending", notes.getJSONObject(0).getString("state"));
            assertTrue(notes.getJSONObject(0).getBoolean("other"));
        } finally {
            ServerCapture.stores = real;
            TraceCollector.stop();
        }
    }
}
