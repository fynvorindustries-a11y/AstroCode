package com.astrolauncher.ui.pages;

import com.astrolauncher.model.Account;
import com.astrolauncher.model.LauncherSettings;
import com.astrolauncher.repository.AccountRepository;
import com.astrolauncher.repository.SettingsRepository;
import com.astrolauncher.skins.SkinLibrary;
import com.astrolauncher.skins.SkinService;
import com.astrolauncher.store.AstroId;
import com.astrolauncher.ui.Motion;
import com.astrolauncher.ui.NavPage;
import com.astrolauncher.ui.ValDeco;
import com.astrolauncher.ui.kit.AvButton;
import com.astrolauncher.ui.kit.SkinModel3D;
import com.astrolauncher.ui.kit.Card;
import com.astrolauncher.ui.kit.ErrorState;
import com.astrolauncher.ui.kit.Skeleton;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.beans.InvalidationListener;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Home. A bold hero ("Ready when you are", the player's name, a big PLAY button with a version
 * selector, and the equipped cape), three quick cards (continue / servers / cape) and the
 * community partners.
 *
 * <p>PLAY here only navigates to the Play page, exactly as the old hero button did; launching stays
 * in PlayPage/LaunchService. The version selector writes {@code defaultVersionId} the same way
 * PlayPage does. All styling is in {@code pages.css}; the only motion is the page transition, the
 * Play glow pulse and a very slow drifting light behind the hero, all through {@link Motion}.
 *
 * <p>Pages are cached, so the content is rebuilt each time Home is shown again. That way a changed
 * Minecraft folder, default version, account or cape is reflected without restarting.
 */
public class HomePage {

    private static final double HERO_H = 400;
    private static final String COPY_IP = "play.pinoy-craft.com";

    private final LauncherSettings settings;
    private final Consumer<NavPage> navigator;
    private final AccountRepository accountRepository;
    private final SettingsRepository settingsRepository;
    private final Runnable openLeaderboard;

    private final VBox root = new VBox();
    private final List<Consumer<CapeState>> capeViews = new ArrayList<>();
    private Label continueTitle;
    private CapeState lastCape;   // last known cape, shown instantly on a revisit
    private int capeTicket;
    private boolean stale;

