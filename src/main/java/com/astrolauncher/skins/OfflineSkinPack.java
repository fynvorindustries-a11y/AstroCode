package com.astrolauncher.skins;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes a library skin show up in-game for an <b>offline</b> account.
 *
 * <p>Minecraft only downloads real skins from Mojang, so an offline player always gets one of the built-in
 * default skins (Steve, Alex, Ari, ...), chosen from the UUID. We therefore write a tiny resource pack,
 * {@code resourcepacks/AstroSkin.zip}, that replaces <i>every</i> default skin with the chosen one, and switch it
 * on in {@code options.txt}. Result: the player sees their skin in singleplayer, on LAN and on offline-mode
 * servers, with no mod and no agent. When the account has no offline skin the pack is switched off again.
 *
 * <p>Limits: it is client-side only (other players see whatever the server gives them), and the arm model
 * (classic / slim) follows the default skin Minecraft picked for the UUID. Works on Minecraft 1.13+.
 */
public final class OfflineSkinPack {

    private static final String TAG = "OfflineSkinPack";
    private static final String PACK_FILE = "AstroSkin.zip";
    private static final String PACK_ENTRY = "file/" + PACK_FILE;
    /** Every default skin since 1.19.3 (the UUID picks one of these nine), plus the pre-1.19.3 names. */
    private static final List<String> DEFAULTS = List.of("steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri");

    private OfflineSkinPack() {}

    /** Same as {@link #sync(Path, Account, String)} when the game version is unknown (newest-format guess). */
    public static void sync(Path gameDir, Account account) {
        sync(gameDir, account, null);
    }

    /**
     * Brings the pack and options.txt in line with the account. Never throws: a failure only logs.
     *
     * @param versionId the version being launched ("1.20.1", "fabric-loader-0.15.11-1.20.1", "1.20.1-forge-47.2.0", ...)
     */
    public static void sync(Path gameDir, Account account, String versionId) {
        try {
            if (gameDir == null) return;
            boolean wants = account != null && account.type == Account.Type.OFFLINE
                    && account.offlineSkinId != null && !account.offlineSkinId.isBlank();
            Path pack = gameDir.resolve("resourcepacks").resolve(PACK_FILE);
            if (wants) {
                Path png = new SkinLibrary().pngPath(account.offlineSkinId);
                if (Files.isRegularFile(png)) {
                    write(pack, Files.readAllBytes(png), packFormatFor(versionId));
                    setEnabled(gameDir.resolve("options.txt"), true);
                    AppLogger.get().info(TAG, "Offline skin pack ready for " + account.username + " (game version \"" + versionId
                            + "\", pack_format " + packFormatFor(versionId) + ", enabled in " + gameDir.resolve("options.txt") + ")");
                    return;
                }
            }
            setEnabled(gameDir.resolve("options.txt"), false);
            Files.deleteIfExists(pack);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not set up the offline skin: " + e.getMessage());
        }
    }

    private static void write(Path pack, byte[] png, int format) throws IOException {
        Files.createDirectories(pack.getParent());
        Path tmp = pack.resolveSibling(PACK_FILE + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
            // Before 1.20.2 only pack_format counts, and a pack whose format doesn't match the game is silently dropped
            // from options.txt at start-up (that is why the skin never showed). 1.20.2+ also read the supported range.
            put(zip, "pack.mcmeta", ("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Astro offline skin\","
                    + "\"supported_formats\":{\"min_inclusive\":1,\"max_inclusive\":999},"
                    + "\"min_format\":1,\"max_format\":999}}").getBytes(StandardCharsets.UTF_8));
            for (String n : DEFAULTS) {
                put(zip, "assets/minecraft/textures/entity/player/wide/" + n + ".png", png);
                put(zip, "assets/minecraft/textures/entity/player/slim/" + n + ".png", png);
            }
            put(zip, "assets/minecraft/textures/entity/steve.png", png);   // Minecraft before 1.19.3
            put(zip, "assets/minecraft/textures/entity/alex.png", png);
        }
        Files.move(tmp, pack, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The resource-pack format the game of this version expects (see the Minecraft wiki "Pack format" table). */
    static int packFormatFor(String versionId) {
        if (versionId == null) return 34;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\d.])1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?![\\d])").matcher(versionId);
        if (!m.find()) return 34;                       // snapshots etc.: newest guess, the supported range covers the rest
        int minor = Integer.parseInt(m.group(1));
        int patch = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (minor <= 12) return 3;
        if (minor <= 14) return 4;
        if (minor == 15 || (minor == 16 && patch <= 1)) return 5;
        if (minor == 16) return 6;
        if (minor == 17) return 7;
        if (minor == 18) return 8;
        if (minor == 19) return patch <= 2 ? 9 : patch == 3 ? 12 : 13;
        if (minor == 20) return patch <= 1 ? 15 : patch == 2 ? 18 : patch <= 4 ? 22 : 32;
        if (minor == 21) return patch <= 1 ? 34 : patch <= 3 ? 42 : patch == 4 ? 46 : patch == 5 ? 55 : patch == 6 ? 63 : 64;
        return 34;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** Adds (last = highest priority) or removes our pack in the {@code resourcePacks:[...]} line of options.txt. */
    private static void setEnabled(Path options, boolean enabled) throws IOException {
        List<String> lines = Files.isRegularFile(options)
                ? new java.util.ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                : new java.util.ArrayList<>();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("resourcePacks:")) { idx = i; break; }

        JsonArray packs = new JsonArray();
        if (idx >= 0) {
            try {
                JsonElement e = JsonParser.parseString(lines.get(idx).substring("resourcePacks:".length()));
                if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (!PACK_ENTRY.equals(x.getAsString())) packs.add(x);
            } catch (Exception ignored) { packs = new JsonArray(); }
        } else {
            packs.add("vanilla");
        }
        if (idx < 0 && !enabled) return;                 // nothing to remove, don't create a file for it
        if (enabled) packs.add(PACK_ENTRY);
        String line = "resourcePacks:" + packs;
        if (idx >= 0) lines.set(idx, line); else lines.add(line);

        // Belt and braces: a pack listed here is kept by the game even if it judges the pack format "incompatible".
        int bad = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("incompatibleResourcePacks:")) { bad = i; break; }
        JsonArray incompatible = new JsonArray();
        if (bad >= 0) {
            try {
                JsonElement e = JsonParser.parseString(lines.get(bad).substring("incompatibleResourcePacks:".length()));
                if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (!PACK_ENTRY.equals(x.getAsString())) incompatible.add(x);
            } catch (Exception ignored) { incompatible = new JsonArray(); }
        }
        if (enabled) incompatible.add(PACK_ENTRY);
        if (bad >= 0 || enabled) {
            String badLine = "incompatibleResourcePacks:" + incompatible;
            if (bad >= 0) lines.set(bad, badLine); else lines.add(badLine);
        }
        Files.write(options, lines, StandardCharsets.UTF_8);
    }
}
