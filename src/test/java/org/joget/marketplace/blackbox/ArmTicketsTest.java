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

public class ArmTicketsTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long NOW = 1_700_000_000_000L;

    private Path root;
    private ArmTickets tickets;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("bb-tickets");
        tickets = new ArmTickets(root.resolve(ArmTickets.DIR));
    }

    private static JSONObject opts() throws Exception {
        return new JSONObject().put("traces", true).put("admin", false);
    }

    @Test
    public void ticketWorksOnceAndCarriesOwnerAndOptions() throws Exception {
        String t = tickets.arm("cat", opts(), NOW);
        assertTrue(ArmTickets.validToken(t));
        JSONObject used = tickets.use(t, NOW + 60_000);
        assertEquals("cat", used.getString("owner"));
        assertTrue(used.getJSONObject("options").getBoolean("traces"));
        assertNull("single use", tickets.use(t, NOW + 61_000));
    }

    @Test
    public void onlyTheHashIsStored() throws Exception {
        String t = tickets.arm("cat", opts(), NOW);
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve(ArmTickets.DIR))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                assertFalse(f.getFileName().toString().contains(t));
                assertFalse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains(t));
                assertEquals(ArmTickets.hash(t) + ".json", f.getFileName().toString());
            }
        }
    }

    @Test
    public void expiredTicketIsRefusedAndUsedUp() throws Exception {
        String t = tickets.arm("cat", opts(), NOW);
        assertNull(tickets.use(t, NOW + ArmTickets.TTL_MS + 1));
        assertEquals(0, tickets.count("cat", NOW));
    }

    @Test
    public void expirySettingIsClampedToOneToSixtyMinutes() {
        long min = 60_000L;
        assertEquals("default", 15 * min, ArmTickets.ttlMs(null));
        assertEquals(15 * min, ArmTickets.ttlMs(""));
        assertEquals(15 * min, ArmTickets.ttlMs("abc"));
        assertEquals(5 * min, ArmTickets.ttlMs(" 5 "));
        assertEquals(1 * min, ArmTickets.ttlMs("0"));
        assertEquals(1 * min, ArmTickets.ttlMs("-3"));
        assertEquals(60 * min, ArmTickets.ttlMs("60"));
        assertEquals(60 * min, ArmTickets.ttlMs("600"));
    }

    @Test
    public void ticketKeepsTheExpiryItWasPreparedWith() throws Exception {
        long ttl = 2 * 60_000L;
        String shortOne = tickets.arm("cat", opts(), NOW, ttl);
        String longOne = tickets.arm("cat", opts(), NOW, 60 * 60_000L);
        assertNull("past its own 2 minutes", tickets.use(shortOne, NOW + ttl + 1));
        assertNotNull("within its own 60 minutes", tickets.use(longOne, NOW + 59 * 60_000L));
    }

    @Test
    public void unknownOrMalformedTicketsAreRefused() throws Exception {
        tickets.arm("cat", opts(), NOW);
        assertNull(tickets.use(ArmTickets.newToken(), NOW));
        assertNull(tickets.use("../../etc/passwd", NOW));
        assertNull(tickets.use("", NOW));
        assertNull(tickets.use(null, NOW));
    }

    @Test
    public void authorizationHeaderParsing() {
        String t = ArmTickets.newToken();
        assertEquals(t, ArmTickets.ticketFrom("BlackBox-Ticket " + t));
        assertEquals("scheme is case-insensitive", t, ArmTickets.ticketFrom("blackbox-ticket " + t));
        assertNull(ArmTickets.ticketFrom("Bearer " + t));
        assertNull(ArmTickets.ticketFrom("BlackBox-Ticket"));
        assertNull(ArmTickets.ticketFrom("BlackBox-Ticket short"));
        assertNull(ArmTickets.ticketFrom(null));
    }

    @Test
    public void countAndSweep() throws Exception {
        tickets.arm("cat", opts(), NOW);
        tickets.arm("cat", opts(), NOW + 1000);
        tickets.arm("dog", opts(), NOW);
        assertEquals(2, tickets.count("cat", NOW + 2000));
        assertEquals(0, tickets.count("cat", NOW + ArmTickets.TTL_MS + 2000));
        assertEquals("expired ones deleted", 2, tickets.sweep(NOW + ArmTickets.TTL_MS + 500));
        assertEquals(1, tickets.count("cat", NOW + 2000));
    }

    @Test
    public void concurrentUseSucceedsOnce() throws Exception {
        String t = tickets.arm("cat", opts(), NOW);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<JSONObject>> runs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                runs.add(pool.submit(() -> tickets.use(t, NOW)));
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
        String secret = ArmTickets.newToken();
        store.put(ID, ServerCaptureStore.SECRET_HASH, ArmTickets.hash(secret));
        assertEquals("cat", store.ownerBySecret(ID, secret));
        assertNull(store.ownerBySecret(ID, ArmTickets.newToken()));
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
        assertNull(store.ownerBySecret(ID, ArmTickets.newToken()));
    }
}