    public HomePage(LauncherSettings settings, Consumer<NavPage> navigator,
                    AccountRepository accountRepository, SettingsRepository settingsRepository,
                    Runnable openLeaderboard) {
        this.settings = settings;
        this.navigator = navigator;
        this.accountRepository = accountRepository;
        this.settingsRepository = settingsRepository;
        this.openLeaderboard = openLeaderboard != null ? openLeaderboard : () -> { };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Page
    // ─────────────────────────────────────────────────────────────────────────

    public VBox build() {
        root.getStyleClass().addAll("av-page-body", "gap-24");
        rebuild();
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                stale = true;                 // left the screen: refresh next time it is shown
            } else if (stale) {
                stale = false;
                rebuild();
            }
        });
        return root;
    }

    private void rebuild() {
        capeViews.clear();
        // The 3D player now stands in its own card beside the hero instead of inside it.
        StackPane hero = buildHero();
        HBox.setHgrow(hero, Priority.ALWAYS);
        hero.setMinWidth(0);
        hero.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(20, hero, buildHeroSkin());
        top.setFillHeight(true);
        root.getChildren().setAll(top, buildQuickRow(), buildCommunity());
        loadCape();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hero
    // ─────────────────────────────────────────────────────────────────────────

    private StackPane buildHero() {
        StackPane hero = new StackPane();
        hero.getStyleClass().add("home-hero");
        hero.setMinHeight(HERO_H);
        hero.setPrefHeight(HERO_H);
        hero.setMaxHeight(HERO_H);

        Region base = new Region();
        base.getStyleClass().add("home-hero-base");
        hero.getChildren().add(base);

        Node photo = buildHeroPhoto(hero);
        if (photo != null) hero.getChildren().add(photo);

        Region light = new Region();           // ambient light: the only looping motion on Home
        light.getStyleClass().add("home-hero-light");
        light.setMouseTransparent(true);
        light.setPrefWidth(640);
        light.setMaxWidth(640);
        StackPane.setAlignment(light, Pos.CENTER_LEFT);
        Motion.drift(light, 140, Duration.seconds(18));

        Region scrim = new Region();
        scrim.getStyleClass().add("home-hero-scrim");
        scrim.setMouseTransparent(true);

        HBox content = new HBox(28);
        content.getStyleClass().add("home-hero-content");
        content.setAlignment(Pos.CENTER_LEFT);
        VBox left = buildHeroLeft();
        HBox.setHgrow(left, Priority.ALWAYS);
        content.getChildren().addAll(left);

        Node leaderboardBtn = buildLeaderboardButton();
        StackPane.setAlignment(leaderboardBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(leaderboardBtn, new Insets(20, 24, 0, 0));

        hero.getChildren().addAll(light, scrim, content, leaderboardBtn);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        clip.setArcWidth(44);   // rounded hero corners (radius 22)
        clip.setArcHeight(44);
        hero.setClip(clip);
        return hero;
    }

    /** Pinned to the hero's top-right corner; slides the live leaderboard in as a semipage drawer. */
    private Node buildLeaderboardButton() {
        // Simple ascending-bars glyph (ranked standings), same 24-grid stroke style as LeaderboardPanel's icons.
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M4 20 V15 M10 20 V11 M16 20 V6 M2 20 H22");
        icon.getStyleClass().add("home-leaderboard-btn-icon");

        Label text = new Label("LEADERBOARD");
        HBox inner = new HBox(8, icon, text);
        inner.setAlignment(Pos.CENTER);

        javafx.scene.control.Button btn = new javafx.scene.control.Button();
        btn.setGraphic(inner);
        btn.getStyleClass().add("home-leaderboard-btn");
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setOnAction(e -> openLeaderboard.run());
        btn.setAccessibleText("Open leaderboard");
        btn.hoverProperty().addListener((o, was, hovering) -> {
            Motion.animate(btn.translateYProperty(), hovering ? -2.0 : 0.0, Motion.HOVER);
            Motion.animate(icon.translateYProperty(), hovering ? -2.0 : 0.0, Motion.HOVER); // bars reach up a little further
        });
        return btn;
    }

    private VBox buildHeroLeft() {
        boolean ready = isReady();
        Account active = resolveActiveAccount();
        String version = settings.defaultVersionId;

        Label welcome = new Label("Welcome to");
        welcome.getStyleClass().add("home-welcome");

        Label brandA = new Label("Astro");
        brandA.getStyleClass().add("home-wordmark-a");
        Label brandB = new Label("Launcher");
        brandB.getStyleClass().add("home-wordmark-b");
        HBox wordmark = new HBox(0, brandA, brandB);
        wordmark.setAlignment(Pos.BASELINE_LEFT);
        wordmark.setMinWidth(0);

        Label tagline = new Label("Play  \u25C6  Explore  \u25C6  Enjoy");
        tagline.getStyleClass().add("home-tagline");

        // Guidance only while something still has to be set up; once ready the hero stays clean.
        Label sub = new Label(subText(active, version));
        sub.getStyleClass().add("home-hero-sub");
        sub.setWrapText(true);
        sub.setMaxWidth(500);
        sub.setMinWidth(0);
        sub.setVisible(!ready);
        sub.setManaged(!ready);
        VBox.setMargin(sub, new Insets(8, 0, 0, 0));

        Node actions = buildHeroActions(sub, active);
        VBox.setMargin(actions, new Insets(18, 0, 0, 0));

        VBox left = new VBox(2, welcome, wordmark, tagline, sub, actions);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(0);  // let the HBox actually shrink this column instead of clipping it
        return left;
    }

    private static Label heroChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("av-chip", "home-hero-chip");
        return l;
    }

    private String subText(Account active, String version) {
        if (!mcDirExists()) return "Choose your Minecraft folder in Settings to get started.";
        if (!versionSet()) return "Pick a version and you'll be ready to launch.";
        String base = "Jump straight into Minecraft " + version + ".";
        return active == null ? base + " Sign in with Astro or Microsoft in Accounts to play." : base;
    }

    private Node buildHeroActions(Label sub, Account active) {
        HBox row = new HBox(20);
        row.setAlignment(Pos.CENTER_LEFT);

        if (!mcDirExists()) {
            AvButton open = new AvButton("Open settings", AvButton.Kind.PRIMARY);
            open.getStyleClass().add("home-cta");
            open.setOnAction(e -> navigator.accept(NavPage.SETTINGS));
            row.getChildren().add(open);
            return row;
        }

        if (isReady()) {
            AvButton play = new AvButton("PLAY", AvButton.Kind.PLAY);
            play.setGraphic(icon("M7 4 L19 12 L7 20 Z", "home-btn-icon"));
            play.getStyleClass().add("home-play");
            play.setOnAction(e -> navigator.accept(NavPage.PLAY));

            // Glow sits behind the button and is the only thing that pulses (opacity only: no effects).
            Region glow = new Region();
            glow.getStyleClass().add("home-play-glow");
            glow.setMouseTransparent(true);
            StackPane.setMargin(glow, new Insets(-40, -56, -40, -56));
            Motion.pulse(glow, 0.35, 0.9, Duration.millis(1400));
            row.getChildren().add(new StackPane(glow, play));
            row.getChildren().add(learnMoreButton());
        } else {
            AvButton choose = new AvButton("Choose version", AvButton.Kind.PRIMARY);
            choose.getStyleClass().add("home-cta");
            choose.setOnAction(e -> navigator.accept(NavPage.PLAY));
            row.getChildren().add(choose);
            row.getChildren().add(learnMoreButton());
            // Until a version is chosen the selector is how the player gets unblocked right here.
            Node selector = buildVersionSelector(sub, active);
            if (selector != null) row.getChildren().add(selector);
        }
        return row;
    }

    private AvButton learnMoreButton() {
        AvButton b = new AvButton("Learn More", AvButton.Kind.GHOST);
        b.getStyleClass().add("home-learn");
        b.setGraphic(icon("M12 3 A9 9 0 1 0 12.01 3 Z M12 11 V17 M12 7.5 V7.6", "home-btn-icon-line"));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        b.setGraphicTextGap(10);
        b.setOnAction(e -> navigator.accept(NavPage.ABOUT));
        return b;
    }

    private static javafx.scene.shape.SVGPath icon(String path, String styleClass) {
        javafx.scene.shape.SVGPath p = new javafx.scene.shape.SVGPath();
        p.setContent(path);
        p.getStyleClass().add(styleClass);
        return p;
    }

    /** Installed versions, newest first. Changing it saves the default version just as PlayPage does. */
    private Node buildVersionSelector(Label sub, Account active) {
        List<String> versions = installedVersions();
        String current = settings.defaultVersionId;
        if (versionSet() && !versions.contains(current)) versions.add(0, current);
        if (versions.isEmpty()) return null;

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(versions));
        combo.getStyleClass().add("home-version-select");
        combo.setAccessibleText("Minecraft version");
        combo.hoverProperty().addListener((o, was, hovering) -> {
            Motion.animate(combo.scaleXProperty(), hovering ? 1.03 : 1.0, Motion.HOVER);
            Motion.animate(combo.scaleYProperty(), hovering ? 1.03 : 1.0, Motion.HOVER);
        });
        if (versionSet()) combo.setValue(current);
        else combo.setPromptText("Select version");

        boolean wasReady = isReady();
        combo.setOnAction(e -> {
            String v = combo.getValue();
            if (v == null || v.equals(settings.defaultVersionId)) return;
            settings.defaultVersionId = v;
            settingsRepository.save(settings);
            if (!wasReady) {
                Platform.runLater(this::rebuild); // hero changes from "choose version" to PLAY
                return;
            }
            sub.setText(subText(active, v));
            if (continueTitle != null) continueTitle.setText("Minecraft " + v);
        });

        Label label = new Label("VERSION");
        label.getStyleClass().add("home-field-label");
        VBox box = new VBox(6, label, combo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── player skin in 3D (hero, right) ──────────────────────────────────────

    private static Label infoChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("home-player-chip");
        return l;
    }

    private Node buildHeroSkin() {
        // Waist-up model (no legs), standing in its own card next to the hero.
        SkinModel3D model = new SkinModel3D(240, 215);
        model.setBust(true);

        StackPane headSlot = new StackPane();
        headSlot.setMinSize(26, 26);
        headSlot.setPrefSize(26, 26);
        headSlot.setMaxSize(26, 26);
        Label name = new Label();
        name.getStyleClass().addAll("home-cape-name", "player-name-grad");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(190);
        HBox nameRow = new HBox(8, headSlot, name);
        nameRow.setAlignment(Pos.CENTER);

        Label tag = new Label("YOUR SKIN");
        tag.getStyleClass().add("av-section-title");

        AvButton manage = new AvButton("Manage Account", AvButton.Kind.PRIMARY);
        manage.getStyleClass().add("home-player-manage");
        manage.setMaxWidth(Double.MAX_VALUE);
        manage.setOnAction(e -> navigator.accept(NavPage.ACCOUNTS));

        // Details: account type, skin model, and (when signed in to Astro ID) rank and AP.
        HBox chips = new HBox(6);
        chips.setAlignment(Pos.CENTER);
        Account shown = activeAccount();
        if (shown != null) {
            chips.getChildren().add(infoChip(shown.type == Account.Type.MICROSOFT ? "MICROSOFT" : "OFFLINE"));
            String variant = shown.type == Account.Type.MICROSOFT ? shown.skinVariant : shown.offlineSkinVariant;
            if (variant != null && !variant.isBlank()) {
                chips.getChildren().add(infoChip("slim".equalsIgnoreCase(variant) ? "SLIM ARMS" : "CLASSIC"));
            }
        }
        Label rankLine = new Label();
        rankLine.getStyleClass().add("home-player-rank");
        com.astrolauncher.rank.RankSnapshot rs = com.astrolauncher.rank.RankService.get().snapshot();
        if (rs.signedIn()) {
            rankLine.setText(rs.standing().label() + "  \u00B7  " + String.format(java.util.Locale.US, "%,d", rs.ap()) + " AP");
        } else {
            rankLine.setText("Sign in with Astro ID to earn a rank");
        }

        VBox col = new VBox(10, model, nameRow, chips, rankLine, tag, manage);
        col.setAlignment(Pos.TOP_CENTER);
        col.getStyleClass().add("home-player-card");
        col.setMinWidth(280);
        col.setPrefWidth(280);
        col.setMaxWidth(280);
        col.setMinHeight(HERO_H);

        // Drag turns the model (handled by the model itself); the button opens Accounts, where skins are managed.
        col.setAccessibleText("Your skin in 3D. Drag to rotate.");

        loadSkin(model, tag, name, headSlot);

        return col;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quick cards
    // ─────────────────────────────────────────────────────────────────────────

    /** "Servers" section: saved servers (live ping), cape, and rank. Each card opens its page. */
    private VBox buildQuickRow() {
        HBox row = new HBox();
        row.getStyleClass().add("gap-16");
        for (Card c : new Card[] { serversCard(), capeCard(), rankCard() }) {
            HBox.setHgrow(c, Priority.ALWAYS);
            c.setPrefWidth(0);
            c.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(c);
        }
        row.setFillHeight(true);
        return new VBox(row);
    }

    private Card serversCard() {
        int n = settings.savedServers != null ? settings.savedServers.size() : 0;
        Label title = new Label(n == 0 ? "Find a server" : n + (n == 1 ? " saved server" : " saved servers"));
        Label hint = new Label(n == 0 ? "Browse the directory and save your favourites." : "Browse your saved servers and join instantly.");
        QuickCard q = quickCard("/images/servers.png", n == 0 ? null : "Checking\u2026", "idle", title, hint,
                () -> navigator.accept(NavPage.SERVERS));
        if (n > 0) {
            // Real status: ping up to 3 saved servers off the FX thread; any answer = Online, none = Offline.
            List<String> addrs = new ArrayList<>();
            for (var e : settings.savedServers) { if (e.address != null && addrs.size() < 3) addrs.add(e.address); }
            Thread t = new Thread(() -> {
                boolean any = false;
                for (String a : addrs) {
                    try { if (com.astrolauncher.servers.ServerPinger.ping(a).online) { any = true; break; } }
                    catch (RuntimeException ignored) { /* counts as not reachable */ }
                }
                final boolean up = any;
                Platform.runLater(() -> q.setPill(up ? "Online" : "Offline", up ? "ok" : "bad"));
            }, "home-server-ping");
            t.setDaemon(true);
            t.start();
        }
        return q.card;
    }

    /** Current global rank at a glance; opens the Ranks page. Read from a fresh snapshot each time Home is rebuilt. */
    private Card rankCard() {
        com.astrolauncher.rank.RankSnapshot snap = com.astrolauncher.rank.RankService.get().snapshot();
        Label title = new Label();
        Label hint = new Label();
        if (!snap.signedIn()) {
            title.setText("Earn a global rank");
            hint.setText("Sign in with Astro ID to earn Astro Points from playtime and quests.");
        } else {
            com.astrolauncher.rank.RankStanding st = snap.standing();
            title.setText(st.label() + "  \u00B7  " + String.format(java.util.Locale.US, "%,d", snap.ap()) + " AP");
            hint.setText((st.isMax() ? "Highest rank reached." : String.format(java.util.Locale.US, "%,d", st.apToNext()) + " AP to " + st.nextLabel() + ".")
                    + " " + snap.dayPlaytimeAp() + "/" + snap.dailyCapAp() + " playtime AP today.");
        }
        String rankImg = "/images/ranks/stone.png";
        String pill = snap.signedIn() ? "Ranked" : "Unranked";
        if (snap.signedIn()) {
            String lab = snap.standing().label().toLowerCase(java.util.Locale.ROOT);
            for (String r : new String[] {"copper","stone","iron","gold","emerald","sapphire","amethyst","obsidian","celestial"}) {
                if (lab.contains(r)) { rankImg = "/images/ranks/" + r + ".png"; break; }
            }
        }
        return quickCard(rankImg, pill, snap.signedIn() ? "ok" : "idle", title, hint, () -> navigator.accept(NavPage.RANKS)).card;
    }

    private Card capeCard() {
        Label title = new Label();
        Label hint = new Label();
        final Runnable[] go = { () -> navigator.accept(NavPage.STORE) };
        QuickCard q = quickCard("/images/capes.png", "Checking\u2026", "idle", title, hint, () -> go[0].run());

        Skeleton s1 = Skeleton.line(150);
        Skeleton s2 = Skeleton.line(210);
        s1.setVisible(false);
        s2.setVisible(false);
        s1.managedProperty().bind(s1.visibleProperty());
        s2.managedProperty().bind(s2.visibleProperty());
        q.text.getChildren().addAll(s1, s2);

        capeViews.add(st -> {
            boolean loading = st.kind() == CapeKind.LOADING;
            title.setVisible(!loading); title.setManaged(!loading);
            hint.setVisible(!loading);  hint.setManaged(!loading);
            s1.setVisible(loading);
            s2.setVisible(loading);
            switch (st.kind()) {
                case EQUIPPED -> { q.setPill("Equipped", "ok"); title.setText(st.name()); hint.setText("Equipped. Open the Store to change it."); go[0] = () -> navigator.accept(NavPage.STORE); }
                case NONE -> { q.setPill("No cape", "bad"); title.setText("No cape yet"); hint.setText("Browse capes in the Store."); go[0] = () -> navigator.accept(NavPage.STORE); }
                case SIGNED_OUT -> { q.setPill("Signed out", "idle"); title.setText("Get a cape"); hint.setText("Sign in with Astro ID to wear one."); go[0] = () -> navigator.accept(NavPage.ACCOUNTS); }
                case ERROR -> { q.setPill("Error", "bad"); title.setText(st.name()); hint.setText("Click to try again."); go[0] = this::loadCape; }
                default -> { }
            }
        });
        return q.card;
    }

    /** A status card: its pill is mutable so async results (ping, cape lookup) can update it. */
    private record QuickCard(Card card, VBox text, Label pillLabel) {
        void setPill(String text, String kind) {
            pillLabel.setText(text);
            pillLabel.getStyleClass().removeAll("home-pill-ok", "home-pill-bad", "home-pill-idle");
            pillLabel.getStyleClass().add("home-pill-" + kind);
            pillLabel.setVisible(text != null);
            pillLabel.setManaged(text != null);
        }
    }

    private QuickCard quickCard(String imagePath, String pillText, String pillKind, Label title, Label hint, Runnable go) {
        Label pill = new Label();
        pill.getStyleClass().add("home-pill");
        title.getStyleClass().add("home-quick-title");
        // BUGFIX: a single-line ellipsis cut short titles that fit fine once wrapped (e.g. "2 saved
        // s…", "Amethyst…") even though the hint line right below wraps to several lines in the same
        // column. Wrap the title too instead of truncating it.
        title.setWrapText(true);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(Double.MAX_VALUE);
        hint.getStyleClass().add("home-quick-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        HBox pillRow = new HBox(pill);
        pillRow.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(6, pillRow, title, hint);
        text.setMinWidth(0);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label arrow = new Label("\u2192");
        arrow.getStyleClass().add("home-quick-arrow");
        StackPane arrowBox = new StackPane(arrow);
        arrowBox.getStyleClass().add("home-quick-arrow-box");
        arrowBox.setMinSize(34, 34);
        arrowBox.setMaxSize(34, 34);
        VBox arrowCol = new VBox(arrowBox);
        arrowCol.setAlignment(Pos.BOTTOM_RIGHT);

        StackPane thumb = new StackPane();
        thumb.getStyleClass().add("home-thumb");
        thumb.setMinSize(120, 0);
        thumb.setPrefSize(120, 0);
        thumb.setMaxSize(120, Double.MAX_VALUE);
        Image img = loadClasspathImage(imagePath);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(false);
            iv.setSmooth(true);
            iv.fitWidthProperty().bind(thumb.widthProperty());
            iv.fitHeightProperty().bind(thumb.heightProperty());
            // "Cover" crop: keep the artwork's proportions and trim the overflow, never stretch it.
            Runnable cover = () -> {
                double w = thumb.getWidth(), h = thumb.getHeight();
                if (w <= 0 || h <= 0) return;
                double iw = img.getWidth(), ih = img.getHeight();
                double scale = Math.max(w / iw, h / ih);
                double vw = w / scale, vh = h / scale;
                iv.setViewport(new javafx.geometry.Rectangle2D((iw - vw) / 2, (ih - vh) / 2, vw, vh));
            };
            thumb.widthProperty().addListener((o, a1, b1) -> cover.run());
            thumb.heightProperty().addListener((o, a1, b1) -> cover.run());
            thumb.getChildren().add(iv);

            // The art dissolves into the card toward the text instead of ending in a hard edge.
            // Painted directly (not via a CSS -fx-background-color gradient) so it's not at the mercy
            // of the stylesheet's gradient parsing — this project has a history of linear-gradient
            // rules JavaFX silently fails to parse, which is exactly what happened to the CSS version
            // of this fade (no visible dissolve at all, just the raw image to a hard edge).
            javafx.scene.paint.Color cardBg = javafx.scene.paint.Color.web("#120a35");
            javafx.scene.paint.LinearGradient fadeGradient = new javafx.scene.paint.LinearGradient(
                    0, 0, 1, 0, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                    new javafx.scene.paint.Stop(0.00, javafx.scene.paint.Color.TRANSPARENT),
                    new javafx.scene.paint.Stop(0.32, javafx.scene.paint.Color.TRANSPARENT),
                    new javafx.scene.paint.Stop(0.75, cardBg.deriveColor(0, 1, 1, 0.70)),
                    new javafx.scene.paint.Stop(1.00, cardBg));
            Rectangle fade = new Rectangle();
            fade.widthProperty().bind(thumb.widthProperty());
            fade.heightProperty().bind(thumb.heightProperty());
            fade.setFill(fadeGradient);
            fade.setMouseTransparent(true);
            thumb.getChildren().add(fade);
        }
        Rectangle tclip = new Rectangle();
        tclip.widthProperty().bind(thumb.widthProperty());
        tclip.heightProperty().bind(thumb.heightProperty());
        tclip.setArcWidth(32);
        tclip.setArcHeight(32);
        thumb.setClip(tclip);

        HBox body = new HBox(12, text, arrowCol);
        body.setPadding(new Insets(14, 14, 14, 0));
        HBox.setHgrow(body, Priority.ALWAYS);
        body.setAlignment(Pos.CENTER_LEFT);
        HBox inner = new HBox(8, thumb, body);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setMinHeight(132);
        inner.setPrefHeight(132);
        inner.setMaxHeight(150);

        Card card = new Card(inner).interactive();
        card.setMaxHeight(150);
        card.getStyleClass().add("home-quick");
        card.setCursor(javafx.scene.Cursor.HAND);
        card.hoverProperty().addListener((o, was, hovering) ->
                Motion.animate(arrow.translateXProperty(), hovering ? 3.0 : 0.0, Motion.HOVER));
        card.setFocusTraversable(true);
        card.setAccessibleText(title.getText());
        card.setOnMouseClicked(e -> go.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) go.run();
        });
        QuickCard q = new QuickCard(card, text, pill);
        q.setPill(pillText, pillKind);
        return q;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Community
    // ─────────────────────────────────────────────────────────────────────────

    private Node buildCommunity() {
        Label title = new Label("COMMUNITY");
        title.getStyleClass().add("av-section-title");

        // Three cards in a loop; two are visible at a time and the strip slides one card per step:
        // [Astrix, PINOYCraft] -> [PINOYCraft, Discord] -> [Discord, Astrix] -> ...
        List<Region> items = List.of(hostCard(), serverCard(), new DiscordCard(DISCORD_URL));
        final int n = items.size();
        final double GAP = 16;

        HBox track = new HBox(GAP);
        track.setAlignment(Pos.TOP_LEFT);
        track.setFillHeight(true);

        Pane viewport = new Pane(track) {
            @Override protected double computePrefHeight(double width) {
                double wv = width > 0 ? width : getWidth();
                double half = wv > 0 ? (wv - GAP) / 2 : -1;
                double hmax = 0;
                for (Region c : items) hmax = Math.max(hmax, c.prefHeight(half));
                return hmax;
            }
            @Override protected void layoutChildren() {
                double half = (getWidth() - GAP) / 2;
                track.resizeRelocate(0, 0, half * n + GAP * (n - 1), getHeight());
                for (Region c : items) {
                    c.setMinWidth(half);
                    c.setPrefWidth(half);
                    c.setMaxWidth(half);
                }
            }
        };
        viewport.setMinHeight(Region.USE_PREF_SIZE);
        Rectangle vclip = new Rectangle();
        vclip.widthProperty().bind(viewport.widthProperty());
        vclip.heightProperty().bind(viewport.heightProperty().add(40));   // room for card shadows below
        viewport.setClip(vclip);

        // Page dots (clickable) on the right of the heading.
        HBox dots = new HBox(6);
        dots.setAlignment(Pos.CENTER_RIGHT);
        List<Region> dotNodes = new java.util.ArrayList<>();
        for (int d = 0; d < n; d++) {
            Region dot = new Region();
            dot.getStyleClass().add("home-rot-dot");
            dot.setCursor(javafx.scene.Cursor.HAND);
            dotNodes.add(dot);
            dots.getChildren().add(dot);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(title, spacer, dots);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, header, viewport);
        final int[] index = {0};
        final boolean[] busy = {false};
        Runnable apply = () -> {
            List<Node> ordered = new java.util.ArrayList<>();
            for (int k = 0; k < n; k++) ordered.add(items.get((index[0] + k) % n));
            track.getChildren().setAll(ordered);
            track.setTranslateX(0);
            for (Region c : items) { c.setOpacity(1); c.setScaleX(1); c.setScaleY(1); }
            for (int d = 0; d < n; d++) {
                dotNodes.get(d).getStyleClass().remove("active");
                if (d == index[0]) dotNodes.get(d).getStyleClass().add("active");
            }
        };
        apply.run();

        Runnable rotate = () -> {
            if (busy[0]) return;
            if (!Motion.enabled() || box.getScene() == null) {
                index[0] = (index[0] + 1) % n;
                apply.run();
                return;
            }
            busy[0] = true;
            double step = (viewport.getWidth() - GAP) / 2 + GAP;
            Region leaving = items.get(index[0]);
            Region incoming = items.get((index[0] + 2) % n);
            incoming.setOpacity(0.0);
            incoming.setScaleX(0.92);
            incoming.setScaleY(0.92);
            javafx.animation.Interpolator ease = javafx.animation.Interpolator.SPLINE(0.65, 0.0, 0.25, 1.0);
            javafx.animation.Timeline slide = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(Duration.ZERO,
                            new javafx.animation.KeyValue(track.translateXProperty(), 0, ease),
                            new javafx.animation.KeyValue(leaving.opacityProperty(), 1, ease),
                            new javafx.animation.KeyValue(leaving.scaleXProperty(), 1, ease),
                            new javafx.animation.KeyValue(leaving.scaleYProperty(), 1, ease),
                            new javafx.animation.KeyValue(incoming.opacityProperty(), 0, ease),
                            new javafx.animation.KeyValue(incoming.scaleXProperty(), 0.92, ease),
                            new javafx.animation.KeyValue(incoming.scaleYProperty(), 0.92, ease)),
                    new javafx.animation.KeyFrame(Duration.millis(900),
                            new javafx.animation.KeyValue(track.translateXProperty(), -step, ease),
                            new javafx.animation.KeyValue(leaving.opacityProperty(), 0, ease),
                            new javafx.animation.KeyValue(leaving.scaleXProperty(), 0.92, ease),
                            new javafx.animation.KeyValue(leaving.scaleYProperty(), 0.92, ease),
                            new javafx.animation.KeyValue(incoming.opacityProperty(), 1, ease),
                            new javafx.animation.KeyValue(incoming.scaleXProperty(), 1, ease),
                            new javafx.animation.KeyValue(incoming.scaleYProperty(), 1, ease)));
            slide.setOnFinished(ev -> {
                index[0] = (index[0] + 1) % n;
                apply.run();
                busy[0] = false;
            });
            slide.play();
        };
        javafx.animation.Timeline auto = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(7), e -> rotate.run()));
        auto.setCycleCount(javafx.animation.Animation.INDEFINITE);

        for (int d = 0; d < n; d++) {
            final int target = d;
            dotNodes.get(d).setOnMouseClicked(e -> {
                if (busy[0]) return;
                index[0] = target;
                apply.run();
                if (auto.getStatus() == javafx.animation.Animation.Status.RUNNING) auto.playFromStart();
            });
        }
        // Rotate on its own; hold still while the pointer is over the cards, and stop when the page is left.
        box.hoverProperty().addListener((o, was, hover) -> {
            if (!Motion.enabled() || box.getScene() == null) return;
            if (hover) auto.pause(); else auto.play();
        });
        box.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null && Motion.enabled()) auto.play(); else auto.stop();
        });
        return box;
    }

    /**
     * Discord invite card: the artwork with its baked-in link and button removed, and a real link label
     * and a real Join button laid over the same spots, scaled with the card.
     */
    private static final class DiscordCard extends Pane {
        private static final double RATIO = 941.0 / 1672.0;
        private final ImageView art = new ImageView();
        private final ImageView artTop = new ImageView();   // stretched top edge row (fills extra height)
        private final ImageView artBottom = new ImageView(); // stretched bottom edge row
        private final HBox linkRow = new HBox();
        private final Label linkHead = new Label("discord.gg/");
        private final Label linkTail = new Label();
        private final javafx.scene.shape.SVGPath linkIcon = new javafx.scene.shape.SVGPath();
        private final Button join = new Button("Join");
        private final javafx.scene.shape.SVGPath joinIcon = new javafx.scene.shape.SVGPath();

        DiscordCard(String url) {
            getStyleClass().add("home-discord-card");
            String tail = url.substring(url.lastIndexOf('/') + 1);
            linkTail.setText(tail);

            Image img = loadClasspathImageStatic("/images/discord-card.png");
            if (img != null) {
                art.setImage(img);
                art.setSmooth(true);
                art.setPreserveRatio(false);
                art.setMouseTransparent(true);
                for (ImageView e : new ImageView[] {artTop, artBottom}) {
                    e.setImage(img);
                    e.setSmooth(true);
                    e.setPreserveRatio(false);
                    e.setMouseTransparent(true);
                }
                artTop.setViewport(new javafx.geometry.Rectangle2D(0, 0, img.getWidth(), 2));
                artBottom.setViewport(new javafx.geometry.Rectangle2D(0, img.getHeight() - 2, img.getWidth(), 2));
                getChildren().addAll(artTop, artBottom, art);
            }

            linkIcon.setContent("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71");
            linkIcon.setFill(null);
            linkIcon.setStroke(javafx.scene.paint.Color.WHITE);
            linkIcon.setStrokeWidth(2.2);
            linkIcon.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            linkHead.setStyle("-fx-text-fill: white; -fx-font-weight: 700;");
            linkTail.setStyle("-fx-text-fill: #8f88ff; -fx-font-weight: 700;");
            linkRow.getChildren().addAll(new Group(linkIcon), linkHead, linkTail);
            linkRow.setAlignment(Pos.CENTER_LEFT);
            linkRow.setCursor(javafx.scene.Cursor.HAND);
            linkRow.setOnMouseClicked(e -> open(url));
            getChildren().add(linkRow);

            try {
                joinIcon.setContent("M20.317 4.37a19.79 19.79 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.74 19.74 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.09 14.09 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.1 13.1 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.3 12.3 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.84 19.84 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z");
                joinIcon.setFill(javafx.scene.paint.Color.WHITE);
                join.setGraphic(new Group(joinIcon));
            } catch (Exception ignored) { }
            join.getStyleClass().add("home-discord-join");
            join.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
            join.setCursor(javafx.scene.Cursor.HAND);
            join.setOnAction(e -> open(url));
            getChildren().add(join);

            setMinHeight(USE_PREF_SIZE);
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            clip.setArcWidth(40);
            clip.setArcHeight(40);
            setClip(clip);
        }

        private static void open(String url) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ignored) { }
        }

        @Override
        protected double computePrefHeight(double width) {
            return (width > 0 ? width : 540) * RATIO;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // The art always spans the full width (nothing is cropped from the sides). If the card is taller
            // than the art, the art stays centred and its top / bottom edge rows are stretched to fill the rest.
            double dw = w, dh = w * RATIO;
            if (dh > h) { dh = h; dw = h / RATIO; }
            double ox = (w - dw) / 2, oy = (h - dh) / 2;
            if (art.getImage() != null) {
                art.setFitWidth(dw);
                art.setFitHeight(dh);
                art.relocate(ox, oy);
                artTop.setFitWidth(dw);
                artTop.setFitHeight(oy + 1);
                artTop.relocate(ox, 0);
                artBottom.setFitWidth(dw);
                artBottom.setFitHeight(oy + 1);
                artBottom.relocate(ox, oy + dh - 1);
            }

            // Link row: where the baked link sat (x 47.8%..95%, centred at y 59%). Shrink the text to fit the panel.
            double maxW = dw * 0.455;
            double fs = Math.max(9, dw * 0.032);
            linkRow.setSpacing(fs * 0.6);
            for (int pass = 0; pass < 6; pass++) {
                String font = "-fx-font-size: " + fs + "px;";
                linkHead.setStyle("-fx-text-fill: white; -fx-font-weight: 700;" + font);
                linkTail.setStyle("-fx-text-fill: #8f88ff; -fx-font-weight: 700;" + font);
                double iconScale = (fs * 1.2) / 24.0;
                linkIcon.setScaleX(iconScale);
                linkIcon.setScaleY(iconScale);
                linkRow.setSpacing(fs * 0.6);
                linkRow.applyCss();
                linkRow.autosize();
                double wNow = linkRow.getWidth();
                if (wNow <= maxW) break;
                fs = fs * maxW / wNow * 0.97;
            }
            linkRow.relocate(ox + dw * 0.478, oy + dh * 0.59 - linkRow.getHeight() / 2);

            // Join button: where the baked one sat (x 54.4%..95.4%, y 67.2%..84.5%).
            double bw = dw * 0.411, bh = dh * 0.173;
            join.setStyle("-fx-font-size: " + Math.max(10, dw * 0.044) + "px;");
            double js = (bh * 0.5) / 24.0;
            joinIcon.setScaleX(js);
            joinIcon.setScaleY(js);
            join.setGraphicTextGap(dw * 0.015);
            join.resizeRelocate(ox + dw * 0.544, oy + dh * 0.672, bw, bh);
        }
    }

    private static Image loadClasspathImageStatic(String path) {
        try (java.io.InputStream in = HomePage.class.getResourceAsStream(path)) {
            if (in == null) return null;
            Image img = new Image(in);
            return img.isError() ? null : img;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String DISCORD_URL = "https://discord.gg/7BgEPqHyhN";

    private static final double BANNER_H = 132;

    /**
     * Partner card layout: a banner of art on top (clipped), a logo tile that overlaps the banner's
     * lower edge, then name / description / chips pinned to the bottom so both cards line up.
     */
    private Card partnerCard(Node bannerArt, Node logo, String accent, String kicker, String name, String desc, List<String> chips, Node action) {
        StackPane art = new StackPane(bannerArt);
        art.setMinHeight(BANNER_H);
        art.setPrefHeight(BANNER_H);
        art.setMaxHeight(BANNER_H);
        Region scrim = new Region();
        scrim.getStyleClass().add("home-banner-scrim");
        scrim.setMouseTransparent(true);
        Region accentLine = new Region();     // thin coloured edge along the top of the card
        accentLine.getStyleClass().add("home-accent-" + accent);
        accentLine.setMinHeight(3);
        accentLine.setPrefHeight(3);
        accentLine.setMaxHeight(3);
        StackPane.setAlignment(accentLine, Pos.TOP_CENTER);
        art.getChildren().addAll(scrim, ValDeco.slashes(0.55), accentLine);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(art.widthProperty());
        clip.heightProperty().bind(art.heightProperty());
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        art.setClip(clip);

        Label tag = new Label("PARTNER");
        tag.getStyleClass().addAll("av-chip", "av-chip-accent");
        StackPane.setAlignment(tag, Pos.TOP_RIGHT);
        StackPane.setMargin(tag, new Insets(12, 12, 0, 0));

        StackPane logoTile = new StackPane(logo);
        logoTile.getStyleClass().addAll("home-logo-tile", "home-logo-" + accent);
        logoTile.setMinSize(64, 64);
        logoTile.setPrefSize(64, 64);
        logoTile.setMaxSize(64, 64);
        logoTile.setTranslateY(32);   // overlaps the banner's lower edge
        StackPane.setAlignment(logoTile, Pos.BOTTOM_LEFT);
        StackPane.setMargin(logoTile, new Insets(0, 0, 0, 20));

        StackPane banner = new StackPane(art, tag, logoTile);

        Label k = new Label(kicker);
        k.getStyleClass().addAll("home-partner-kicker", "home-kicker-" + accent);
        Label n = new Label(name);
        n.getStyleClass().add("home-partner-name");
        Label d = new Label(desc);
        d.getStyleClass().add("home-partner-desc");
        d.setWrapText(true);
        d.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.layout.FlowPane chipRow = new javafx.scene.layout.FlowPane(6, 6);   // wraps instead of truncating
        for (String c : chips) {
            Label l = new Label(c);
            l.getStyleClass().add("av-chip");
            chipRow.getChildren().add(l);
        }

        Region spring = new Region();
        VBox.setVgrow(spring, Priority.ALWAYS);
        VBox body = new VBox(6, k, n, d, spring, chipRow);
        if (action != null) body.getChildren().add(action);
        body.getStyleClass().add("home-partner-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox content = new VBox(banner, body);
        content.setMaxHeight(Double.MAX_VALUE);
        return new Card(content);
    }

    private static final String HOST_URL = "https://www.astrixhostings.xyz/";
    private static final String HOST_PANEL_URL = "https://panel.astrixhostings.xyz/";

    /**
     * Finds the Astrix banner. Tries a few likely file names inside the bundled images folder, then the
     * same names next to the app / project root (no rebuild needed). Logs what it tried if none loads.
     */
    private Image loadAstrixBanner() {
        String[] names = {"astrix-banner", "astrix-hostings", "astrix", "astrixhostings", "astrix_banner", "banner-astrix"};
        String[] exts = {".png", ".jpg", ".jpeg", ".webp"};
        for (String n : names) {
            for (String e : exts) {
                Image img = loadClasspathImage("/images/" + n + e);
                if (img != null) return img;
            }
        }
        for (String n : names) {
            for (String e : exts) {
                java.io.File f = new java.io.File(n + e);
                if (f.isFile()) {
                    try {
                        Image img = new Image(f.toURI().toString());
                        if (!img.isError()) return img;
                    } catch (Exception ignored) { }
                }
            }
        }
        com.astrolauncher.logging.AppLogger.get().warn("HomePage",
                "Astrix banner not found. Put it at src/main/resources/images/astrix-banner.png and rebuild "
                        + "(or next to the app as astrix-banner.png).");
        return null;
    }

    /** Logo that fills the whole tile (inside its 2px ring) with rounded corners, so no dark gap shows around it. */
    private static Node partnerLogo(Image img) {
        double size = 60;
        ImageView iv = new ImageView(img);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(false);   // the logos are square; fill the tile exactly
        iv.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        iv.setClip(clip);
        return iv;
    }

    private Card hostCard() {
        // Banner: the Astrix Hostings artwork, scaled to cover (falls back to a gold gradient if the image is missing).
        StackPane art = new StackPane();
        Region gradient = new Region();
        gradient.getStyleClass().add("home-banner-astrix");
        art.getChildren().add(gradient);
        Image bg = loadAstrixBanner();
        if (bg != null) {
            ImageView cover = new ImageView(bg);
            cover.setSmooth(true);
            cover.setPreserveRatio(false);
            cover.setMouseTransparent(true);
            double iw = bg.getWidth(), ih = bg.getHeight();
            DoubleBinding scale = Bindings.createDoubleBinding(
                    () -> Math.max(Math.max(art.getWidth(), 1) / iw, BANNER_H / ih), art.widthProperty());
            cover.fitWidthProperty().bind(scale.multiply(iw));
            cover.fitHeightProperty().bind(scale.multiply(ih));
            art.getChildren().add(cover);
        }

        Node logo;
        Image astrixLogo = null;
        for (String n : new String[] {"astrix-logo", "astrix_logo", "astrixlogo", "astrix-hostings-logo"}) {
            astrixLogo = loadClasspathImage("/images/" + n + ".png");
            if (astrixLogo != null) break;
        }
        if (astrixLogo != null) {
            logo = partnerLogo(astrixLogo);
        } else {
            Label mono = new Label("A");
            mono.getStyleClass().add("home-logo-mono");
            logo = mono;
        }

        AvButton visit = new AvButton("Visit website", AvButton.Kind.GHOST);
        visit.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(HOST_URL));
            } catch (Exception ignored) { }
        });
        AvButton panel = new AvButton("Game panel", AvButton.Kind.GHOST);
        panel.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(HOST_PANEL_URL));
            } catch (Exception ignored) { }
        });
        HBox visitRow = new HBox(10, visit, panel);
        visitRow.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(visitRow, new Insets(4, 0, 0, 0));

        return partnerCard(art, logo, "gold", "HOSTING", "Astrix Hostings",
                "Proudly Sri Lanka\u2019s first and #1 hosting provider, with our servers located right here in Sri Lanka.",
                List.of("INSTANT SETUP", "DDOS PROTECTED", "24/7 SUPPORT"), visitRow);
    }

    private Card serverCard() {
        StackPane art = new StackPane();
        Region gradient = new Region();
        gradient.getStyleClass().add("home-banner-server");
        art.getChildren().add(gradient);
        Image bg = loadClasspathImage("/images/bg.png");
        if (bg != null) {
            ImageView cover = new ImageView(bg);
            cover.setSmooth(true);
            cover.setPreserveRatio(false);
            cover.setMouseTransparent(true);
            double iw = bg.getWidth(), ih = bg.getHeight();
            DoubleBinding scale = Bindings.createDoubleBinding(
                    () -> Math.max(Math.max(art.getWidth(), 1) / iw, BANNER_H / ih), art.widthProperty());
            cover.fitWidthProperty().bind(scale.multiply(iw));
            cover.fitHeightProperty().bind(scale.multiply(ih));
            art.getChildren().add(cover);
        }

        Node logo;
        Image pinoy = loadClasspathImage("/images/pinoy.png");
        if (pinoy != null) {
            logo = partnerLogo(pinoy);
        } else {
            Label mono = new Label("P");
            mono.getStyleClass().add("home-logo-mono");
            logo = mono;
        }

        Label ip = new Label(COPY_IP);
        ip.getStyleClass().add("home-partner-ip");
        HBox.setHgrow(ip, Priority.ALWAYS);
        ip.setMaxWidth(Double.MAX_VALUE);
        AvButton copy = new AvButton("Copy IP", AvButton.Kind.GHOST);
        copy.setOnAction(e -> {
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(COPY_IP), null);
            } catch (Exception ignored) { }
            String original = copy.getText();
            copy.setText("Copied!");
            PauseTransition revert = new PauseTransition(Duration.millis(1400));
            revert.setOnFinished(ev -> copy.setText(original));
            revert.play();
        });
        // Live status: ping the server off the FX thread and show "Online - 12/100" (or Offline) on the IP row.
        Label status = new Label("Checking\u2026");
        status.getStyleClass().add("home-partner-status");
        Thread pinger = new Thread(() -> {
            String text;
            try {
                com.astrolauncher.servers.ServerPinger.PingResult r = com.astrolauncher.servers.ServerPinger.ping(COPY_IP);
                text = r.online
                        ? "Online \u00B7 " + r.onlinePlayers + "/" + r.maxPlayers
                        : "Offline";
            } catch (Exception ex) {
                text = "Offline";
            }
            final String t = text;
            Platform.runLater(() -> {
                status.setText(t);
                status.getStyleClass().removeAll("status-online", "status-offline");
                status.getStyleClass().add(t.startsWith("Online") ? "status-online" : "status-offline");
            });
        }, "home-pinoy-ping");
        pinger.setDaemon(true);
        pinger.start();
        HBox ipRow = new HBox(10, status, ip, copy);
        ipRow.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(ipRow, new Insets(4, 0, 0, 0));

        return partnerCard(art, logo, "red", "SERVER", "PINOYCraft",
                "The community's favourite icy-biome survival server.",
                List.of("SURVIVAL", "SKYBLOCK", "PRISON"), ipRow);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player skin (3D hero model)
    // ─────────────────────────────────────────────────────────────────────────

    private int skinTicket;

    private Account activeAccount() {
        String id = settings.activeAccountId;
        if (id == null) return null;
        return accountRepository.load().stream()
                .filter(a -> id.equals(a.id))
                .findFirst().orElse(null);
    }

    /**
     * Shows the active account's skin on the 3D model. Source order mirrors the Play page: the library
     * skin an offline account wears, then the skin URL Mojang reported, then (Microsoft accounts only)
     * a lookup by name. Anything missing or unreadable falls back to a short hint on the empty tile.
     */
    private void loadSkin(SkinModel3D model, Label tag, Label name, StackPane headSlot) {
        final int mine = ++skinTicket;
        headSlot.getChildren().clear();
        Account acct = activeAccount();
        if (acct == null) {
            model.showEmpty("No account");
            name.setText("Add an account");
            tag.setText("OPEN ACCOUNTS");
            return;
        }
        name.setText(acct.username != null ? acct.username : "Player");
        tag.setText("YOUR SKIN");

        java.util.concurrent.CompletableFuture<SkinModel3D.Prepared> job;
        boolean offline = acct.type != Account.Type.MICROSOFT;
        Path local = null;
        if (acct.offlineSkinId != null && !acct.offlineSkinId.isBlank()) {
            Path p = new SkinLibrary().pngPath(acct.offlineSkinId);
            if (Files.isRegularFile(p)) local = p;
        }
        final Path localPng = local;
        final SkinService skins = new SkinService();

        if (localPng != null) {
            model.showLoading();
            job = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    long stamp = Files.getLastModifiedTime(localPng).toMillis();
                    return SkinModel3D.prepare(Files.readAllBytes(localPng), acct.offlineSkinVariant, localPng + "|" + stamp);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (acct.skinUrl != null && !acct.skinUrl.isBlank()) {
            model.showLoading();
            job = skins.download(acct.skinUrl).thenApplyAsync(png -> {
                try {
                    return SkinModel3D.prepare(png, acct.skinVariant, acct.skinUrl);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (!offline && acct.username != null && !acct.username.isBlank()) {
            model.showLoading();
            job = skins.fetchByUsername(acct.username).thenApplyAsync(rs -> {
                try {
                    return SkinModel3D.prepare(rs.png, rs.variant, "name:" + acct.username);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            model.showEmpty("No skin");
            tag.setText("SET ONE IN ACCOUNTS");
            return;
        }

        job.whenComplete((prepared, err) -> Platform.runLater(() -> {
            if (mine != skinTicket) return;   // Home was rebuilt meanwhile
            if (err != null || prepared == null) {
                model.showEmpty("No skin");
                tag.setText("SET ONE IN ACCOUNTS");
                return;
            }
            model.show(prepared);
            headSlot.getChildren().setAll(SkinModel3D.head(prepared, 26));   // player head before the name
        }));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cape state (display only: reads what Astro ID already exposes)
    // ─────────────────────────────────────────────────────────────────────────

    private enum CapeKind { SIGNED_OUT, LOADING, NONE, EQUIPPED, ERROR }

    private record CapeState(CapeKind kind, String id, String name, String textureUrl) { }

    private void loadCape() {
        AstroId astro = AstroId.get();
        if (!astro.isSignedIn()) {
            lastCape = null;
            publish(new CapeState(CapeKind.SIGNED_OUT, null, null, null));
            return;
        }
        publish(lastCape != null ? lastCape : new CapeState(CapeKind.LOADING, null, null, null));
        final int mine = ++capeTicket;
        astro.api().cosmetics().whenComplete((c, err) -> Platform.runLater(() -> {
            if (mine != capeTicket) return;   // a newer request superseded this one
            if (err != null) {
                String raw = ErrorState.describe(err);
                String msg = ErrorState.isNetwork(raw) ? "Can't reach Astro right now" : "Couldn't load your cape";
                publish(lastCape != null ? lastCape : new CapeState(CapeKind.ERROR, null, msg, null));
                return;
            }
            if (c == null || c.equippedCape == null) {
                lastCape = new CapeState(CapeKind.NONE, null, null, null);
            } else {
                lastCape = new CapeState(CapeKind.EQUIPPED, c.equippedCape.id, c.equippedCape.name, c.equippedCape.textureUrl);
            }
            publish(lastCape);
        }));
    }

    private void publish(CapeState st) {
        for (Consumer<CapeState> v : new ArrayList<>(capeViews)) v.accept(st);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean mcDirExists() {
        return settings.minecraftDirectory != null && !settings.minecraftDirectory.isBlank()
                && Files.exists(Path.of(settings.minecraftDirectory));
    }

    private boolean versionSet() {
        return settings.defaultVersionId != null && !settings.defaultVersionId.isBlank();
    }

    private boolean isReady() {
        return mcDirExists() && versionSet();
    }

    private Account resolveActiveAccount() {
        if (settings.activeAccountId == null) return null;
        return accountRepository.load().stream()
                .filter(a -> a.id.equals(settings.activeAccountId))
                .findFirst().orElse(null);
    }

    /** Versions with a JSON on disk under versions/, most recently installed first. */
    private List<String> installedVersions() {
        List<String> out = new ArrayList<>();
        if (!mcDirExists()) return out;
        Path dir = Path.of(settings.minecraftDirectory, "versions");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.exists(jsonOf(d)))
                    .sorted(Comparator.comparingLong((Path d) -> jsonOf(d).toFile().lastModified()).reversed())
                    .limit(80)
                    .forEach(d -> out.add(d.getFileName().toString()));
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Path jsonOf(Path versionDir) {
        String id = versionDir.getFileName().toString();
        return versionDir.resolve(id + ".json");
    }

    /**
     * Cover-fits /images/hero-background.jpg across the hero (scaled by the larger ratio, cropped to the
     * banner). Returns null if the file isn't bundled, in which case the gradient alone shows.
     */
    private Node buildHeroPhoto(StackPane hero) {
        Image image = loadClasspathImage("/images/hero-background.jpg");
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        double imgW = image.getWidth(), imgH = image.getHeight();

        ImageView view = new ImageView(image);
        view.getStyleClass().add("home-hero-photo");
        view.setSmooth(true);
        view.setPreserveRatio(false);
        DoubleBinding scale = Bindings.createDoubleBinding(
                () -> Math.max(Math.max(hero.getWidth(), 1) / imgW, HERO_H / imgH), hero.widthProperty());
        view.fitWidthProperty().bind(scale.multiply(imgW));
        view.fitHeightProperty().bind(scale.multiply(imgH));
        StackPane.setAlignment(view, Pos.TOP_CENTER);
        view.setMouseTransparent(true);
        return view;
    }

    private Image loadClasspathImage(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            Image image = new Image(is);
            return image.isError() ? null : image;
        } catch (Exception e) {
            return null;
        }
    }
}