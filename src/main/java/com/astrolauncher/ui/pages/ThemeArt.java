package com.astrolauncher.ui.pages;

import com.astrolauncher.logging.AppLogger;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Rectangle2D;
import javafx.scene.layout.StackPane;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Artwork + grouping for Astro Labs backgrounds in the Store.
 *
 * <p>Artwork: every background has a JPG bundled in {@code /images/themes/<slug>.jpg}, so the store
 * reads those first and only falls back to the server's {@code previewUrl} when there is no local file
 * (a brand-new background that shipped to the server before a launcher update).
 *
 * <p>Categories: the server catalog has no category field, so backgrounds are grouped here by slug.
 * Anything not listed (a new server-side background) lands in {@link Category#MORE}, so nothing ever
 * disappears from the store because of a missing entry.
 */
final class ThemeArt {

    private ThemeArt() {}

    private static final String TAG = "ThemeArt";

    enum Category {
        HEROES("Heroes & Mages"),
        SKY("Sky & Sea"),
        NATURE("Cozy & Nature"),
        NIGHT("Night & Stars"),
        ANIME("Anime & Sakura"),
        MORE("More");

        final String label;
        Category(String label) { this.label = label; }
    }

    private static final Map<String, Category> BY_SLUG = new LinkedHashMap<>();
    static {
        put(Category.HEROES, "ember-mage", "fire-and-wind", "tidal-mage", "void-conjurer", "elemental-clash",
                "frost-seraph", "sunrise-kneel", "archangel", "halo-garden", "moonlit-dream", "diamond-dawn");
        put(Category.SKY, "cube-sky", "floating-ruins", "sky-cathedral", "whale-sphere", "aqua-express",
                "hilltop-voyage", "holy-blade", "skyward-explorer");
        put(Category.NATURE, "forest-spirit", "twilight-hill", "starlight-grove", "sky-watchers", "summer-nap",
                "lakeside-reader", "study-desk");
        put(Category.NIGHT, "starlit-lilies", "midnight-window", "clockwork-night", "cosmic-pistol");
        put(Category.ANIME, "sakura-pass", "sakura-shrine", "golden-gaze", "petal-sky");
    }

    private static void put(Category c, String... slugs) {
        for (String s : slugs) BY_SLUG.put(s, c);
    }

    // ── capes ───────────────────────────────────────────────────────────────

    enum CapeCategory {
        FIRE("Fire & Sun"),
        NIGHT("Night & Space"),
        SKY("Sky & Storm"),
        MYTHIC("Mythic"),
        MORE("More");

        final String label;
        CapeCategory(String label) { this.label = label; }
    }

    private static final Map<String, CapeCategory> CAPES = new LinkedHashMap<>();
    static {
        for (String k : new String[] { "crimson", "ember", "sunflare", "bloodmoon", "frostfire", "dragonrider" }) CAPES.put(k, CapeCategory.FIRE);
        for (String k : new String[] { "shadow", "moonveil", "nightfall", "darkstar", "nebula", "starborn", "raven", "phantom" }) CAPES.put(k, CapeCategory.NIGHT);
        for (String k : new String[] { "storm", "thunder", "wildwind", "silverwing", "aurora", "celestial", "frozen" }) CAPES.put(k, CapeCategory.SKY);
        for (String k : new String[] { "mystic", "eternal", "zenin" }) CAPES.put(k, CapeCategory.MYTHIC);
    }

    /** "Wildwind_v2" / "wildwind_2" / "wild-wind" all key as "wildwind"; same idea the API uses to pair ids. */
    private static String capeKey(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\-]?v?\\d+$", "").replaceAll("[^a-z0-9]", "");
    }

    static CapeCategory capeCategoryOf(String id, String name) {
        CapeCategory c = CAPES.get(capeKey(id));
        if (c == null) c = CAPES.get(capeKey(name));
        return c != null ? c : CapeCategory.MORE;
    }

    /** Category tabs in display order (MORE is only shown when something actually falls into it). */
    static List<Category> order() { return List.of(Category.values()); }

    static Category categoryOf(String id, String name) {
        Category c = BY_SLUG.get(slug(id));
        if (c == null) c = BY_SLUG.get(slug(name));
        return c != null ? c : Category.MORE;
    }

    /** "bg_forest-spirit" -> "forest-spirit"; "Fire & Wind" -> "fire-and-wind". */
    static String slug(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("bg_")) t = t.substring(3);
        t = t.replace("&", " and ").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return t;
    }

    // ── images ──────────────────────────────────────────────────────────────

    private static final Map<String, Image> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> NO_LOCAL = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The bundled image for this background, decoded once at roughly tile size; null if none is bundled. */
    private static Image local(String id, String name, double w, double h) {
        for (String key : new String[] { slug(id), slug(name) }) {
            if (key.isEmpty() || NO_LOCAL.contains(key)) continue;
            Image cached = CACHE.get(key);
            if (cached != null) return cached;
            String path = "/images/themes/" + key + ".jpg";
            java.net.URL res = ThemeArt.class.getResource(path);
            if (res == null) { NO_LOCAL.add(key); continue; }
            try {
                // Decode at 2x the tile (crisp on HiDPI) rather than full size: 28 full-size JPGs would be
                // a lot of memory for 176px tiles. Loads in the background so the page never stalls.
                Image img = new Image(res.toExternalForm(), w * 2, h * 2, true, true, true);
                CACHE.put(key, img);
                return img;
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not load " + path + ": " + e.getMessage());
                NO_LOCAL.add(key);
            }
        }
        return null;
    }

    /**
     * Tile art of exactly w x h: the local image (cover-cropped), else the remote preview, else null so the
     * caller keeps its gradient.
     */
    static Node image(String id, String name, String previewUrl, double w, double h) {
        Image img = local(id, name, w, h);
        if (img == null && previewUrl != null && !previewUrl.isBlank()) {
            try {
                img = new Image(previewUrl, w * 2, h * 2, true, true, true);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Bad preview URL for " + id + ": " + e.getMessage());
            }
        }
        if (img == null) return null;

        ImageView iv = new ImageView(img);
        iv.setSmooth(true);
        iv.setPreserveRatio(false);
        iv.setFitWidth(w);
        iv.setFitHeight(h);
        final Image fi = img;
        Runnable crop = () -> {
            double iw = fi.getWidth(), ih = fi.getHeight();
            if (iw <= 0 || ih <= 0) return;
            double scale = Math.max(w / iw, h / ih);          // "cover"
            double vw = w / scale, vh = h / scale;
            iv.setViewport(new Rectangle2D((iw - vw) / 2, (ih - vh) / 2, vw, vh));
        };
        if (fi.getProgress() >= 1.0) crop.run();
        else fi.progressProperty().addListener((o, a, b) -> { if (b.doubleValue() >= 1.0) crop.run(); });

        StackPane box = new StackPane(iv);
        box.setMinSize(w, h);
        box.setPrefSize(w, h);
        box.setMaxSize(w, h);
        return box;
    }
}
