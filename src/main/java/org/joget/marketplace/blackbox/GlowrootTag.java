package org.joget.marketplace.blackbox;

import java.lang.reflect.Method;
import org.joget.commons.util.LogUtil;

final class GlowrootTag {

    static final String ATTRIBUTE = "BlackBox recording";

    private static volatile boolean looked;
    private static Method attribute;
    private static Method slowThreshold;

    private GlowrootTag() {
    }

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
        ClassLoader[] loaders = {null, ClassLoader.getSystemClassLoader(), Thread.currentThread().getContextClassLoader()};
        String tried = "";
        for (ClassLoader cl : loaders) {
            try {
                Class<?> c = Class.forName("org.glowroot.agent.api.Glowroot", true, cl);
                attribute = c.getMethod("addTransactionAttribute", String.class, String.class);
                try {
                    slowThreshold = c.getMethod("setTransactionSlowThreshold", long.class, java.util.concurrent.TimeUnit.class);
                } catch (NoSuchMethodException e) {
                    slowThreshold = null;
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
