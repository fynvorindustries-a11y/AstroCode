package com.astrolauncher.ui;

import com.astrolauncher.auth.MicrosoftAuthService;
import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.LauncherSettings;
import com.astrolauncher.mods.ModManager;
import com.astrolauncher.repository.AccountRepository;
import com.astrolauncher.repository.SettingsRepository;
import com.astrolauncher.service.LaunchService;
import com.astrolauncher.service.ModrinthService;
import com.astrolauncher.ui.kit.AvButton;
import com.astrolauncher.ui.kit.FocusRing;
import com.astrolauncher.ui.kit.LeaderboardOverlay;
import com.astrolauncher.ui.kit.RankToast;
import com.astrolauncher.ui.kit.RankUpOverlay;
import com.astrolauncher.ui.pages.*;
import com.astrolauncher.update.UpdateChecker;
import com.astrolauncher.update.UpdateDownloader;
import com.astrolauncher.update.UpdateInfo;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.EnumMap;
import java.util.Map;

/**
 * Builds and owns the main application window. The shell is a stack of layers:
 *
 * <pre>
 *   column   top bar + page area (left inset = rail width, so pages never sit under the rail)
 *     page area   = pages (PageTransition)  +  update bar floating over the bottom
 *   scrim    dims the column while the rail is expanded over it
 *   sidebar  RailSidebar: 72 px icon rail that slides open to 240 px over everything above
 * </pre>
 *
 * Pages are constructed lazily on first visit to keep startup fast and idle memory low.
 */
public class MainWindow {

    private static final String TAG = "MainWindow";
    /** Opacity of the scrim at full strength (it is further multiplied by the -av-scrim token's alpha). */
    private static final double SCRIM_ALPHA = 0.8;

    private final Stage stage;
    private final LauncherSettings settings;
    private final SettingsRepository settingsRepository;
    private final AppContext appContext;

    private final StackPane pageHost = new StackPane();
    private final Map<NavPage, Parent> pageCache = new EnumMap<>(NavPage.class);
    private final Region scrim = new Region();
    private final BorderPane column = new BorderPane();
    private RailSidebar sidebar;
    private PageTransition transition;
    private UpdateBar updateBar;
    private RankToast rankToast;
    private RankUpOverlay rankUpOverlay;
    private LeaderboardOverlay leaderboardOverlay;

    // Top-bar account chip — refreshed on every navigation so it reflects account changes.
    private Label topBarAccountName;
    private Label topBarAccountTag;
    private Label topBarAvatarInitial;
    private StackPane topBarAvatar;
    private String topBarHeadKey = "";
    private int topBarHeadTicket;
    private AvButton topBarLoginButton;
    private final Map<NavPage, javafx.scene.control.Button> topNavButtons = new EnumMap<>(NavPage.class);
    private LabsPage labsPage;

    public MainWindow(Stage stage, LauncherSettings settings, SettingsRepository settingsRepository, LaunchService launchService,
                       ModrinthService modrinthService, com.astrolauncher.service.CurseForgeService curseForgeService,
                       ModManager modManager, AccountRepository accountRepository,
                       MicrosoftAuthService microsoftAuthService, UpdateChecker updateChecker,
                       UpdateDownloader updateDownloader) {
        this.stage = stage;
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.appContext = new AppContext(settings, settingsRepository, launchService, modrinthService, curseForgeService,
                modManager, accountRepository, microsoftAuthService, updateChecker, updateDownloader);
        ensureActiveAccount();
        // Lets the Astro ID keep the launcher's single Astro account in step with the sign-in.
        com.astrolauncher.store.AstroId.get().bind(appContext);
        // Global rank system: tracks verified playtime of games launched from here (needs Astro ID, see RankService).
        com.astrolauncher.rank.RankService.get().bind(appContext);
    }

