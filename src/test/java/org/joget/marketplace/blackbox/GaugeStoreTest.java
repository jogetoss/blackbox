package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;

public class GaugeStoreTest {

    @Test
    public void mergeGroupsByNodeInTimeOrder() throws Exception {
        Path dir = Files.createTempDirectory("bb-gauges");
        assertNull("no samples", GaugeStore.merge(dir));
        GaugeStore.append(dir, "node-a", new JSONObject().put("ts", 2).put("cpu", 0.5));
        GaugeStore.append(dir, "node-a", new JSONObject().put("ts", 1).put("cpu", 0.1));
        GaugeStore.append(dir, "node-b", new JSONObject().put("ts", 1).put("cpu", 0.9));
        JSONObject nodes = GaugeStore.merge(dir).getJSONObject("nodes");
        assertEquals(2, nodes.length());
        assertEquals(1, nodes.getJSONArray("node-a").getJSONObject(0).getLong("ts"));
        assertEquals(1, nodes.getJSONArray("node-b").length());
    }

    @Test
    public void moveAndWriteBack() throws Exception {
        Path from = Files.createTempDirectory("bb-gauges-from");
        Path to = Files.createTempDirectory("bb-gauges-to");
        GaugeStore.append(from, "n", new JSONObject().put("ts", 1));
        GaugeStore.append(to, "n", new JSONObject().put("ts", 2)); // written to the recording meanwhile
        GaugeStore.moveAll(from, to);
        assertFalse(Files.exists(from.resolve(GaugeStore.DIR)));
        JSONObject merged = GaugeStore.merge(to);
        assertEquals(2, merged.getJSONObject("nodes").getJSONArray("n").length());

        Path imported = Files.createTempDirectory("bb-gauges-import");
        GaugeStore.write(imported, merged);
        assertEquals(merged.toString(), GaugeStore.merge(imported).toString());
        GaugeStore.write(imported, new JSONObject("{\"nodes\":{\"../x\":[{\"ts\":1}]}}"));
        assertFalse("node names can't leave the folder", Files.exists(imported.resolve("x.jsonl")));
    }

    @Test
    public void sampleReadsTheJvm() throws Exception {
        JSONObject s = GaugeSampler.sample(123);
        assertEquals(123, s.getLong("ts"));
        assertTrue(s.getLong("heapUsed") > 0);
        assertTrue(s.getLong("heapMax") >= s.getLong("heapUsed"));
        assertFalse("no Joget here, so no DB pool", s.has("dbActive"));
    }
}
