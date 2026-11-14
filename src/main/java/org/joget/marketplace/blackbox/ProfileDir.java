package org.joget.marketplace.blackbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SetupManager;

final class ProfileDir {

    private static final ThreadLocal<Path> CURRENT = new ThreadLocal<>();

    interface Work<E extends Exception> {
        void run() throws E;
    }

    static final class Watch {
        final long since;
        final Path dir;

        Watch(long since, Path dir) {
            this.since = since;
            this.dir = dir;
        }
    }

    private ProfileDir() {
    }

    static Path current() {
        Path dir = CURRENT.get();
        return dir != null ? dir : Paths.get(SetupManager.getBaseDirectory(), "blackbox");
    }

    static final long WARN_EVERY_MS = 10 * 60_000L;
    private static final java.util.Map<String, Long> WARNED = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean safely(Class<?> who, String what, Path dir, Work<? extends Exception> work) {
        try {
            in(dir, work);
            return true;
        } catch (Exception | LinkageError e) {
            warn(who, "Could not " + what + (dir != null ? " in " + dir : "") + ": " + e);
            return false;
        }
    }

    private static void warn(Class<?> who, String message) {
        long now = System.currentTimeMillis();
        Long last = WARNED.get(message);
        if (last == null || now - last >= WARN_EVERY_MS) {
            WARNED.put(message, now);
            WARNED.entrySet().removeIf(w -> now - w.getValue() >= WARN_EVERY_MS && !w.getKey().equals(message));
            LogUtil.warn(who.getName(), message);
        }
    }

    static java.util.List<Path> all(Class<?> who) {
        try {
            return ServerCapture.checkDirs.get();
        } catch (RuntimeException e) {
            warn(who, "Could not list the profiles' blackbox folders: " + e);
            return java.util.Collections.emptyList();
        }
    }

    static <E extends Exception> void in(Path dir, Work<E> work) throws E {
        if (dir == null) {
            work.run();
            return;
        }
        Path before = CURRENT.get();
        CURRENT.set(dir);
        try {
            work.run();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }
}
