package com.astrolauncher.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Best-effort cross-process lock (an OS file lock). Used so two launcher processes never rotate the
 * same single-use refresh token at the same time. If the lock can't be had within the timeout the
 * caller simply proceeds unlocked: this is a safeguard, never a reason to fail or hang.
 */
public final class ProcessLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private ProcessLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    /** Waits up to {@code timeoutMs} for the lock on {@code file}; returns a (possibly no-op) handle, never null. */
    public static ProcessLock acquire(Path file, long timeoutMs) {
        FileChannel ch = null;
        try {
            Files.createDirectories(file.getParent());
            ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                try {
                    FileLock l = ch.tryLock();
                    if (l != null) return new ProcessLock(ch, l);
                } catch (OverlappingFileLockException same) {
                    break; // this JVM already holds it: nothing more to wait for
                }
                if (System.currentTimeMillis() >= deadline) break;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to a no-op handle
        }
        if (ch != null) { try { ch.close(); } catch (IOException ignored) { } }
        return new ProcessLock(null, null);
    }

    @Override
    public void close() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }
}
