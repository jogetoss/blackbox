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

final class FileLocks {

    static long waitMs = 10_000;

    interface Work<T, E extends Exception> {
        T run() throws IOException, E;
    }

    private FileLocks() {
    }

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
                throw e;
            } catch (IOException | UnsupportedOperationException e) {
                return null;
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
