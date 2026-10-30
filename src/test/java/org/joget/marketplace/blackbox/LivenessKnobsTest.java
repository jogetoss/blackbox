package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

public class LivenessKnobsTest {

    private Path root;
    private Path file;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-knobs");
        file = root.resolve(LivenessKnobs.FILE);
        LivenessKnobs.fileSupplier = () -> file;
    }

    private void write(String s) throws Exception {
        Files.write(file, s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void defaultsWhenFileMissing() {
        assertEquals(30_000L, LivenessKnobs.pingMs());
        assertEquals(180_000L, LivenessKnobs.lostMs());
        assertEquals(30_000L, LivenessKnobs.checkMs());
        assertEquals(Long.MAX_VALUE, LivenessKnobs.minLimitMs());
    }

    @Test
    public void overridesAreReadOnEveryCall() throws Exception {
        write("{\"pingMs\":2000,\"lostMs\":6000,\"checkMs\":1500,\"minLimitMs\":90000}");
        assertEquals(2000L, LivenessKnobs.pingMs());
        assertEquals(6000L, LivenessKnobs.lostMs());
        assertEquals(1500L, LivenessKnobs.checkMs());
        assertEquals(90000L, LivenessKnobs.minLimitMs());
        write("{\"pingMs\":4000}"); // changed without a restart; the rest falls back
        assertEquals(4000L, LivenessKnobs.pingMs());
        assertEquals(180_000L, LivenessKnobs.lostMs());
        Files.delete(file);
        assertEquals(30_000L, LivenessKnobs.pingMs());
    }

    @Test
    public void invalidFileOrValuesUseDefaults() throws Exception {
        write("not json");
        assertEquals(30_000L, LivenessKnobs.pingMs());
        write("{\"pingMs\":999,\"lostMs\":0,\"checkMs\":-5,\"minLimitMs\":\"abc\"}"); // below 1000 ms is refused
        assertEquals(30_000L, LivenessKnobs.pingMs());
        assertEquals(180_000L, LivenessKnobs.lostMs());
        assertEquals(30_000L, LivenessKnobs.checkMs());
        assertEquals(Long.MAX_VALUE, LivenessKnobs.minLimitMs());
        write("{\"pingMs\":1000}"); // exactly 1000 is fine
        assertEquals(1000L, LivenessKnobs.pingMs());
    }

    @Test
    public void inUseOnlyWhenFileHasAValidValue() throws Exception {
        assertFalse(LivenessKnobs.inUse());
        write("{\"pingMs\":2000}");
        assertTrue(LivenessKnobs.inUse());
    }

    @Test
    public void recordingListSkipsTheFile() throws Exception {
        write("{\"pingMs\":2000}");
        RecordingStore store = new RecordingStore(root);
        assertEquals(0, store.list().size());
        assertNull(store.readMeta(LivenessKnobs.FILE));
    }

    @Test
    public void captureSweepKeepsTheFile() throws Exception {
        write("{\"pingMs\":2000}");
        // the file is next to pending/, the sweep only looks inside the pending folder
        Path pending = Files.createDirectory(root.resolve("pending"));
        assertEquals(0, new ServerCaptureStore(pending).sweep(Long.MAX_VALUE));
        assertTrue(Files.exists(file));
        // and even when it sits in the swept folder itself, it is no capture and stays
        Files.copy(file, pending.resolve(LivenessKnobs.FILE));
        assertEquals(0, new ServerCaptureStore(pending).sweep(Long.MAX_VALUE));
        assertTrue(Files.exists(pending.resolve(LivenessKnobs.FILE)));
    }
}
