package com.astrolauncher.ui.pages;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.model.ServerEntry;
import com.astrolauncher.rank.RankService;
import com.astrolauncher.rank.RankSnapshot;
import com.astrolauncher.repository.ServerRepository;
import com.astrolauncher.servers.ServerPinger;
import com.astrolauncher.ui.AppContext;
import com.astrolauncher.ui.NavPage;
import com.astrolauncher.ui.kit.AvButton;
import com.astrolauncher.ui.kit.PageHeader;
import com.astrolauncher.ui.kit.PlayerSkinSource;
import com.astrolauncher.ui.kit.SkinModel3D;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Astro Labs: four things most launchers do not have.
 * <ul>
 *   <li><b>Passport</b>: a shareable player card (skin head, rank, AP, playtime) saved as a PNG.</li>
 *   <li><b>Wrapped</b>: a recap of your lifetime stats, also shareable.</li>
 *   <li><b>Server Hub</b>: pings your servers and picks the best one for you.</li>
 *   <li><b>Backups</b>: one-click world backups and restores.</li>
 *   <li><b>Servers</b>: the plain servers list (favorites, directory, your servers).</li>
 * </ul>
 */
public final class LabsPage {

    private static final String TAG = "LabsPage";
    private static final String PINOY_ADDRESS = "play.pinoy-craft.com";

    private final AppContext ctx;
    private final Consumer<NavPage> navigate;
    private final LabsThemes themes = new LabsThemes();
    private final StackPane content = new StackPane();
    private final Label credits = new Label();
    private ToggleButton serversTab;
    private ServersPage serversPage;

    public LabsPage(AppContext ctx, Consumer<NavPage> navigate) {
        this.ctx = ctx;
        this.navigate = navigate;
    }

    private void refreshCredits() {
        credits.setText("\u25C6 " + themes.balance(RankService.get().lifetime()) + " Astro Credits");
    }

