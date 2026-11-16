package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class OneTimeTokensTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long NOW = 1_700_000_000_000L;

    private Path root;
    private OneTimeTokens tokens;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-tokens");
        tokens = new OneTimeTokens(root.resolve(OneTimeTokens.DIR));
    }

    private static JSONObject opts() throws Exception {
        return new JSONObject().put("traces", true).put("admin", false);
    }

    @Test
    public void tokenWorksOnceAndCarriesOwnerAndOptions() throws Exception {
        String t = tokens.arm("cat", opts(), NOW);
        assertTrue(OneTimeTokens.validToken(t));
        JSONObject used = tokens.use(t, NOW + 60_000);
        assertEquals("cat", used.getString("owner"));
        assertTrue(used.getJSONObject("options").getBoolean("traces"));
        assertNull("single use", tokens.use(t, NOW + 61_000));
    }

    @Test
    public void onlyTheHashIsStored() throws Exception {
        String t = tokens.arm("cat", opts(), NOW);
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve(OneTimeTokens.DIR))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                assertFalse(f.getFileName().toString().contains(t));
                assertFalse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains(t));
                assertEquals(OneTimeTokens.hash(t) + ".json", f.getFileName().toString());
            }
        }
    }

    @Test
    public void expiredTokenIsRefusedAndUsedUp() throws Exception {
        String t = tokens.arm("cat", opts(), NOW);
        assertNull(tokens.use(t, NOW + OneTimeTokens.TTL_MS + 1));
        assertEquals(0, tokens.count("cat", NOW));
    }

    @Test
    public void expirySettingIsClampedToOneToSixtyMinutes() {
        long min = 60_000L;
        assertEquals("default", 15 * min, OneTimeTokens.ttlMs(null));
        assertEquals(15 * min, OneTimeTokens.ttlMs(""));
        assertEquals(15 * min, OneTimeTokens.ttlMs("abc"));
        assertEquals(5 * min, OneTimeTokens.ttlMs(" 5 "));
        assertEquals(1 * min, OneTimeTokens.ttlMs("0"));
        assertEquals(1 * min, OneTimeTokens.ttlMs("-3"));
        assertEquals(60 * min, OneTimeTokens.ttlMs("60"));
        assertEquals(60 * min, OneTimeTokens.ttlMs("600"));
    }

    @Test
    public void tokenKeepsTheExpiryItWasPreparedWith() throws Exception {
        long ttl = 2 * 60_000L;
        String shortOne = tokens.arm("cat", opts(), NOW, ttl);
        String longOne = tokens.arm("cat", opts(), NOW, 60 * 60_000L);
        assertNull("past its own 2 minutes", tokens.use(shortOne, NOW + ttl + 1));
        assertNotNull("within its own 60 minutes", tokens.use(longOne, NOW + 59 * 60_000L));
    }

    @Test
    public void unknownOrMalformedTokensAreRefused() throws Exception {
        tokens.arm("cat", opts(), NOW);
        assertNull(tokens.use(OneTimeTokens.newToken(), NOW));
        assertNull(tokens.use("../../etc/passwd", NOW));
        assertNull(tokens.use("", NOW));
        assertNull(tokens.use(null, NOW));
    }

    @Test
    public void authorizationHeaderParsing() {
        String t = OneTimeTokens.newToken();
        assertEquals(t, OneTimeTokens.tokenFrom("BlackBox-Token " + t));
        assertEquals("scheme is case-insensitive", t, OneTimeTokens.tokenFrom("blackbox-token " + t));
        assertNull(OneTimeTokens.tokenFrom("Bearer " + t));
        assertNull(OneTimeTokens.tokenFrom("BlackBox-Token"));
        assertNull(OneTimeTokens.tokenFrom("BlackBox-Token short"));
        assertNull(OneTimeTokens.tokenFrom(null));
    }

    @Test
    public void countAndSweep() throws Exception {
        tokens.arm("cat", opts(), NOW);
        tokens.arm("cat", opts(), NOW + 1000);
        tokens.arm("dog", opts(), NOW);
        assertEquals(2, tokens.count("cat", NOW + 2000));
        assertEquals(0, tokens.count("cat", NOW + OneTimeTokens.TTL_MS + 2000));
        assertEquals("expired ones deleted", 2, tokens.sweep(NOW + OneTimeTokens.TTL_MS + 500));
        assertEquals(1, tokens.count("cat", NOW + 2000));
    }

    @Test
    public void concurrentUseSucceedsOnce() throws Exception {
        String t = tokens.arm("cat", opts(), NOW);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<JSONObject>> runs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                runs.add(pool.submit(() -> tokens.use(t, NOW)));
            }
            int ok = 0;
            for (Future<JSONObject> f : runs) {
                ok += f.get() != null ? 1 : 0;
            }
            assertEquals(1, ok);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void captureSecretIdentifiesOwnerAndIsDroppedAtAttach() throws Exception {
        ServerCaptureStore store = new ServerCaptureStore(root.resolve("pending"));
        assertTrue(store.start(ID, "cat", 1024, 24L * 3600 * 1000));
        String secret = OneTimeTokens.newToken();
        store.put(ID, ServerCaptureStore.SECRET_HASH, OneTimeTokens.hash(secret));
        assertEquals("cat", store.ownerBySecret(ID, secret));
        assertNull(store.ownerBySecret(ID, OneTimeTokens.newToken()));
        assertNull(store.ownerBySecret(ID, null));
        assertNull(store.ownerBySecret(ID, ""));

        Path rec = Files.createDirectories(root.resolve("rec"));
        assertNotNull(store.attach(ID, "cat", rec.resolve(ServerCaptureStore.FILE)));
        assertFalse(store.readMeta(ID).has(ServerCaptureStore.SECRET_HASH));
        assertNull("no secret after upload", store.ownerBySecret(ID, secret));
    }

    @Test
    public void captureWithoutSecretNeverMatches() throws Exception {
        ServerCaptureStore store = new ServerCaptureStore(root.resolve("pending"));
        assertTrue(store.start(ID, "cat", 1024, 24L * 3600 * 1000));
        assertNull(store.ownerBySecret(ID, OneTimeTokens.newToken()));
    }
}
