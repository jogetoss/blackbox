package org.joget.marketplace.blackbox;

import java.lang.reflect.Method;
import org.joget.commons.util.LogUtil;

/**
 * Glowroot's agent API (org.glowroot.agent.api.Glowroot, inside glowroot.jar), reached by reflection: tags a recorded
 * request with the recording id. Glowroot still decides which requests it keeps a trace of (slow or failed); the ones
 * it keeps carry the tag, so the traces of one recording can be found by it. {@link #keep()} makes it keep the current
 * one too (a recorded request that logged an ERROR). Does nothing when Glowroot is not running.
 */
final class GlowrootTag {

    static final String ATTRIBUTE = "BlackBox recording";

    private static volatile boolean looked;
    private static Method attribute;
    private static Method slowThreshold; // setTransactionSlowThreshold(long, TimeUnit)

    private GlowrootTag() {
    }

    /** Called on the request's thread, inside Glowroot's transaction for it. */
    static void tag(String recordingId) {
        if (!looked) {
            find();
        }
        if (attribute == null) {
            return;
        }
        try {
            attribute.invoke(null, ATTRIBUTE, recordingId);
        } catch (Exception | LinkageError e) {
            LogUtil.debug(GlowrootTag.class.getName(), "Glowroot API call failed: " + e);
        }
    }

    /**
     * Called on the request's thread: a 0 ms slow threshold for this request only, so Glowroot keeps its trace even
     * when it was fast and returned 200 (an error caught and logged, not thrown, is not an error to Glowroot).
     */
    static void keep() {
        if (!looked) {
            find();
        }
        if (slowThreshold == null) {
            return;
        }
        try {
            slowThreshold.invoke(null, 0L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception | LinkageError e) {
            LogUtil.debug(GlowrootTag.class.getName(), "Glowroot API call failed: " + e);
        }
    }

    private static synchronized void find() {
        if (looked) {
            return;
        }
        // glowroot.jar is on the bootstrap or system class path, never inside this bundle
        ClassLoader[] loaders = {null, ClassLoader.getSystemClassLoader(), Thread.currentThread().getContextClassLoader()};
        String tried = "";
        for (ClassLoader cl : loaders) {
            try {
                Class<?> c = Class.forName("org.glowroot.agent.api.Glowroot", true, cl);
                attribute = c.getMethod("addTransactionAttribute", String.class, String.class);
                try {
                    slowThreshold = c.getMethod("setTransactionSlowThreshold", long.class, java.util.concurrent.TimeUnit.class);
                } catch (NoSuchMethodException e) {
                    slowThreshold = null; // older Glowroot: only slow or failed requests have traces
                }
                LogUtil.info(GlowrootTag.class.getName(), "Glowroot API found (" + (cl == null ? "bootstrap" : cl) + "): recorded requests are tagged");
                break;
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
                tried += " " + (cl == null ? "bootstrap" : cl.getClass().getSimpleName()) + ": " + e;
            }
        }
        if (attribute == null) {
            LogUtil.info(GlowrootTag.class.getName(), "Glowroot API not found, recorded requests are not tagged:" + tried);
        }
        looked = true;
    }
}
