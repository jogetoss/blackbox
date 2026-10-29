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
            long jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            JSONObject before = new JSONObject(now.toString()).put("at", jvmStart - 1000); // within MAX_EXPORT_MS
            assertTrue("queued before a restart", ExportJobs.lost(before));
            ExportJobs.stop();
            ExportJobs.start();
            assertFalse("a plugin reload: the old bundle's job may still run", ExportJobs.lost(now));
            assertFalse("another node's job", ExportJobs.lost(new JSONObject(before.toString()).put("node", "other-node")));
            assertTrue("older version: no node", ExportJobs.lost(new JSONObject().put("state", "exporting").put("at", jvmStart - 1000)));
            assertFalse("ended", ExportJobs.lost(new JSONObject(before.toString()).put("state", "done")));
            assertFalse("saved as lost already", ExportJobs.lost(ExportJobs.lostState()));
            assertFalse(ExportJobs.lost(null));
        } finally {
            ExportJobs.stop();
        }
    }

    @Test
    public void exportingOlderThanTheLimitIsLostOnAnyNode() throws Exception {
        ExportJobs.start();
        try {
            long old = System.currentTimeMillis() - ExportJobs.MAX_EXPORT_MS - 1000;
            assertTrue(ExportJobs.lost(new JSONObject().put("state", "exporting").put("at", old).put("node", "other-node")));
            assertFalse(ExportJobs.lost(new JSONObject().put("state", "exporting").put("at", System.currentTimeMillis()).put("node", "other-node")));
            JSONObject lost = ExportJobs.lostState();
            assertEquals("failed", lost.getString("state"));
            assertTrue(ExportJobs.wasLost(lost));
            assertFalse("a job's own failure is final", ExportJobs.wasLost(new JSONObject().put("state", "failed")));
            assertFalse(ExportJobs.wasLost(null));
        } finally {
            ExportJobs.stop();
        }
    }

    @Test
    public void stopDropsQueuedJobsOnAThreadOfItsOwnAndLetsTheRunningOneFinish() throws Exception {
        java.util.function.UnaryOperator<Runnable> real = ExportJobs.withRequest;
        ExportJobs.withRequest = r -> r; // PluginThread needs a running Joget
        ExportJobs.start();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> droppedOn = new java.util.concurrent.CopyOnWriteArrayList<>();
        ThreadLocal<String> callerState = new ThreadLocal<>(); // stands for the stopping request's attributes and user
        callerState.set("request");
        try {
            ExportJobs.submit(() -> {
                running.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                ran.incrementAndGet();
            }, () -> droppedOn.add(Thread.currentThread()));
            ExportJobs.submit(ran::incrementAndGet, () -> {
                callerState.remove(); // what PluginThread.run() does to its thread when it ends
                droppedOn.add(Thread.currentThread());
            });
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            ExportJobs.stop();
            ExportJobs.withRequest = real;
        }
        assertEquals("only the queued one is dropped, and stop() waited for it", 1, droppedOn.size());
        assertNotSame("not on the stopping thread", Thread.currentThread(), droppedOn.get(0));
        assertEquals("the stopping thread keeps its state", "request", callerState.get());
        release.countDown();
        long until = System.currentTimeMillis() + 5000;
        while (ran.get() < 1 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertEquals("the running one ends itself", 1, ran.get());
        assertFalse("and isn't interrupted", interrupted.get());
    }
}
