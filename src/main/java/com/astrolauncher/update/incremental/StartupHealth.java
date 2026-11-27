package com.astrolauncher.update.incremental;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.update.AppVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The explicit "I started fine" signal. When the update helper relaunches the launcher it sets
 * {@code ASTRO_UPDATE_HEALTH_FILE}; once the main window is up the launcher writes its version there. The
 * helper commits the update only after seeing it, and rolls back if it never appears.
 */
public final class StartupHealth {

    public static final String ENV = "ASTRO_UPDATE_HEALTH_FILE";

    private StartupHealth() {}

    public static void signalIfRequested() {
        String target = System.getenv(ENV);
        if (target == null || target.isBlank()) return;
        try {
            Path file = Path.of(target);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = "{\"version\":\"" + AppVersion.CURRENT + "\",\"pid\":" + ProcessHandle.current().pid() + "}";
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            AppLogger.get().info("StartupHealth", "Startup confirmed to the update helper");
        } catch (Exception e) {
            AppLogger.get().warn("StartupHealth", "Could not write the startup signal: " + e.getMessage());
        }
    }
}
