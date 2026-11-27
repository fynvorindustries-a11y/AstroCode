package com.astrolauncher.store;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.LauncherSettings;
import com.astrolauncher.util.PathResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Loads the Astro client (HUD, keystrokes, combat info, visuals, etc.) straight into the game JVM
 * through the {@code -javaagent:} argument, <b>without it ever being a mod</b>.
 *
 * <p>Nothing is copied into {@code mods} or {@code resourcepacks}. This means it works identically
 * on vanilla, Fabric, Forge, NeoForge and Quilt — it has no dependency on any mod loader being
 * present at all, because the JVM itself loads it via {@code Premain-Class} before Minecraft's own
 * main() ever runs. See {@link CapeSupport} for the same approach already used for Astro capes.
 *
 * <ul>
 *   <li>{@link #ensureAgent()} unpacks the bundled jar (SHA-256 verified) into
 *       {@code <launcher data>/agent/} on first use.</li>
 *   <li>{@link #agentArgument(LauncherSettings)} returns the {@code -javaagent:} argument to add
 *       to the game's JVM arguments, or {@code null} when the client is switched off or the bundled
 *       jar could not be unpacked.</li>
 * </ul>
 */
public final class AstroClientSupport {

    private static final String TAG = "AstroClientSupport";
    private static final String JAR_PREFIX = "astro-universal-";
    // Deliberately distinct from CapeSupport's pointer file name: both classes share the same
    // "<launcher data>/agent/" directory, so a shared name would make each overwrite the other's pointer.
    private static final String POINTER_FILE = "client-current.txt";
    private static final Pattern SAFE_JAR_NAME = Pattern.compile("^astro-universal-[A-Za-z0-9._-]+\\.jar$");

    /** The build that ships inside the launcher. */
    static final String BUNDLED_VERSION = "0.8.0";
    static final String BUNDLED_SHA256 = "f6a45a26800f2b484b5949ebd358b056fdd90fd572367c61ec50767109dee03d";
    private static final String BUNDLED_RESOURCE = "/agent/" + JAR_PREFIX + BUNDLED_VERSION + ".jar";
    /** The jar is still stored in the launcher resources under its original "avero-" name. */
    private static final String BUNDLED_RESOURCE_LEGACY = "/agent/avero-universal-" + BUNDLED_VERSION + ".jar";

    /** Opens the bundled agent under either name (null if neither is packaged). */
    private static java.io.InputStream openBundled() {
        java.io.InputStream in = AstroClientSupport.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in != null ? in : AstroClientSupport.class.getResourceAsStream(BUNDLED_RESOURCE_LEGACY);
    }

    private AstroClientSupport() {}

    /** Short-lived token for the in-game social features (friends, party, chat). Kept in memory only. */
    private static volatile String socialToken;
    private static volatile long socialTokenValidUntilMs;
    private static final long SOCIAL_TTL_SECONDS = 6L * 3600;

    /**
     * Gets a launch token with the "social" scope for the agent. Completes (never exceptionally); without a token the
     * agent still loads and works offline (chat time stamps), and says "not set up" for social commands.
     */
    public static CompletableFuture<Void> prepare(LauncherSettings settings) {
        try {
            if (settings == null || !settings.astroClientEnabled) return CompletableFuture.completedFuture(null);
            AstroId id = AstroId.get();
            if (!id.isSignedIn() || id.api().isDemo()) { socialToken = null; return CompletableFuture.completedFuture(null); }
            if (socialToken != null && System.currentTimeMillis() < socialTokenValidUntilMs) return CompletableFuture.completedFuture(null);
            return id.api().launcherSession(SOCIAL_TTL_SECONDS, "cosmetics:read", "social")
                    .orTimeout(8, TimeUnit.SECONDS)
                    .thenAccept(s -> {
                        socialToken = s.token;
                        socialTokenValidUntilMs = System.currentTimeMillis() + (SOCIAL_TTL_SECONDS - 600) * 1000L;
                    })
                    .exceptionally(e -> {
                        socialToken = null;
                        AppLogger.get().warn(TAG, "No social token for the client agent (social features stay off this launch): " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Social token request failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public static String agentArgument(LauncherSettings settings) {
        return agentArgument(settings, null);
    }

    /**
     * The complete JVM argument that loads the client agent, e.g.
     * {@code -javaagent:C:\...\astro-universal-0.2.0.jar=api=https://.../api/v1,token=...,name=Steve,self=<uuid>};
     * or {@code null} when the client is disabled in settings, or the agent jar could not be made available.
     * The options are only added when a social token is available (see {@link #prepare}).
     */
    public static String agentArgument(LauncherSettings settings, com.astrolauncher.model.Account account) {
        if (settings == null || !settings.astroClientEnabled) return null;
        Path jar = ensureAgent();
        if (jar == null) return null;
        String path = jar.toAbsolutePath().toString();
        StringBuilder sb = new StringBuilder("-javaagent:").append(path);
        try {
            String base = AstroId.get().api().baseUrl();
            String token = socialToken;
            // '=' in the path or ',' in a value would split the agent's option string in the wrong place
            if (base != null && token != null && path.indexOf('=') < 0 && token.indexOf(',') < 0 && base.indexOf(',') < 0) {
                sb.append("=api=").append(base).append(",token=").append(token);
                if (account != null && account.username != null && account.username.matches("[A-Za-z0-9_]{1,16}"))
                    sb.append(",name=").append(account.username);
                if (account != null && account.minecraftUuid != null) {
                    String self = account.minecraftUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
                    if (self.matches("[0-9a-f]{32}")) sb.append(",self=").append(self);
                }
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not add the social options to the client agent: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * The agent jar to use: the installed copy if present, otherwise the bundled build is unpacked
     * into {@code <launcher data>/agent/} and becomes the installed one. Null only if the bundled
     * resource is missing or fails its SHA-256 check and nothing is already installed.
     */
    static Path ensureAgent() {
        Path installed = installedAgent();
        if (installed != null) return installed;
        try (java.io.InputStream in = openBundled()) {
            if (in == null) {
                AppLogger.get().warn(TAG, "The bundled Astro client agent is missing from the launcher.");
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (!BUNDLED_SHA256.equals(sha256(bytes))) {
                AppLogger.get().warn(TAG, "The bundled Astro client agent failed its SHA-256 check; not using it.");
                return null;
            }
            Path dir = agentDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(JAR_PREFIX + BUNDLED_VERSION + ".jar");
            if (!(Files.isRegularFile(target) && BUNDLED_SHA256.equals(sha256(Files.readAllBytes(target))))) {
                Path tmp = dir.resolve(target.getFileName() + ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                AppLogger.get().info(TAG, "Unpacked the bundled Astro client agent " + BUNDLED_VERSION);
            }
            Files.writeString(dir.resolve(POINTER_FILE), target.getFileName().toString(), StandardCharsets.UTF_8);
            removeOtherBuilds(dir, target.getFileName().toString());
            return target;
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not unpack the bundled Astro client agent: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ internals

    private static Path agentDir() {
        return PathResolver.launcherDataDir().resolve("agent");
    }

    /** The agent jar recorded by the last successful unpack, or null. */
    private static Path installedAgent() {
        try {
            Path pointer = agentDir().resolve(POINTER_FILE);
            if (!Files.isRegularFile(pointer)) return null;
            String name = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (!SAFE_JAR_NAME.matcher(name).matches()) return null;
            Path jar = agentDir().resolve(name);
            return Files.isRegularFile(jar) ? jar : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void removeOtherBuilds(Path dir, String keepName) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, JAR_PREFIX + "*.jar")) {
            for (Path p : ds) {
                if (!p.getFileName().toString().equals(keepName)) Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not clean old client agent builds: " + e.getMessage());
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}