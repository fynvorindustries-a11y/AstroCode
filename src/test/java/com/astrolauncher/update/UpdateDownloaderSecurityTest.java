package com.astrolauncher.update;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the critical finding in PROJECT_AUDIT.md (S-1): the launcher used to download a release
 * installer with no hash/signature verification and then run it silently. The downloader must refuse, and it
 * must refuse BEFORE any network access (the URL below is unroutable on purpose).
 */
class UpdateDownloaderSecurityTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void refusesToDownloadAnUnsignedInstaller() {
        UpdateInfo info = new UpdateInfo("9.9.9", "Astro 9.9.9", "https://example.invalid/release",
                "notes", "https://203.0.113.1/AstroSetup.exe", "AstroSetup.exe", 1234);
        assertTrue(info.hasDownloadableAsset(), "precondition: the release advertises an installer asset");

        UpdateDownloader d = new UpdateDownloader(DIRECT);
        Throwable t = assertThrows(Throwable.class, () -> d.download(info, null).get());

        Throwable cause = t instanceof ExecutionException || t instanceof CompletionException ? t.getCause() : t;
        assertInstanceOf(SecurityException.class, cause);
        assertTrue(cause.getMessage().toLowerCase().contains("signed"), cause.getMessage());
    }
}
