package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Samples this node's load once a second while a recording's capture is open here: heap, GC time, CPU, Tomcat's
 * busy request threads and Joget's database pool. The replay page draws them as the Server load strip, to show
 * whether the server was short of memory, CPU, threads or connections when something got slow.
 *
 * Read from the JVM itself (no Glowroot needed). A node starts sampling a capture when the capture opens on it (the
 * start call, or its first tagged request) and stops when the capture is stopped, or after {@link #MAX_MS}.
 * Samples go to gauges/{node}.jsonl next to the traces ({@link GaugeStore}).
 */
final class GaugeSampler {

    private static final long MAX_MS = 2 * 3600 * 1000L;
    private static final long META_CHECK_MS = 5000;
    private static final String NODE = SystemLogCapture.nodeName();

    private static final Map<String, Sampled> CAPTURES = new ConcurrentHashMap<>();
    private static ScheduledExecutorService timer;
    private static long lastGcMs = -1;
    private static boolean warned;

    private GaugeSampler() {
    }

    static synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlackBox gauges");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(GaugeSampler::tick, 1, 1, TimeUnit.SECONDS);
    }

    static synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        CAPTURES.clear();
    }

    /** A capture is open on this node: sample for it until it stops. */
    static void watch(SystemLogCapture.Capture capture) {
        if (timer != null && capture.gauges) {
            CAPTURES.putIfAbsent(capture.id, new Sampled());
        }
    }

    private static void tick() {
        if (CAPTURES.isEmpty()) {
            lastGcMs = -1;
            return;
        }
        long now = System.currentTimeMillis();
        JSONObject sample;
        try {
            sample = sample(now);
        } catch (RuntimeException e) { // JSONException too
            return;
        }
        for (Map.Entry<String, Sampled> e : CAPTURES.entrySet()) {
            Sampled s = e.getValue();
            if (now - s.metaCheckedAt >= META_CHECK_MS) {
                s.metaCheckedAt = now;
                JSONObject meta = SystemLogCapture.store().readMeta(e.getKey());
                if (meta == null || meta.has("stoppedAt") && now > meta.optLong("stoppedAt") + 1000 || now - s.since > MAX_MS) {
                    CAPTURES.remove(e.getKey());
                    continue;
                }
            }
            try {
                java.nio.file.Path dir = TraceCollector.captureDir(e.getKey());
                if (dir != null) {
                    GaugeStore.append(dir, NODE, sample);
                }
            } catch (IOException ex) {
                CAPTURES.remove(e.getKey());
            }
        }
    }

    /** One sample. Values the JVM or the server can't give are left out. */
    static JSONObject sample(long now) throws JSONException {
        JSONObject s = new JSONObject().put("ts", now);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        s.put("heapUsed", heap.getUsed()).put("heapMax", heap.getMax() > 0 ? heap.getMax() : heap.getCommitted());

        long gc = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc += Math.max(0, b.getCollectionTime());
        }
        if (lastGcMs >= 0) {
            s.put("gcMs", Math.max(0, gc - lastGcMs)); // GC time in the last second
        }
        lastGcMs = gc;

        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName os = new ObjectName("java.lang:type=OperatingSystem");
            putLoad(s, "cpu", mbs.getAttribute(os, "ProcessCpuLoad"));
            putLoad(s, "sysCpu", mbs.getAttribute(os, "SystemCpuLoad"));
            int busy = 0;
            int max = 0;
            for (ObjectName pool : mbs.queryNames(new ObjectName("Catalina:type=ThreadPool,*"), null)) {
                busy += ((Number) mbs.getAttribute(pool, "currentThreadsBusy")).intValue();
                max += ((Number) mbs.getAttribute(pool, "maxThreads")).intValue();
            }
            if (max > 0) {
                s.put("threadsBusy", busy).put("threadsMax", max);
            }
        } catch (Exception | LinkageError e) {
            warnOnce("CPU and thread pool", e);
        }

        try {
            // Joget's DynamicDataSource is a Tomcat JDBC pool (getActive, getMaxActive, getWaitCount)
            Object ds = AppUtil.getApplicationContext().getBean("setupDataSource");
            s.put("dbActive", call(ds, "getActive")).put("dbMax", call(ds, "getMaxActive")).put("dbWaiting", call(ds, "getWaitCount"));
        } catch (Exception | LinkageError e) {
            warnOnce("database pool", e);
        }
        return s;
    }

    private static void putLoad(JSONObject s, String key, Object v) throws JSONException {
        if (v instanceof Number && ((Number) v).doubleValue() >= 0) {
            s.put(key, Math.round(((Number) v).doubleValue() * 1000) / 1000.0);
        }
    }

    private static int call(Object o, String method) throws ReflectiveOperationException {
        Method m = o.getClass().getMethod(method);
        return ((Number) m.invoke(o)).intValue();
    }

    private static void warnOnce(String what, Throwable e) {
        if (!warned) {
            warned = true;
            LogUtil.info(GaugeSampler.class.getName(), "BlackBox can't read the " + what + " for the Server load strip: " + e);
        }
    }

    private static final class Sampled {
        final long since = System.currentTimeMillis();
        long metaCheckedAt;
    }
}