    /**
     * Startup tidy-up of the two-sign-in model: hand-made offline profiles are dropped (the Astro account replaces
     * them) and the selected account is repaired (settings.json reset, an account removed, ...). With only one
     * of the Astro / Microsoft accounts signed in, that one is simply selected.
     */
    private void ensureActiveAccount() {
        if (com.astrolauncher.service.AccountChoice.ensureValid(appContext)) {
            AppLogger.get().info(TAG, "Account selection updated at startup.");
        }
    }

    /**
     * Registers every bundled font weight with JavaFX's font system before any CSS is applied, so
     * "-fx-font-family: 'Barlow'" combined with "-fx-font-weight" in the stylesheet resolves to the
     * correct static weight file instead of a synthetic bold.
     *
     * <p>Typography follows Riot's Valorant client: <b>Barlow</b> (a DIN-style grotesque) for body
     * text and <b>Barlow Condensed</b> (the closest free match to Tungsten) for headings, buttons and
     * labels. Both are SIL OFL 1.1 — see fonts/OFL-LICENSE-Barlow.txt.
     */
    private void loadCustomFonts() {
        // Body (regular/bold) + two display faces derived from Barlow Condensed Bold: "Astro Display"
        // (+2% tracking, titles/buttons/nav) and "Astro Tracked" (+9.5%, small caps labels).
        // JavaFX resolves by plain family name, so only the faces the stylesheet uses are loaded.
        String[] weights = {
                "/fonts/Barlow-Regular.ttf",
                "/fonts/Barlow-Bold.ttf",
                "/fonts/AstroDisplay-Bold.ttf",
                "/fonts/AstroTracked-Bold.ttf",
                // Monocraft (SIL OFL 1.1): the pixel face used for buttons, labels and tags so the
                // Valorant-style layout is dressed in Minecraft's own lettering.
                "/fonts/Monocraft-Regular.ttf",
                "/fonts/Monocraft-Bold.ttf",
                // Montserrat: used specifically by the home-page Play button (components.css
                // .av-btn-play). Bundled already but was never registered, so without loading it
                // here "-fx-font-family: Montserrat" silently fell back to the default system font
                // on any machine that didn't happen to already have Montserrat installed.
                "/fonts/Montserrat-Regular.ttf",
                "/fonts/Montserrat-Medium.ttf",
                "/fonts/Montserrat-SemiBold.ttf",
                "/fonts/Montserrat-Bold.ttf",
                "/fonts/Montserrat-Italic.ttf"
        };
        for (String path : weights) {
            try (var stream = getClass().getResourceAsStream(path)) {
                if (stream == null) {
                    AppLogger.get().warn(TAG, "Font resource not found: " + path);
                    continue;
                }
                Font loaded = Font.loadFont(stream, 12);
                if (loaded == null) {
                    AppLogger.get().warn(TAG, "Failed to load font: " + path);
                }
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Error loading font " + path + ": " + e.getMessage());
            }
        }
    }