    public VBox build() {
        VBox root = new VBox(18);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));
        PageHeader header = new PageHeader("Astro Labs", "Special things only Astro Launcher does.");

        ToggleGroup group = new ToggleGroup();
        HBox tabs = new HBox(8);
        ToggleButton passport = tab("Passport", group);
        ToggleButton wrapped = tab("Wrapped", group);
        ToggleButton hub = tab("Server Hub", group);
        ToggleButton backups = tab("Backups", group);
        serversTab = tab("Servers", group);
        tabs.getChildren().addAll(passport, wrapped, hub, backups, serversTab);

        passport.setOnAction(e -> show(passportTab()));
        wrapped.setOnAction(e -> show(wrappedTab()));
        hub.setOnAction(e -> show(hubTab()));
        backups.setOnAction(e -> show(backupsTab()));
        serversTab.setOnAction(e -> showServers());
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true);   // keep one tab selected
        });

        passport.setSelected(true);
        show(passportTab());

        credits.getStyleClass().add("labs-credits");
        credits.setTooltip(new javafx.scene.control.Tooltip(
                "Earned by playing: 20 per verified hour and 25 per finished quest. Spend them on card themes."));
        refreshCredits();
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox tabRow = new HBox(8, tabs, grow, credits);
        tabRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(header, tabRow, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return root;
    }

    /** Opens the Servers tab (the plain servers list). Built once, so its live pings keep running between visits. */
    public void showServers() {
        if (serversTab != null) serversTab.setSelected(true);
        if (serversPage == null) serversPage = new ServersPage(ctx);
        show(serversPage.buildListOnly());
    }

    private static ToggleButton tab(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(g);
        b.getStyleClass().add("labs-tab");
        return b;
    }

    private void show(Node n) {
        content.getChildren().setAll(n);
    }

    // ─────────────────────────────────────────────────────────── Passport

    private Node passportTab() {
        StackPane card = new StackPane();
        card.getStyleClass().add("labs-card");
        card.setMinSize(520, 300);
        card.setPrefSize(520, 300);
        card.setMaxSize(520, 300);

        Account acct = activeAccount();
        String name = acct != null && acct.username != null ? acct.username : "Player";
        RankSnapshot snap = RankService.get().snapshot();
        RankService.Lifetime life = RankService.get().lifetime();

        // Full 3D player standing on the left edge of the card (drag it to turn it).
        SkinModel3D model = new SkinModel3D(150, 270);
        StackPane.setAlignment(model, Pos.BOTTOM_LEFT);
        StackPane.setMargin(model, new Insets(0, 0, 4, 10));

        Label title = new Label("ASTRO PASSPORT");
        title.getStyleClass().add("labs-kicker");
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().addAll("labs-name", "player-name-grad");
        nameLabel.setMaxWidth(280);
        nameLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Label rank = new Label(snap.signedIn()
                ? snap.standing().label() + "  \u00B7  " + String.format(Locale.US, "%,d", snap.ap()) + " AP"
                : "Unranked");
        rank.getStyleClass().add("labs-rank");

        HBox stats = new HBox(26,
                stat(hours(life.verifiedMs()), "HOURS PLAYED"),
                stat(String.valueOf(life.sessions()), "SESSIONS"),
                stat(String.valueOf(life.questsDone()), "QUESTS DONE"));

        VBox right = new VBox(8, title, nameLabel, rank, stats);
        right.setAlignment(Pos.CENTER_LEFT);
        Label foot = new Label("Astro Launcher");
        foot.getStyleClass().add("labs-foot");
        StackPane.setAlignment(foot, Pos.TOP_RIGHT);
        StackPane.setMargin(foot, new Insets(14, 18, 0, 0));

        // The text sits on a dark glass panel at the bottom so the artwork stays visible above it.
        HBox body = new HBox(right);
        body.setAlignment(Pos.CENTER_LEFT);
        StackPane panel = new StackPane(body);
        panel.getStyleClass().add("labs-panel");
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(panel, new Insets(0, 16, 16, 172));
        card.getChildren().addAll(panel, model, foot);

        loadModel(model, acct);
        return exportable(card, "Your shareable player card. Save it or copy it and post it anywhere.", "astro-passport", null);
    }

    // ─────────────────────────────────────────────────────────── Wrapped

    private Node wrappedTab() {
        StackPane card = new StackPane();
        card.getStyleClass().add("labs-card");
        card.setMinSize(520, 340);
        card.setPrefSize(520, 340);
        card.setMaxSize(520, 340);

        RankSnapshot snap = RankService.get().snapshot();
        RankService.Lifetime life = RankService.get().lifetime();
        double hrs = life.verifiedMs() / 3_600_000.0;

        Label title = new Label("YOUR ASTRO WRAPPED");
        title.getStyleClass().add("labs-kicker");
        Label big = new Label(hours(life.verifiedMs()) + " hours");
        big.getStyleClass().addAll("labs-big", "player-name-grad");
        Label fun = new Label(snap.signedIn() || hrs > 0
                ? funLine(hrs)
                : "Sign in with Astro ID and play a little. Your recap fills in as you go.");
        fun.getStyleClass().add("labs-sub");
        fun.setWrapText(true);

        HBox stats = new HBox(26,
                stat(String.valueOf(life.sessions()), "SESSIONS"),
                stat(String.valueOf(life.questsDone()), "QUESTS DONE"),
                stat(snap.signedIn() ? String.format(Locale.US, "%,d", snap.ap()) : "-", "ASTRO POINTS"),
                stat(snap.signedIn() ? snap.standing().label() : "Unranked", "RANK"));

        VBox body = new VBox(8, title, big, fun, stats);
        body.setAlignment(Pos.CENTER_LEFT);
        StackPane panel = new StackPane(body);
        panel.getStyleClass().add("labs-panel");
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(panel, new Insets(0, 16, 16, 16));
        Label foot = new Label("Astro Launcher");
        foot.getStyleClass().add("labs-foot");
        StackPane.setAlignment(foot, Pos.TOP_RIGHT);
        StackPane.setMargin(foot, new Insets(14, 18, 0, 0));
        card.getChildren().addAll(panel, foot);
        return exportable(card, "Your lifetime stats in one card. Share it.", "astro-wrapped", null);
    }

    private static String funLine(double hrs) {
        if (hrs < 1) return "Just getting started. The first hour is the best one.";
        int movies = (int) Math.round(hrs / 2.0);
        int days = (int) Math.floor(hrs / 24.0);
        if (days >= 1) return "That is about " + days + " full day" + (days == 1 ? "" : "s")
                + " of Minecraft, or roughly " + movies + " movies back to back.";
        return "That is roughly " + Math.max(1, movies) + " movie" + (movies <= 1 ? "" : "s") + " worth of Minecraft.";
    }

    // ─────────────────────────────────────────────────────────── Server Hub

    private Node hubTab() {
        VBox list = new VBox(8);
        Label status = new Label("Testing your servers\u2026");
        status.getStyleClass().add("labs-sub");
        AvButton retest = new AvButton("Test again", AvButton.Kind.GHOST);

        Runnable run = () -> {
            list.getChildren().clear();
            retest.setDisable(true);
            status.setText("Testing your servers\u2026");
            List<ServerEntry> servers = new ArrayList<>();
            try {
                servers.addAll(new ServerRepository().load());
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not load saved servers: " + e.getMessage());
            }
            boolean hasPinoy = false;
            for (ServerEntry s : servers) if (s.address != null && s.address.toLowerCase(Locale.ROOT).contains("pinoy-craft")) hasPinoy = true;
            if (!hasPinoy) servers.add(new ServerEntry("pinoycraft", "PINOYCraft", PINOY_ADDRESS));
            final List<ServerEntry> toTest = servers;

            CompletableFuture.supplyAsync(() -> {
                List<Object[]> out = new ArrayList<>();
                for (ServerEntry s : toTest) {
                    ServerPinger.PingResult r;
                    try {
                        r = ServerPinger.ping(s.address);
                    } catch (Exception e) {
                        r = null;
                    }
                    out.add(new Object[] {s, r});
                }
                return out;
            }).thenAccept(results -> Platform.runLater(() -> {
                results.sort(Comparator.comparingInt(o -> {
                    ServerPinger.PingResult r = (ServerPinger.PingResult) o[1];
                    return r != null && r.online ? r.pingMs : Integer.MAX_VALUE;
                }));
                boolean first = true;
                int online = 0;
                for (Object[] o : results) {
                    ServerEntry s = (ServerEntry) o[0];
                    ServerPinger.PingResult r = (ServerPinger.PingResult) o[1];
                    boolean up = r != null && r.online;
                    if (up) online++;
                    list.getChildren().add(serverRow(s, r, up && first));
                    if (up) first = false;
                }
                status.setText(online == 0
                        ? "No server answered. Check your connection."
                        : online + " of " + results.size() + " servers online. Lowest ping first.");
                retest.setDisable(false);
            }));
        };
        retest.setOnAction(e -> run.run());
        run.run();

        VBox box = new VBox(12, status, retest, list);
        return scroll(box);
    }

    private Node serverRow(ServerEntry s, ServerPinger.PingResult r, boolean best) {
        Label name = new Label(s.name != null ? s.name : s.address);
        name.getStyleClass().add("labs-row-title");
        Label addr = new Label(s.address);
        addr.getStyleClass().add("labs-sub");
        VBox left = new VBox(2, name, addr);
        HBox.setHgrow(left, Priority.ALWAYS);

        boolean up = r != null && r.online;
        Label ping = new Label(up ? r.pingMs + " ms" : "Offline");
        ping.getStyleClass().addAll("labs-ping", up ? "labs-ok" : "labs-bad");
        Label players = new Label(up ? r.onlinePlayers + "/" + r.maxPlayers + " players" : "");
        players.getStyleClass().add("labs-sub");

        HBox row = new HBox(16, left, players, ping);
        row.setAlignment(Pos.CENTER_LEFT);
        if (best) {
            Label badge = new Label("BEST FOR YOU");
            badge.getStyleClass().add("labs-badge");
            row.getChildren().add(0, badge);
        }
        AvButton copy = new AvButton("Copy IP", AvButton.Kind.GHOST);
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(s.address);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        row.getChildren().add(copy);
        row.getStyleClass().add("labs-row");
        return row;
    }

    // ─────────────────────────────────────────────────────────── Backups

    private Node backupsTab() {
        VBox box = new VBox(12);
        Label status = new Label();
        status.getStyleClass().add("labs-sub");
        VBox worlds = new VBox(8);
        VBox saved = new VBox(8);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            worlds.getChildren().clear();
            saved.getChildren().clear();
            Path mc = mcDir();
            if (mc == null) {
                status.setText("Choose your Minecraft folder in Settings first.");
                return;
            }
            Path saves = mc.resolve("saves");
            List<Path> dirs = new ArrayList<>();
            if (Files.isDirectory(saves)) {
                try (Stream<Path> s = Files.list(saves)) {
                    s.filter(Files::isDirectory).sorted().forEach(dirs::add);
                } catch (IOException e) {
                    AppLogger.get().warn(TAG, "Could not list worlds: " + e.getMessage());
                }
            }
            status.setText(dirs.isEmpty()
                    ? "No worlds found in " + saves
                    : "Back up a world before you try something risky. Restoring also saves a copy of the current world first.");
            for (Path w : dirs) {
                Label n = new Label(w.getFileName().toString());
                n.getStyleClass().add("labs-row-title");
                HBox.setHgrow(n, Priority.ALWAYS);
                n.setMaxWidth(Double.MAX_VALUE);
                AvButton b = new AvButton("Back up now", AvButton.Kind.PRIMARY);
                b.setOnAction(e -> {
                    b.setDisable(true);
                    status.setText("Backing up " + w.getFileName() + "\u2026");
                    CompletableFuture.supplyAsync(() -> backupWorld(mc, w)).whenComplete((p, err) -> Platform.runLater(() -> {
                        status.setText(err != null || p == null
                                ? "Backup failed. See the log for details."
                                : "Saved " + p.getFileName());
                        refresh[0].run();
                    }));
                });
                HBox row = new HBox(12, n, b);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("labs-row");
                worlds.getChildren().add(row);
            }

            Path root = mc.resolve("astro-backups");
            List<Path> zips = new ArrayList<>();
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root, 2)) {
                    s.filter(p -> p.toString().endsWith(".zip")).forEach(zips::add);
                } catch (IOException ignored) { }
            }
            zips.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            for (Path z : zips) {
                Label n = new Label(z.getFileName().toString());
                n.getStyleClass().add("labs-row-title");
                HBox.setHgrow(n, Priority.ALWAYS);
                n.setMaxWidth(Double.MAX_VALUE);
                Label size = new Label(megabytes(z));
                size.getStyleClass().add("labs-sub");
                AvButton restore = new AvButton("Restore", AvButton.Kind.GHOST);
                restore.setOnAction(e -> restoreWorld(mc, z, status, refresh[0]));
                AvButton del = new AvButton("Delete", AvButton.Kind.GHOST);
                del.setOnAction(e -> {
                    try { Files.deleteIfExists(z); } catch (IOException ex) { AppLogger.get().warn(TAG, "Delete failed: " + ex.getMessage()); }
                    refresh[0].run();
                });
                HBox row = new HBox(12, n, size, restore, del);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("labs-row");
                saved.getChildren().add(row);
            }
            if (zips.isEmpty() && !dirs.isEmpty()) {
                Label none = new Label("No backups yet.");
                none.getStyleClass().add("labs-sub");
                saved.getChildren().add(none);
            }
        };
        refresh[0].run();

        Label h1 = new Label("WORLDS");
        h1.getStyleClass().add("av-section-title");
        Label h2 = new Label("BACKUPS");
        h2.getStyleClass().add("av-section-title");
        box.getChildren().addAll(status, h1, worlds, h2, saved);
        return scroll(box);
    }

    private Path mcDir() {
        String d = ctx.settings.minecraftDirectory;
        if (d == null || d.isBlank()) return null;
        return Path.of(d);
    }

    private static Path backupWorld(Path mc, Path world) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path dir = mc.resolve("astro-backups").resolve(world.getFileName().toString());
            Files.createDirectories(dir);
            Path zip = dir.resolve(world.getFileName() + "_" + stamp + ".zip");
            try (OutputStream out = Files.newOutputStream(zip);
                 ZipOutputStream zos = new ZipOutputStream(out);
                 Stream<Path> walk = Files.walk(world)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isDirectory(p)) continue;
                    if (p.getFileName().toString().equals("session.lock")) continue;   // held open by a running game
                    String entry = world.getFileName() + "/" + world.relativize(p).toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException skip) {
                        AppLogger.get().warn(TAG, "Skipped " + p + ": " + skip.getMessage());
                    }
                }
            }
            return zip;
        } catch (IOException e) {
            AppLogger.get().warn(TAG, "Backup failed: " + e.getMessage());
            return null;
        }
    }

    private void restoreWorld(Path mc, Path zip, Label status, Runnable refresh) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore " + zip.getFileName() + "?\nThe current version of that world is backed up first, then replaced.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("Restore world");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        status.setText("Restoring\u2026");
        CompletableFuture.runAsync(() -> {
            try {
                Path saves = mc.resolve("saves").toAbsolutePath().normalize();
                String worldName = null;
                try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry e = zin.getNextEntry();
                    if (e != null) worldName = e.getName().split("/")[0];
                }
                if (worldName == null || worldName.isBlank()) throw new IOException("Empty backup");
                Path target = saves.resolve(worldName).normalize();
                if (!target.startsWith(saves)) throw new IOException("Unsafe backup");
                if (Files.isDirectory(target)) {
                    Path safety = backupWorld(mc, target);
                    if (safety == null) throw new IOException("Could not back up the current world, so nothing was changed");
                    deleteTree(target);
                }
                try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        Path out = saves.resolve(e.getName()).normalize();
                        if (!out.startsWith(saves)) throw new IOException("Unsafe entry " + e.getName());
                        if (e.isDirectory()) {
                            Files.createDirectories(out);
                        } else {
                            Files.createDirectories(out.getParent());
                            Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Platform.runLater(() -> status.setText("Restored " + zip.getFileName()));
            } catch (Exception ex) {
                AppLogger.get().warn(TAG, "Restore failed: " + ex.getMessage());
                Platform.runLater(() -> status.setText("Restore failed: " + ex.getMessage()));
            }
            Platform.runLater(refresh);
        });
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> w = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            w.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) Files.deleteIfExists(p);
        }
    }

    private static String megabytes(Path p) {
        try {
            return String.format(Locale.US, "%.1f MB", Files.size(p) / 1_048_576.0);
        } catch (IOException e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────── shared bits

    private Node scroll(Node n) {
        ScrollPane sp = new ScrollPane(n);
        sp.setFitToWidth(true);
        sp.getStyleClass().addAll("page-scroll", "av-page-scroll");
        sp.setStyle("-fx-background-color: transparent;");
        return sp;
    }

    private Node stat(String value, String label) {
        Label v = new Label(value);
        v.getStyleClass().add("labs-stat");
        Label l = new Label(label);
        l.getStyleClass().add("labs-stat-label");
        return new VBox(2, v, l);
    }

    private static String hours(long ms) {
        double h = ms / 3_600_000.0;
        return h >= 100 ? String.valueOf(Math.round(h)) : String.format(Locale.US, "%.1f", h);
    }

    private Account activeAccount() {
        String id = ctx.settings.activeAccountId;
        if (id == null) return null;
        return ctx.accountRepository.load().stream().filter(a -> id.equals(a.id)).findFirst().orElse(null);
    }

    private void loadModel(SkinModel3D model, Account acct) {
        if (acct == null) {
            model.showEmpty("No account");
            return;
        }
        model.showLoading();
        PlayerSkinSource.load(() -> acct).whenComplete((prepared, err) -> Platform.runLater(() -> {
            if (err != null || prepared == null) {
                model.showEmpty("Skin unavailable");
                return;
            }
            model.show(prepared);
        }));
    }

    private void loadHead(StackPane slot, Account acct, double size) {
        if (acct == null) return;
        PlayerSkinSource.load(() -> acct).whenComplete((prepared, err) -> Platform.runLater(() -> {
            if (err != null || prepared == null) return;
            slot.getChildren().setAll(SkinModel3D.head(prepared, size));
        }));
    }

    // ─────────────────────────────────────────────────────────── themes

    private static final java.util.Map<String, javafx.scene.image.Image> THEME_IMAGES = new java.util.HashMap<>();

    /** Background layer for a card or a preview tile: artwork (cover-fitted) or a gradient, with a dark scrim. */
    private static Node themeLayer(LabsThemes.Theme t, double w, double h, double arc, double scrimBottom) {
        StackPane layer = new StackPane();
        layer.setMinSize(w, h);
        layer.setPrefSize(w, h);
        layer.setMaxSize(w, h);
        layer.setStyle("-fx-background-color: " + t.gradientCss() + ";");
        javafx.scene.image.Image img = null;
        if (t.image() != null) {
            // A background bundled with the launcher (/images/themes/*.jpg). Decoded once per size and shared,
            // because the picker rebuilds every tile on each click and there are dozens of them.
            java.net.URL res = LabsPage.class.getResource("/images/" + t.image());
            if (res != null) {
                String key = t.image() + "@" + w + "x" + h;
                javafx.scene.image.Image cached = THEME_IMAGES.get(key);
                if (cached == null) {
                    cached = new javafx.scene.image.Image(res.toExternalForm(), w * 2, h * 2, true, true, true);
                    THEME_IMAGES.put(key, cached);
                }
                if (cached.isError()) THEME_IMAGES.remove(key); else img = cached;
            }
        }
        if (img == null && t.previewUrl() != null && !t.previewUrl().isBlank()) {
            // A background that came from the server catalog: load it over the network, in the
            // background, like every other remote image in the app (e.g. CapePreview).
            try {
                javafx.scene.image.Image loaded = new javafx.scene.image.Image(t.previewUrl(), w * 2, h * 2, true, true, true);
                img = loaded;
            } catch (Exception ignored) { }
        }
        if (img != null) {
            final javafx.scene.image.Image loadedImg = img; // lambdas below need an effectively-final capture
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(loadedImg);
            Region scrim = new Region();
            scrim.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(8,5,30,0.0) 35%, rgba(8,5,30," + scrimBottom + ") 100%);");
            iv.setPreserveRatio(false);
            iv.setSmooth(true);
            Runnable fit = () -> {
                double iw = loadedImg.getWidth(), ih = loadedImg.getHeight();
                if (iw <= 0 || ih <= 0) return;
                double scale = Math.max(w / iw, h / ih);
                iv.setFitWidth(iw * scale);
                iv.setFitHeight(ih * scale);
            };
            if (loadedImg.getProgress() >= 1.0 && !loadedImg.isError()) fit.run();
            else loadedImg.progressProperty().addListener((o, was, now) -> { if (now.doubleValue() >= 1.0 && !loadedImg.isError()) fit.run(); });
            layer.getChildren().addAll(iv, scrim);
        }
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(w, h);
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        layer.setClip(clip);
        layer.setMouseTransparent(true);
        return layer;
    }

    private void applyTheme(StackPane card, LabsThemes.Theme t) {
        card.getChildren().removeIf(n -> "theme-bg".equals(n.getUserData()));
        Node layer = themeLayer(t, card.getPrefWidth(), card.getPrefHeight(), 44, 0.55);
        layer.setUserData("theme-bg");
        card.getChildren().add(0, layer);
    }

    /** Row of background tiles under a card: pick an owned one, buy one with credits, or see how to unlock it. */
    private Node themeBar(StackPane card) {
        javafx.scene.layout.TilePane row = new javafx.scene.layout.TilePane(10, 10);
        row.setPrefColumns(2);
        row.setPadding(new Insets(2, 4, 8, 2));
        javafx.scene.layout.FlowPane chipRow = new javafx.scene.layout.FlowPane(6, 6);
        chipRow.getStyleClass().add("store-cat-row");
        Object[] filter = new Object[1]; // null = All, else a ThemeArt.Category
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            RankSnapshot snap = RankService.get().snapshot();
            RankService.Lifetime life = RankService.get().lifetime();
            LabsThemes.Theme current = themes.selected(life);
            List<LabsThemes.Theme> all = themes.all();
            if (!themes.unlocked(current, snap, life)) current = all.isEmpty() ? current : all.get(0);
            applyTheme(card, current);
            refreshCredits();

            java.util.Map<ThemeArt.Category, Integer> counts = new java.util.EnumMap<>(ThemeArt.Category.class);
            for (LabsThemes.Theme t : all) counts.merge(ThemeArt.categoryOf(t.id(), t.name()), 1, Integer::sum);
            if (filter[0] instanceof ThemeArt.Category fc && !counts.containsKey(fc)) filter[0] = null;
            List<CategoryUi.Chip> chips = new ArrayList<>();
            chips.add(new CategoryUi.Chip("All  " + all.size(), null));
            for (ThemeArt.Category c : ThemeArt.order()) {
                Integer n = counts.get(c);
                if (n != null) chips.add(new CategoryUi.Chip(c.label + "  " + n, c));
            }
            CategoryUi.fill(chipRow, chips, filter[0], key -> { filter[0] = key; rebuild[0].run(); });

            row.getChildren().clear();
            for (LabsThemes.Theme t : all) {
                if (filter[0] != null && filter[0] != ThemeArt.categoryOf(t.id(), t.name())) continue;
                boolean open = themes.unlocked(t, snap, life);
                StackPane tile = new StackPane();
                tile.setMinSize(128, 84);
                tile.setPrefSize(128, 84);
                tile.setMaxSize(128, 84);
                Node bg = themeLayer(t, 128, 84, 18, 0.7);
                Label name = new Label(t.name());
                name.getStyleClass().add("labs-tile-name");
                String need = t.kind() == LabsThemes.Kind.STORE
                        ? "Store \u00B7 " + livePrice.getOrDefault(LabsThemes.storeId(t), String.format("$%.2f", t.need() / 100.0))
                        : LabsThemes.requirement(t);
                Label sub = new Label(open ? (t.id().equals(current.id()) ? "Selected" : "Unlocked") : need);
                sub.getStyleClass().add(open ? "labs-tile-open" : "labs-tile-locked");
                VBox text = new VBox(1, name, sub);
                text.setAlignment(Pos.BOTTOM_LEFT);
                text.setPadding(new Insets(8));
                tile.getChildren().addAll(bg, text);
                tile.getStyleClass().add("labs-tile");
                if (t.id().equals(current.id())) tile.getStyleClass().add("labs-tile-selected");
                if (!open) bg.setOpacity(0.55);
                tile.setCursor(javafx.scene.Cursor.HAND);
                tile.setOnMouseClicked(e -> {
                    if (open) {
                        themes.select(t, life);
                    } else if (t.kind() == LabsThemes.Kind.CREDITS) {
                        int bal = themes.balance(life);
                        if (bal < t.need()) {
                            info("Not enough credits", t.name() + " costs " + t.need() + " Astro Credits. You have " + bal
                                    + ".\nPlay more to earn them: 20 per verified hour and 25 per finished quest.");
                            return;
                        }
                        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                                "Unlock " + t.name() + " for " + t.need() + " Astro Credits?", ButtonType.OK, ButtonType.CANCEL);
                        a.setHeaderText(null);
                        a.setTitle("Unlock theme");
                        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
                        if (themes.buy(t, life)) themes.select(t, life);
                    } else if (t.kind() == LabsThemes.Kind.STORE) {
                        buyTheme(t, rebuild[0]);
                        return;
                    } else {
                        info("Locked", t.name() + ": " + LabsThemes.requirement(t) + " to unlock this background.");
                        return;
                    }
                    rebuild[0].run();
                });
                row.getChildren().add(tile);
            }
        };
        rebuild[0].run();
        refreshStore(rebuild[0]);   // what the account owns in the Store + the live prices
        ScrollPane sp = new ScrollPane(row);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setMinWidth(300);
        sp.setPrefWidth(300);
        sp.setMaxWidth(300);
        sp.setPrefViewportHeight(card.getPrefHeight() + 60);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox box = new VBox(8, chipRow, sp);
        box.setMinWidth(300);
        box.setPrefWidth(300);
        box.setMaxWidth(300);
        return box;
    }

    // ─────────────────────────────────────────────────── Store backgrounds

    private final java.util.Map<String, String> livePrice = new java.util.HashMap<>();
    private javafx.animation.Timeline ownershipPoll;
    private int pollTicks;

    /** Loads the backgrounds' live prices and which of them the signed-in account owns, then runs {@code after} on the FX thread. */
    private void refreshStore(Runnable after) {
        com.astrolauncher.store.AstroId astro = com.astrolauncher.store.AstroId.get();
        com.astrolauncher.store.StoreApi api = astro.api();
        api.themeCatalog().whenComplete((list, err) -> {
            if (err == null && list != null) {
                // The source of truth for which backgrounds exist: anything the server is selling
                // under kind "theme" shows up here, not just the curated set baked into the launcher.
                themes.setStoreCatalog(list);
                for (com.astrolauncher.store.StoreApi.CapeItem c : list) if (c.id != null) livePrice.put(c.id, c.priceLabel());
            }
            if (!astro.isSignedIn()) { themes.setStoreOwned(null); Platform.runLater(after); return; }
            api.wardrobe().whenComplete((w, e2) -> {
                if (e2 == null && w != null) themes.setStoreOwned(w.owned);
                Platform.runLater(after);
            });
        });
    }

    /** Confirm, open the Store checkout in the browser, then watch the account until the background shows up as owned. */
    private void buyTheme(LabsThemes.Theme t, Runnable after) {
        com.astrolauncher.store.AstroId astro = com.astrolauncher.store.AstroId.get();
        if (!astro.isSignedIn()) {
            info("Sign in to buy", "Sign in with your Astro ID (Accounts page) to buy " + t.name() + ".");
            return;
        }
        String price = livePrice.getOrDefault(LabsThemes.storeId(t), String.format("$%.2f", t.need() / 100.0));
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Buy the " + t.name() + " background for " + price + "?\nCheckout opens in your browser; "
                        + "the background unlocks here as soon as the payment goes through.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("Buy background");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        astro.api().checkoutUrl(LabsThemes.storeId(t)).whenComplete((url, err) -> Platform.runLater(() -> {
            if (err != null) {
                Throwable r = err; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                info("Couldn't start checkout", r.getMessage() == null ? "Please try again." : r.getMessage());
                return;
            }
            try {
                if (url != null && java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                }
            } catch (Exception e) { AppLogger.get().warn(TAG, "Could not open the browser: " + e.getMessage()); }
            watchOwnership(t, after);
        }));
    }

    private void watchOwnership(LabsThemes.Theme t, Runnable after) {
        if (ownershipPoll != null) ownershipPoll.stop();
        pollTicks = 0;
        ownershipPoll = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(4), e ->
                refreshStore(() -> {
                    after.run();
                    boolean owned = themes.unlocked(t, RankService.get().snapshot(), RankService.get().lifetime());
                    if (owned || ++pollTicks > 75) { ownershipPoll.stop(); }
                })));
        ownershipPoll.setCycleCount(javafx.animation.Animation.INDEFINITE);
        ownershipPoll.play();
    }

    private static void info(String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.setHeaderText(null);
        a.setTitle(title);
        a.showAndWait();
    }

    /** A card with Save / Copy buttons that render it to a 2x PNG. */
    private Node exportable(StackPane card, String hint, String fileBase, Node extra) {
        Label h = new Label(hint);
        h.getStyleClass().add("labs-sub");
        Label msg = new Label();
        msg.getStyleClass().add("labs-sub");
        AvButton save = new AvButton("Save as PNG", AvButton.Kind.PRIMARY);
        AvButton copy = new AvButton("Copy image", AvButton.Kind.GHOST);
        save.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(fileBase + ".png");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
            File f = fc.showSaveDialog(card.getScene() != null ? card.getScene().getWindow() : null);
            if (f == null) return;
            try {
                writePng(render(card), f);
                msg.setText("Saved " + f.getName());
            } catch (Exception ex) {
                AppLogger.get().warn(TAG, "Saving image failed: " + ex.getMessage());
                msg.setText("Could not save the image.");
            }
        });
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putImage(render(card));
            Clipboard.getSystemClipboard().setContent(cc);
            msg.setText("Copied to the clipboard.");
        });
        HBox buttons = new HBox(10, save, copy, msg);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(14, h, card);
        if (extra != null) left.getChildren().add(extra);
        left.getChildren().add(buttons);
        left.setAlignment(Pos.TOP_LEFT);
        Label pick = new Label("BACKGROUNDS");
        pick.getStyleClass().add("av-section-title");
        VBox right = new VBox(8, pick, themeBar(card));
        HBox both = new HBox(28, left, right);
        both.setAlignment(Pos.TOP_LEFT);
        return both;
    }

    private static WritableImage render(Node card) {
        double w = card.getLayoutBounds().getWidth(), h = card.getLayoutBounds().getHeight();
        SnapshotParameters sp = new SnapshotParameters();
        sp.setTransform(Transform.scale(2, 2));
        sp.setFill(javafx.scene.paint.Color.TRANSPARENT);
        return card.snapshot(sp, new WritableImage((int) (w * 2), (int) (h * 2)));
    }

    /** PNG writer that needs no javafx.swing: copies the pixels into an AWT image and uses ImageIO. */
    private static void writePng(WritableImage img, File f) throws IOException {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, pr.getArgb(x, y));
        }
        if (!javax.imageio.ImageIO.write(out, "png", f)) throw new IOException("No PNG writer");
    }
}
