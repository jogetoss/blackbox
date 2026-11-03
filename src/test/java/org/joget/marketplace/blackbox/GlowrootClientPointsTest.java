package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** GlowrootClient.points against a fake Glowroot that answers like 0.14: error traces only under error/points. */
public class GlowrootClientPointsTest {

    private HttpServer server;
    private final List<String> asked = new ArrayList<>();

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backend/", ex -> {
            String path = ex.getRequestURI().getPath();
            asked.add(path);
            String body;
            if (path.equals("/backend/transaction/points")) {
                // the slow request, and a slow failed one (also under error/points)
                body = "{\"normalPoints\":[[1000,2500.4,\"\",\"slow\"]],\"errorPoints\":[[2000,3000.0,\"\",\"slowFailed\"]],\"partialPoints\":[]}";
            } else if (path.equals("/backend/error/points")) {
                // the fast failed request is only here
                body = "{\"normalPoints\":[],\"errorPoints\":[[3000,263.2,\"\",\"fastFailed\"],[2000,3000.0,\"\",\"slowFailed\"]],\"partialPoints\":[]}";
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void fastFailedRequestsAreFoundOnce() throws Exception {
        GlowrootClient g = new GlowrootClient("http://127.0.0.1:" + server.getAddress().getPort());
        List<GlowrootClient.Point> points = g.points(0, 10_000);
        assertEquals(java.util.Arrays.asList("slow", "slowFailed", "fastFailed"),
                points.stream().map(p -> p.traceId).collect(Collectors.toList()));
        GlowrootClient.Point fast = points.get(2);
        assertEquals(3000, fast.endMs);
        assertEquals(263, fast.durationMs);
        assertTrue(asked.contains("/backend/error/points"));
    }
}