    public Scene createScene() {
        loadCustomFonts();
        Motion.bind(settings);                      // one live switch for every animation (Low-End Mode)
        ShellState.bind(settings, settingsRepository); // "Pin sidebar open"

        sidebar = new RailSidebar(this::navigateTo, this::focusPage);
        updateBar = new UpdateBar(appContext, settings, settingsRepository);
        transition = new PageTransition(pageHost);

        // Page area: pages, with the update bar floating over the bottom edge (no reflow).
        pageHost.getStyleClass().add("av-page-host");
        rankToast = new RankToast();
        // Leaderboard "semipage": slides in over the page area (not the rail/top bar) from the right.
        leaderboardOverlay = new LeaderboardOverlay(this::navigateTo);
        StackPane pageLayer = new StackPane(pageHost, updateBar, rankToast, leaderboardOverlay);
        // Halloween season: pumpkins in the bottom-right corner, under the toasts so alerts stay readable.
        javafx.scene.image.ImageView pumpkins = com.astrolauncher.ui.kit.HalloweenDecor.create();
        if (pumpkins != null) {
            pageLayer.getChildren().add(1, pumpkins);
            StackPane.setAlignment(pumpkins, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(pumpkins, new Insets(0, 8, 4, 0));
        }
        StackPane.setAlignment(updateBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(updateBar, new Insets(0, 24, 16, 24));
        StackPane.setAlignment(rankToast, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(rankToast, new Insets(0, 24, 24, 24));
        installRankToasts();

        column.setTop(buildTopBar());
        column.setCenter(pageLayer);
        applyColumnInset(ShellState.isPinned());

        // Scrim: dims the page while the rail is open over it; a click on it closes the rail.
        scrim.getStyleClass().add("av-scrim");
        scrim.setOpacity(0);
        scrim.setVisible(false);
        scrim.setMouseTransparent(true);
        scrim.opacityProperty().addListener((o, was, now) -> scrim.setVisible(now.doubleValue() > 0.001));
        scrim.setOnMousePressed(e -> sidebar.collapseNow());

        sidebar.expandedProperty().addListener((o, was, now) -> syncScrim());
        ShellState.pinnedProperty().addListener((o, was, pinned) -> {
            applyColumnInset(pinned);   // pinned is a deliberate choice, so the page makes room for it
            sidebar.setPinned(pinned);
            syncScrim();
        });

        // Full-screen cinematic rank-up: sits above everything (sidebar included), built once and
        // reused for every promotion so it reads as one modal moment regardless of the current page.
        rankUpOverlay = new RankUpOverlay();

        // Boot splash: the character reveal, layered above absolutely everything else (sidebar
        // included) so it fully masks the shell underneath while it plays, then removes itself.
        com.astrolauncher.ui.kit.SplashScreen splash = new com.astrolauncher.ui.kit.SplashScreen();

        StackPane shell = new StackPane(column, scrim, sidebar, rankUpOverlay, splash);
        shell.getStyleClass().add("av-shell");
        StackPane.setAlignment(sidebar, Pos.TOP_LEFT);
        sidebar.setPinned(ShellState.isPinned());
        ValText.watch(shell); // sidebar + top bar (pages are watched as they are shown)

        Scene scene = new Scene(shell, 1280, 720);
        // Belt-and-suspenders against the Scene's own default WHITE fill showing through at the edges.
        scene.setFill(javafx.scene.paint.Color.web("#0b0726"));
        // Order matters: legacy sheet first, then the design system on top of it, then shell and per-page styles.
        for (String name : new String[] {"dark-theme", "tokens", "components", "shell", "pages", "rank", "rankup", "mc-theme", "splash", "astro-violet"}) {
            var css = getClass().getResource("/css/" + name + ".css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            } else {
                AppLogger.get().warn(TAG, "Stylesheet not found: " + name + ".css");
            }
        }
        AppLogger.get().info(TAG, "Loaded " + scene.getStylesheets().size() + " stylesheets; last = "
                + scene.getStylesheets().get(scene.getStylesheets().size() - 1).replaceAll(".*/", ""));
        FocusRing.install(scene);
        installShortcuts(scene);
        EasterEggs eggs = EasterEggs.install(scene, shell, rankToast);
        sidebar.setOnLogoClick(eggs::logoClicked);

        navigateTo(NavPage.HOME);

        // Played once the window is actually on screen, so the reveal is seen rather than
        // ticking away behind an unshown Stage.
        stage.setOnShown(e -> splash.playIntro(() -> shell.getChildren().remove(splash)));

        return scene;
    }

    /** Ctrl+1..9 jump to a page; Ctrl+B moves keyboard focus into the sidebar (Esc returns to the page). */
    private void installShortcuts(Scene scene) {
        // Fixed order so Ctrl+1..8 keep their original meaning; pages added later take the remaining numbers.
        NavPage[] pages = {NavPage.HOME, NavPage.PLAY, NavPage.MODS, NavPage.SERVERS, NavPage.STORE,
                NavPage.ACCOUNTS, NavPage.SETTINGS, NavPage.ABOUT, NavPage.RANKS}; // SERVERS opens Astro Labs > Servers
        for (int i = 0; i < pages.length && i < 9; i++) {
            NavPage p = pages[i];
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.valueOf("DIGIT" + (i + 1)), KeyCombination.CONTROL_DOWN),
                    () -> navigateTo(p));
        }
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN), () -> sidebar.focusNav());
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::replayRankUp);
    }

    /** The page area keeps a fixed left inset equal to the rail, so an overlaying rail never reflows it. */
    private void applyColumnInset(boolean pinned) {
        column.setPadding(new Insets(0, 0, 0, RailSidebar.RAIL_W)); // icon-only rail
    }

    private void syncScrim() {
        boolean dim = sidebar.expandedProperty().get() && !ShellState.isPinned();
        scrim.setMouseTransparent(!dim);
        Motion.animate(scrim.opacityProperty(), dim ? SCRIM_ALPHA : 0.0, Motion.PANEL);
    }

    /** Esc in the rail: give focus back to the page area. */
    private void focusPage() {
        // A plain pane only accepts programmatic focus while it is focus-traversable; make it so just
        // for this call so it doesn't become an extra Tab stop.
        column.setFocusTraversable(true);
        column.requestFocus();
        column.setFocusTraversable(false);
    }

    private void navigateTo(NavPage page) {
        // Servers no longer has its own page: it is a tab inside Astro Labs.
        boolean serversTab = page == NavPage.SERVERS;
        if (serversTab) page = NavPage.LABS;
        Parent panel = pageCache.computeIfAbsent(page, this::wrapScrollable);
        if (serversTab && labsPage != null) labsPage.showServers();
        ValText.watch(panel);
        sidebar.select(page);
        syncTopNav(page);
        refreshTopBarAccount();
        transition.show(panel);
        AppLogger.get().debug(TAG, "Navigated to " + page);
        // Checked on Home too (the page the launcher opens on), so a promotion earned while the launcher was
        // closed or in the background is celebrated as soon as the player sees the launcher again.
        if (page == NavPage.RANKS || page == NavPage.HOME) {
            com.astrolauncher.rank.RankService.get().refresh();   // pick up AP granted elsewhere
            Platform.runLater(() -> celebrateIfNew(com.astrolauncher.rank.RankService.get().snapshot().standing()));
        }
    }

    /**
     * Plays the rank-up cinematic for {@code to} if (and only if) it's higher than the last standing
     * already celebrated, then persists {@code to} as celebrated so it never replays. This is the
     * primary trigger (checked every time the Ranks page opens) because the live {@code onPromotion}
     * callback alone can be missed — the launcher window may not be focused when AP is credited during
     * play, or a sync/catch-up can grant a large batch of AP in one step. The live callback below still
     * fires it immediately when the window happens to be in front; whichever path runs first wins, and
     * {@code rankCelebratedKey} keeps the other from firing twice for the same standing.
     */
    private void celebrateIfNew(com.astrolauncher.rank.RankStanding to) {
        if (to == null || to.tier() == null || rankUpOverlay == null) return;
        String key = to.tier().id + ":" + to.division();
        if (key.equals(settings.rankCelebratedKey)) return;
        if (to.ap() <= 0) {
            // Brand-new standing with no AP earned yet: nothing to celebrate, just set the baseline
            // so the first real promotion (not this starting point) is what triggers the cinematic.
            settings.rankCelebratedKey = key;
            settingsRepository.save(settings);
            return;
        }
        com.astrolauncher.rank.RankConfig cfg = com.astrolauncher.rank.RankService.get().config();
        com.astrolauncher.rank.RankStanding from = com.astrolauncher.rank.RankCalculator.previousStep(cfg, to);
        // Only remember this standing as celebrated once the cinematic really started, so a failure
        // never silently swallows the promotion.
        if (playRankUp(cfg, from, to)) {
            settings.rankCelebratedKey = key;
            settingsRepository.save(settings);
        }
    }

    /** Plays the overlay; logs (instead of throwing into the FX thread) if anything in it fails. */
    private boolean playRankUp(com.astrolauncher.rank.RankConfig cfg, com.astrolauncher.rank.RankStanding from,
                               com.astrolauncher.rank.RankStanding to) {
        try {
            rankUpOverlay.play(cfg, from, to, null);
            return true;
        } catch (Throwable t) {
            AppLogger.get().error(TAG, "Rank-up animation failed: " + t, t);
            return false;
        }
    }

    /** The Home page's leaderboard button: slides the live leaderboard in as a semipage drawer. */
    private void openLeaderboard() {
        if (leaderboardOverlay != null) leaderboardOverlay.open();
    }

    /** Ctrl+Shift+R / the Ranks page "Replay" button: plays the cinematic for the current rank on demand. */
    private void replayRankUp() {
        com.astrolauncher.rank.RankStanding to = com.astrolauncher.rank.RankService.get().snapshot().standing();
        if (to == null || to.tier() == null) return;
        com.astrolauncher.rank.RankConfig cfg = com.astrolauncher.rank.RankService.get().config();
        playRankUp(cfg, com.astrolauncher.rank.RankCalculator.previousStep(cfg, to), to);
    }

    /**
     * Refreshes the top-bar account chip. It reflects BOTH sign-ins: the active Minecraft account and
     * the Astro ID. "Not signed in / SIGN IN" only shows when the player really isn't signed in to anything.
     */
    private void refreshTopBarAccount() {
        if (topBarAccountName == null) return; // not built yet
        java.util.List<com.astrolauncher.model.Account> allAccounts = appContext.accountRepository.load();
        com.astrolauncher.model.Account active = appContext.settings.activeAccountId != null
                ? allAccounts.stream().filter(a -> a.id.equals(appContext.settings.activeAccountId)).findFirst().orElse(null)
                : null;
        com.astrolauncher.store.StoreApi.AstroSession astro = com.astrolauncher.store.AstroId.get().session();
        boolean astroIn = astro != null;

        String name, tag;
        boolean showSignIn;
        if (active != null) {
            name = active.username;
            tag = active.type == com.astrolauncher.model.Account.Type.MICROSOFT ? "Microsoft account" : "Astro account";
            showSignIn = false;
        } else if (astroIn) {
            name = astro.username != null && !astro.username.isBlank() ? astro.username : "Astro ID";
            tag = "Astro account";
            showSignIn = false;
        } else {
            name = "Not signed in";
            tag = "No account";
            showSignIn = true;
        }
        topBarAccountName.setText(name);
        topBarAccountTag.setText(tag);
        String initial = (name != null && !name.isBlank() && !"Not signed in".equals(name)) ? name.substring(0, 1).toUpperCase() : "G";
        topBarAvatarInitial.setText(initial);
        topBarLoginButton.setVisible(showSignIn);
        topBarLoginButton.setManaged(showSignIn);
        updateTopBarHead(active);
    }

    /** Shows the player's head (from their skin) in the account chip; guests keep the letter avatar. */
    private void updateTopBarHead(com.astrolauncher.model.Account active) {
        if (topBarAvatar == null) return;
        if (active == null) {
            topBarHeadKey = "";
            topBarHeadTicket++;
            topBarAvatar.setStyle(null);
            topBarAvatar.getChildren().setAll(topBarAvatarInitial);
            return;
        }
        String key = active.id + "|" + active.username + "|" + active.skinUrl + "|" + active.offlineSkinId;
        if (key.equals(topBarHeadKey)) return;   // same skin as before: nothing to reload
        topBarHeadKey = key;
        final int mine = ++topBarHeadTicket;
        com.astrolauncher.ui.kit.PlayerSkinSource.load(() -> active).whenComplete((prepared, err) -> Platform.runLater(() -> {
            if (mine != topBarHeadTicket || err != null || prepared == null) return;
            topBarAvatar.setStyle("-fx-background-color: transparent;");
            topBarAvatar.getChildren().setAll(com.astrolauncher.ui.kit.SkinModel3D.head(prepared, 36));
        }));
    }

    /**
     * Compact (~56 px) top bar: page buttons on the left (Astro Labs as a lettered button, then Ranks, Mods and
     * About), account chip on the right. Home, Play, Store and Settings stay in the sidebar.
     */
    private HBox buildTopBar() {
        HBox bar = new HBox();
        bar.getStyleClass().addAll("av-topbar", "gap-16");
        bar.setAlignment(Pos.CENTER_LEFT);

        HBox navRow = new HBox(6);
        navRow.getStyleClass().add("av-topnav-row");
        navRow.setAlignment(Pos.CENTER_LEFT);
        for (NavPage p : NavPage.topBar()) {
            javafx.scene.control.Button b = new javafx.scene.control.Button(
                    p == NavPage.LABS ? p.label().toUpperCase() : p.label());
            b.getStyleClass().add(p == NavPage.LABS ? "av-topnav-labs" : "av-topnav");
            b.setMnemonicParsing(false);
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setAccessibleText(p.label());
            b.setOnAction(e -> navigateTo(p));
            topNavButtons.put(p, b);
            navRow.getChildren().add(b);
        }
        // The account chip opens Accounts, which has no button of its own up here.
        syncTopNav(null);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBarAccountName = new Label();
        topBarAccountName.getStyleClass().addAll("account-name", "player-name-grad");
        topBarAccountTag = new Label();
        topBarAccountTag.getStyleClass().add("account-tag");
        VBox accountText = new VBox(1, topBarAccountName, topBarAccountTag);

        StackPane avatar = avatarCircle();
        topBarAvatarInitial = (Label) avatar.getChildren().get(0); // avatarCircle's sole child
        topBarAvatar = avatar;

        HBox accountChip = new HBox(10, avatar, accountText);
        accountChip.getStyleClass().add("account-chip");
        accountChip.setAlignment(Pos.CENTER_LEFT);
        accountChip.setFocusTraversable(true);
        accountChip.setAccessibleText("Account");
        accountChip.setOnMouseClicked(e -> navigateTo(NavPage.ACCOUNTS));
        accountChip.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) navigateTo(NavPage.ACCOUNTS);
        });

        // Only shown when there is no account at all; otherwise the chip is the shortcut to Accounts.
        topBarLoginButton = new AvButton("Sign in", AvButton.Kind.GHOST);
        topBarLoginButton.setOnAction(e -> navigateTo(NavPage.ACCOUNTS));

        bar.getChildren().addAll(navRow, spacer, accountChip, topBarLoginButton, new com.astrolauncher.notify.NotificationBell());
        refreshTopBarAccount();
        // Sign-in / sign-out / session restore finishing in the background all update the chip immediately.
        com.astrolauncher.store.AstroId.get().addListener(() -> Platform.runLater(this::refreshTopBarAccount));
        // Switching "Playing as" on the Accounts page updates the chip straight away.
        com.astrolauncher.service.AccountChoice.addListener(() -> Platform.runLater(this::refreshTopBarAccount));
        return bar;
    }

    /** Marks the top-bar button of the page being shown (none for pages that live in the sidebar). */
    private void syncTopNav(NavPage current) {
        for (Map.Entry<NavPage, javafx.scene.control.Button> e : topNavButtons.entrySet()) {
            if (e.getKey() == current) {
                if (!e.getValue().getStyleClass().contains("av-topnav-current")) e.getValue().getStyleClass().add("av-topnav-current");
            } else {
                e.getValue().getStyleClass().remove("av-topnav-current");
            }
        }
    }

    private StackPane avatarCircle() {
        StackPane circle = new StackPane();
        circle.getStyleClass().add("avatar-circle");
        Label initial = new Label("G");
        initial.getStyleClass().add("avatar-initial");
        circle.getChildren().add(initial);
        return circle;
    }

    /** Multiplies the mouse-wheel scroll distance on every page's ScrollPane (default feels sluggish). */
    private static final double SCROLL_SPEED_FACTOR = 4.0;

    private Parent wrapScrollable(NavPage page) {
        Parent content = buildPage(page);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        // Home's layout uses VGrow so it naturally fills a normal-height window without scrolling.
        // It deliberately does NOT force fitToHeight: nested regions (the tabbed activity card's
        // internal scroll lists in particular) report a small minimum height, so forcing the page to
        // the viewport height on a small window squashed them instead of simply making the page scroll.
        // Transparent background comes from .av-page-scroll (shell.css); "page-scroll" keeps legacy scrollbar styling.
        scroll.getStyleClass().addAll("page-scroll", "av-page-scroll");
        speedUpScrolling(scroll, content);
        return scroll;
    }

    /**
     * JavaFX's default ScrollPane wheel step is a tiny fraction of the content height, which reads
     * as sluggish on tall pages (e.g. the Store's cape grid). This intercepts the wheel event, scales
     * the distance by {@link #SCROLL_SPEED_FACTOR} and applies it directly, instead of the skin's
     * built-in handler.
     */
    private static void speedUpScrolling(ScrollPane scroll, Parent content) {
        content.addEventFilter(ScrollEvent.SCROLL, e -> {
            double contentHeight = content.getBoundsInLocal().getHeight();
            double viewportHeight = scroll.getViewportBounds().getHeight();
            double range = contentHeight - viewportHeight;
            if (range <= 0) return; // nothing to scroll; let the event pass through
            double deltaY = e.getDeltaY() * SCROLL_SPEED_FACTOR;
            double newVvalue = scroll.getVvalue() - deltaY / range;
            scroll.setVvalue(Math.max(0, Math.min(1, newVvalue)));
            e.consume();
        });
    }

    private Parent buildPage(NavPage page) {
        switch (page) {
            case HOME:
                return new HomePage(settings, this::navigateTo, appContext.accountRepository, settingsRepository,
                        this::openLeaderboard).build();
            case PLAY:
                return new PlayPage(appContext, this::navigateTo).build();
            case MODS:
                return new ModsPage(appContext).build();
            case SERVERS:   // never built on its own any more: navigateTo() sends it to Astro Labs > Servers
                return new ServersPage(appContext).build();
            case STORE:
                return new StorePage(appContext, this::navigateTo).build();
            case RANKS:
                return new RanksPage(this::navigateTo, this::replayRankUp).build();
            case LABS:
                labsPage = new LabsPage(appContext, this::navigateTo);
                return labsPage.build();
            case ACCOUNTS:
                return new AccountsPage(appContext).build();
            case SETTINGS:
                return new SettingsPage(settings, settingsRepository, appContext).build();
            case ABOUT:
                return new AboutPage(appContext).build();
            default:
                throw new IllegalStateException("Unhandled page: " + page);
        }
    }

    // ─────────────────────────────────────────────────────── rank toasts

    /**
     * Promotion and quest-completed notices from the rank system, shown over the page area.
     * onPromotion is the fast path (fires immediately if the launcher window happens to be in front
     * when it happens) but isn't relied on alone — see {@link #celebrateIfNew} and the check that runs
     * every time the Ranks page opens, which is what actually guarantees the cinematic is seen.
     */
    private void installRankToasts() {
        com.astrolauncher.rank.RankService.get().addListener(new com.astrolauncher.rank.RankListener() {
            @Override public void onPromotion(com.astrolauncher.rank.RankStanding to) {
                Platform.runLater(() -> celebrateIfNew(to));
            }
            @Override public void onQuestCompleted(com.astrolauncher.rank.QuestView q) {
                Platform.runLater(() -> rankToast.show("Quest complete: " + q.title(), "+" + q.rewardAp() + " AP", "#5ec97a"));
            }
        });
    }

    // ─────────────────────────────────────────────────────── update bar

    /**
     * Shows the slim update bar over the page area. Safe to call from any thread; hops to the FX
     * thread itself. Ignores a version the player already chose to skip.
     */
    public void showUpdateAvailable(UpdateInfo info) {
        if (info == null) return;
        com.astrolauncher.notify.NotificationCenter.get().post("Update available", "Astro " + info.displayName + " is ready.");
        Platform.runLater(() -> updateBar.show(info));
    }
}
