package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;

public class ServerDataTest {

    @Test
    public void slowFollowsGlowrootUnlessItKeepsEverything() {
        assertEquals(2000, ServerData.slowMs(2000));
        assertEquals(5000, ServerData.slowMs(5000));
        assertEquals("0 ms = trace everything, not 'all slow'", ServerData.DEFAULT_SLOW_MS, ServerData.slowMs(0));
        assertEquals(ServerData.DEFAULT_SLOW_MS, ServerData.slowMs(50));
    }

    @Test
    public void countsSlowTracesAndPeaks() throws Exception {
        Path dir = Files.createTempDirectory("bb-serverdata");
        TraceStore.append(dir, "n", new JSONObject().put("key", "a").put("ms", 5526).put("state", "saved").put("updated", 1));
        TraceStore.append(dir, "n", new JSONObject().put("key", "b").put("ms", 3).put("state", "saved").put("updated", 1));
        TraceStore.append(dir, "n", new JSONObject().put("key", "c").put("ms", 1).put("state", "pending").put("updated", System.currentTimeMillis()));
        GaugeStore.append(dir, "n", new JSONObject().put("ts", 1).put("cpu", 0.3).put("heapUsed", 1).put("heapMax", 4)
                .put("threadsBusy", 9).put("threadsMax", 10).put("dbActive", 2).put("dbMax", 20).put("dbWaiting", 3).put("gcMs", 120));
        GaugeStore.append(dir, "m", new JSONObject().put("ts", 1).put("cpu", 0.95));
        JSONObject d = ServerData.of(dir, 2000);
        assertEquals(3, d.getJSONObject("traces").getInt("total"));
        assertEquals(1, d.getJSONObject("traces").getInt("slow"));
        assertEquals(1, d.getJSONObject("traces").getInt("pending"));
        JSONObject load = d.getJSONObject("load");
        assertEquals(0.95, load.getDouble("cpu"), 0.0001);
        assertEquals(0.9, load.getDouble("threads"), 0.0001);
        assertEquals(0.1, load.getDouble("db"), 0.0001);
        assertEquals(3, load.getLong("dbWaiting"));
        assertFalse(d.getBoolean("info"));
        assertFalse("no samples, no load", ServerData.of(Files.createTempDirectory("bb-empty"), 2000).has("load"));
    }
}
