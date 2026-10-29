package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ExportJobsTest {

    @Test
    public void exportingIsLostOnlyWhenQueuedHereBeforeTheJvmStarted() throws Exception {
        ExportJobs.start();
        try {
            JSONObject now = ExportJobs.exporting();
            assertFalse("queued since the start: still running", ExportJobs.lost(now));
            JSONObject before = new JSONObject(now.toString()).put("at", 1);
            assertTrue("queued before a restart", ExportJobs.lost(before));
            ExportJobs.stop();
            ExportJobs.start();
            assertFalse("a plugin reload: the old bundle's job may still run", ExportJobs.lost(now));
            assertFalse("another node's job", ExportJobs.lost(new JSONObject(before.toString()).put("node", "other-node")));
            assertTrue("older version: no node", ExportJobs.lost(new JSONObject().put("state", "exporting").put("at", 1)));
            assertFalse("ended", ExportJobs.lost(new JSONObject(before.toString()).put("state", "done")));
            assertFalse(ExportJobs.lost(null));
        } finally {
            ExportJobs.stop();
        }
    }

    @Test
    public void stopSavesJobsItDropsAsFailed() throws Exception {
        java.util.function.UnaryOperator<Runnable> real = ExportJobs.withRequest;
        ExportJobs.withRequest = r -> r; // PluginThread needs a running Joget
        ExportJobs.start();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger dropped = new java.util.concurrent.atomic.AtomicInteger();
        try {
            ExportJobs.submit(() -> {
                running.countDown();
                try {
                    release.await(); // interrupted by stop(): ends on its own
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.incrementAndGet();
            }, dropped::incrementAndGet);
            ExportJobs.submit(ran::incrementAndGet, dropped::incrementAndGet);
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            ExportJobs.stop();
            ExportJobs.withRequest = real;
        }
        assertEquals("only the queued one is dropped", 1, dropped.get());
        long until = System.currentTimeMillis() + 5000;
        while (ran.get() < 1 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertEquals("the running one ends itself", 1, ran.get());
    }
}
