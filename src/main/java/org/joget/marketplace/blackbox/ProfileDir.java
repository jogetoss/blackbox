package org.joget.marketplace.blackbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SetupManager;

/**
 * The blackbox folder ({@code <base>/blackbox}) of the profile that work belongs to. With virtual hosts each profile
 * has its own base folder, and Joget picks it from the thread's profile: a request thread has the request's, but the
 * background threads (traces, other users' traces, Glowroot data, gauges) only have the one they inherited when the
 * plugin started. So a capture remembers its folder when a request opens it ({@link #current()}), and its background
 * work runs {@link #in} that folder: {@link ServerCapture#store()}, {@link BlackBoxPlugin#store()} and the trace retry
 * folder then resolve to it. Work that is not about one capture (restart recovery, retry requests) goes through every
 * profile's folder ({@link ServerCapture#checkDirs}).
 */
final class ProfileDir {

    private static final ThreadLocal<Path> CURRENT = new ThreadLocal<>();

    /** Work that may throw a checked exception. */
    interface Work<E extends Exception> {
        void run() throws E;
    }

    /** A watched capture: since when, and the folder its background work runs in. */
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

    /** The folder set by {@link #in} on this thread, else the one of the thread's Joget profile. */
    static Path current() {
        Path dir = CURRENT.get();
        return dir != null ? dir : Paths.get(SetupManager.getBaseDirectory(), "blackbox");
    }

    /**
     * {@link #in}, logging what fails instead of throwing: one capture or profile (an unreadable folder, a file access
     * a cloud install refuses) must not end a scheduled job, which would then never run again.
     */
    static void safely(Class<?> who, String what, Path dir, Work<? extends Exception> work) {
        try {
            in(dir, work);
        } catch (Exception | LinkageError e) {
            LogUtil.warn(who.getName(), "Could not " + what + (dir != null ? " in " + dir : "") + ": " + e);
        }
    }

    /** Every profile's blackbox folder (ServerCapture.checkDirs); none when they can't be listed. */
    static java.util.List<Path> all(Class<?> who) {
        try {
            return ServerCapture.checkDirs.get();
        } catch (RuntimeException e) {
            LogUtil.warn(who.getName(), "Could not list the profiles' blackbox folders: " + e);
            return java.util.Collections.emptyList();
        }
    }

    /** Runs work with {@link #current()} set to dir (null: unchanged), then puts the previous one back. */
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
