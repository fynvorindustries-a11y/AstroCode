package com.astrolauncher.ui.pages;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.rank.RankService;
import com.astrolauncher.rank.RankSnapshot;
import com.astrolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Card backgrounds for Passport and Wrapped, and the Astro Credits that pay for some of them.
 *
 * <p>How a theme is unlocked:
 * <ul>
 *   <li>{@code FREE}: always available.</li>
 *   <li>{@code PLAYTIME}: play this many verified hours.</li>
 *   <li>{@code RANK}: reach this rank tier (0 = Stone ... 8 = Celestial).</li>
 *   <li>{@code CREDITS}: spend Astro Credits.</li>
 *   <li>{@code STORE}: sold in the Astro Store (the server confirms ownership; {@code need} = price in cents).</li>
 * </ul>
 *
 * <p>Astro Credits come from verified playtime and finished quests, so they inherit the rank
 * system's AFK checks. How many have been spent is kept in a local file, which is fine for
 * cosmetics but is not tamper-proof. Anything of real value should be confirmed by the server.
 */
public final class LabsThemes {

    public enum Kind { FREE, PLAYTIME, RANK, CREDITS, STORE }

    /**
     * One background. {@code image} is a file bundled in /images (optional, used by the curated
     * backgrounds below); {@code previewUrl} is a remote image (used by backgrounds that came from the
     * server catalog, so a new one shows up without a launcher update); otherwise {@code gradientCss}
     * is the fallback. At most one of {@code image} / {@code previewUrl} is normally set.
     */
    public record Theme(String id, String name, Kind kind, int need, String image, String gradientCss, String previewUrl) {
        public Theme(String id, String name, Kind kind, int need, String image, String gradientCss) {
            this(id, name, kind, need, image, gradientCss, null);
        }
    }

    private static final String VIOLET = "linear-gradient(to bottom right, #150f45 0%, #2a1b6e 60%, #4a1f78 100%)";

    /**
     * The five backgrounds earned by playing (not sold), bundled with the launcher. Everything sold in
     * the Astro Store is fetched live from the server instead of being hard-coded here — see
     * {@link #setStoreCatalog} — so a background added to the store shows up without a launcher update.
     */
    public static final List<Theme> CURATED = List.of(
            new Theme("skyward-explorer", "Skyward Explorer", Kind.FREE, 0, "themes/skyward-explorer.jpg", VIOLET),
            new Theme("study-desk", "Study Desk", Kind.PLAYTIME, 3, "themes/study-desk.jpg", VIOLET),
            new Theme("sakura-shrine", "Sakura Shrine", Kind.RANK, 1, "themes/sakura-shrine.jpg", VIOLET),
            new Theme("lakeside-reader", "Lakeside Reader", Kind.PLAYTIME, 10, "themes/lakeside-reader.jpg", VIOLET),
            new Theme("petal-sky", "Petal Sky", Kind.CREDITS, 300, "themes/petal-sky.jpg", VIOLET));



    /** Per-user saved state. */
    public static final class UserState {
        public int spent;
        public List<String> owned = new ArrayList<>();
        public String selected = "skyward-explorer";
    }

    private static final class FileModel {
        Map<String, UserState> users = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LOCAL = "local";

    /** Id the Astro server sells a STORE background under (it shares the cape catalog, tagged kind "theme"). */
    public static String storeId(Theme t) { return "bg_" + t.id(); }

    /** Entitlement ids the signed-in account owns, from the server. Refreshed by the Labs page. */
    private volatile java.util.Set<String> storeOwned = java.util.Set.of();

    public void setStoreOwned(java.util.Collection<String> ids) {
        storeOwned = ids == null ? java.util.Set.of() : new java.util.HashSet<>(ids);
    }

    /** Backgrounds currently sold in the Astro Store, as last fetched from GET /capes?kind=theme. */
    private volatile List<Theme> storeThemes = List.of();

