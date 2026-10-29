package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The file lock the stores take around a read-change-write of meta.json (RecordingStore, ServerCaptureStore). It is
 * what keeps nodes sharing the folder apart, and also two copies of the plugin in one JVM (the old bundle's export job
 * during a plugin reload): the stores' JVM locks are per copy, file locks are per JVM.
 */
final class FileLocks {

    /** How long to wait for a lock another plugin copy in this JVM holds; tests shorten it. */
    static long waitMs = 10_000;

    /** Work done holding the lock. */
    interface Work<T, E extends Exception> {
        T run() throws IOException, E;
    }

    private FileLocks() {
    }

    /**
     * Runs work holding the file lock on lockFile. Without locks on this file system (an IOException or
     * UnsupportedOperationException from lock()) work runs with the caller's JVM lock only. Throws when the lock can't
     * be had: held by another plugin copy longer than waitMs, or the wait interrupted. A pending interrupt is cleared
     * for the work (an interrupted thread can't lock) and set again after it.
     */
    static <T, E extends Exception> T locked(Path lockFile, Work<T, E> work) throws IOException, E {
        boolean interrupted = Thread.interrupted();
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock fl = lock(ch, lockFile)) {
            return work.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static FileLock lock(FileChannel ch, Path lockFile) throws IOException {
        long until = System.currentTimeMillis() + waitMs;
        while (true) {
            try {
                return ch.lock();
            } catch (FileLockInterruptionException | ClosedChannelException e) {
                // Interrupted (or ClosedByInterruptException), not "no locks": both are IOExceptions, so caught first
                throw e;
            } catch (IOException | UnsupportedOperationException e) {
                return null; // no locks on this file system
            } catch (OverlappingFileLockException e) {
                if (System.currentTimeMillis() > until) {
                    throw new IOException("Locked by another copy of the plugin for over " + waitMs / 1000 + " s: " + lockFile);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted waiting for a file lock");
                }
            }
        }
    }
}
