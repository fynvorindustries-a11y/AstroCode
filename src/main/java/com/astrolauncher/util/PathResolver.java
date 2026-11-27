package com.astrolauncher.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Resolves OS-appropriate application-data and default Minecraft directories
 * at runtime. Never hard-codes a single path.
 */
public final class PathResolver {

    private PathResolver() {}

    /**
     * The launcher was renamed from AveroLauncher to ASTRO. Everything the app reads or
     * writes moved from {@code .averolauncher} to {@code .astrolauncher}. Existing installs must
     * not look "signed out" or "reset" after an update just because of the rename, so the first
     * time the new folder is needed and doesn't exist yet, the old one (if present) is migrated
     * into it, once, before anything else touches the directory.
     */
    private static volatile boolean migrationChecked = false;

    private static synchronized void ensureMigrated(Path newDir) {
        if (migrationChecked) return;
        migrationChecked = true;
        try {
            if (Files.exists(newDir)) return; // already on the new folder; nothing to do
            Path oldDir = newDir.resolveSibling(".averolauncher");
            if (!Files.exists(oldDir)) return; // fresh install; nothing to migrate
            Files.createDirectories(newDir.getParent());
            try {
                Files.move(oldDir, newDir);
            } catch (IOException renameFailed) {
                // Different volume, or something holding a handle open on the old folder — copy
                // instead and leave the old folder in place rather than risk losing data.
                copyTree(oldDir, newDir);
            }
        } catch (Exception e) {
            // Never block startup over the migration; worst case the player sees a fresh profile
            // once instead of a crash.
            System.err.println("[PathResolver] AveroLauncher -> ASTRO data migration failed: " + e.getMessage());
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    public static boolean isWindows() {
        return os().contains("win");
    }

    public static boolean isMac() {
        return os().contains("mac");
    }

    public static boolean isLinux() {
        return os().contains("nux") || os().contains("nix");
    }

    /**
     * Directory where ASTRO stores its own configuration, cache and logs.
     * Uses a dot-prefixed hidden folder (like .tlauncher, .minecraft) so it is
     * consistent and discoverable by the user.
     *
     * Windows: %APPDATA%\.astrolauncher
     * macOS:   ~/Library/Application Support/.astrolauncher
     * Linux:   ~/.astrolauncher
     *
     * IMPORTANT: this path is intentionally identical to defaultMinecraftDir() so
     * that settings.json, the metadata cache, and all game files (versions/,
     * libraries/, assets/, mods/, etc) live under the same single root directory.
     * Previously this returned "ASTRO" (no dot) which caused a path mismatch
     * with defaultMinecraftDir() (".astrolauncher"), so settings were saved to one
     * folder and never found on restart.
     */
    public static Path launcherDataDir() {
        Path dir = resolveDataDir();
        ensureMigrated(dir);
        return dir;
    }

    private static Path resolveDataDir() {
        // Explicit override (used by tests so they never touch the real profile, even on Windows
        // where %APPDATA% would otherwise win over a changed user.home).
        String override = System.getProperty("astro.data.dir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home", ".");
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, ".astrolauncher");
            }
            return Paths.get(home, "AppData", "Roaming", ".astrolauncher");
        } else if (isMac()) {
            return Paths.get(home, "Library", "Application Support", ".astrolauncher");
        } else {
            return Paths.get(home, ".astrolauncher");
        }
    }

    /**
     * The default game-files directory ASTRO uses for its own,
     * isolated Minecraft installation (versions/, libraries/, assets/, mods/,
     * saves/, etc). Deliberately separate from the shared .minecraft folder
     * so ASTRO never reads, writes, or corrupts files belonging to
     * the official launcher or any other launcher. The user may still
     * override this in Settings to point at a shared .minecraft folder if
     * they explicitly choose to.
     *
     * Windows: %APPDATA%\.astrolauncher
     * macOS:   ~/Library/Application Support/.astrolauncher
     * Linux:   ~/.astrolauncher
     */
    public static Path defaultMinecraftDir() {
        // Same physical folder as launcherDataDir(); routing through it also means the migration
        // check above runs no matter which of the two callers touches the directory first.
        return launcherDataDir();
    }
}