    /**
     * Rebuilds the list of purchasable backgrounds from the server's live catalog, so a background
     * added to (or removed from, or disabled in) the database shows up here without a launcher update.
     * Each server item's id already looks like {@code bg_<slug>}; {@link #storeId} re-adds that prefix,
     * so the id is stored here without it, matching how the curated themes are keyed.
     */
    public void setStoreCatalog(List<com.astrolauncher.store.StoreApi.CapeItem> items) {
        if (items == null || items.isEmpty()) { storeThemes = List.of(); return; }
        List<Theme> list = new ArrayList<>(items.size());
        for (com.astrolauncher.store.StoreApi.CapeItem c : items) {
            if (c == null || c.id == null || c.id.isBlank()) continue;
            String id = c.id.startsWith("bg_") ? c.id.substring(3) : c.id;
            String name = (c.name == null || c.name.isBlank()) ? com.astrolauncher.store.StoreApi.prettyName(id) : c.name;
            // previewUrl (set only when the server has a texture for this id) is loaded straight from
            // the network; no local /images asset is expected for a background that lives only server-side.
            // Prefer the picture bundled with the launcher (/images/themes/<slug>.jpg); previewUrl stays as the fallback.
            String slug = ThemeArt.slug(id);
            String local = "themes/" + slug + ".jpg";
            if (LabsThemes.class.getResource("/images/" + local) == null) {
                slug = ThemeArt.slug(name);
                local = "themes/" + slug + ".jpg";
                if (LabsThemes.class.getResource("/images/" + local) == null) local = null;
            }
            list.add(new Theme(id, name, Kind.STORE, c.priceCents, local, VIOLET, c.previewUrl));
        }
        storeThemes = list;
    }

    /** Every background to show: the curated earned ones, then whatever the server is currently selling. */
    public List<Theme> all() {
        List<Theme> list = new ArrayList<>(CURATED.size() + storeThemes.size());
        list.addAll(CURATED);
        list.addAll(storeThemes);
        return list;
    }

    private FileModel model = new FileModel();
    private final Path file;

    public LabsThemes() {
        this.file = PathResolver.launcherDataDir().resolve("labs.json");
        try {
            if (Files.exists(file)) {
                FileModel m = GSON.fromJson(Files.readString(file), FileModel.class);
                if (m != null && m.users != null) model = m;
            }
        } catch (Exception e) {
            AppLogger.get().warn("LabsThemes", "Could not read labs.json, starting fresh: " + e.getMessage());
        }
    }

    public UserState state(RankService.Lifetime life) {
        String key = life.userId() == null ? LOCAL : life.userId();
        return model.users.computeIfAbsent(key, k -> new UserState());
    }

    /** Credits earned so far: 20 per verified hour and 25 per finished quest. */
    public static int earned(RankService.Lifetime life) {
        double hours = life.verifiedMs() / 3_600_000.0;
        return (int) Math.floor(hours * 20) + life.questsDone() * 25;
    }

    public int balance(RankService.Lifetime life) {
        return Math.max(0, earned(life) - state(life).spent);
    }

    public boolean unlocked(Theme t, RankSnapshot snap, RankService.Lifetime life) {
        UserState st = state(life);
        switch (t.kind()) {
            case FREE:
                return true;
            case PLAYTIME:
                return life.verifiedMs() / 3_600_000.0 >= t.need();
            case RANK:
                return snap.signedIn() && snap.standing().tierIndex() >= t.need();
            case CREDITS:
                return st.owned.contains(t.id());
            case STORE:
                return storeOwned.contains(storeId(t));
            default:
                return false;
        }
    }

    /** Spends credits on a CREDITS theme. Returns false if it is not affordable or not for sale this way. */
    public boolean buy(Theme t, RankService.Lifetime life) {
        if (t.kind() != Kind.CREDITS) return false;
        UserState st = state(life);
        if (st.owned.contains(t.id())) return true;
        if (balance(life) < t.need()) return false;
        st.spent += t.need();
        st.owned.add(t.id());
        save();
        return true;
    }

    public void select(Theme t, RankService.Lifetime life) {
        state(life).selected = t.id();
        save();
    }

    public Theme selected(RankService.Lifetime life) {
        String id = state(life).selected;
        for (Theme t : all()) if (t.id().equals(id)) return t;
        return CURATED.get(0);
    }

    /** Short text for a locked theme: what it takes to get it. */
    public static String requirement(Theme t) {
        switch (t.kind()) {
            case PLAYTIME: return "Play " + t.need() + " h";
            case RANK: return "Reach " + rankName(t.need());
            case CREDITS: return "\u25C6 " + t.need();
            case STORE: return String.format("Store \u00B7 $%.2f", t.need() / 100.0);
            default: return "";
        }
    }

    private static String rankName(int tier) {
        String[] names = {"Stone", "Copper", "Iron", "Gold", "Emerald", "Sapphire", "Amethyst", "Obsidian", "Celestial"};
        return tier >= 0 && tier < names.length ? names[tier] : "a higher rank";
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("labs.json.tmp");
            Files.writeString(tmp, GSON.toJson(model));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AppLogger.get().warn("LabsThemes", "Could not save labs.json: " + e.getMessage());
        }
    }
}
