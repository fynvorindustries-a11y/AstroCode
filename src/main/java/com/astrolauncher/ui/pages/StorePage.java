package com.astrolauncher.ui.pages;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.store.AstroId;
import com.astrolauncher.store.StoreApi;
import com.astrolauncher.store.StoreApi.CapeItem;
import com.astrolauncher.store.StoreApi.Wardrobe;
import com.astrolauncher.ui.AppContext;
import com.astrolauncher.ui.Motion;
import com.astrolauncher.ui.NavPage;
import com.astrolauncher.ui.ValDeco;
import com.astrolauncher.ui.kit.AvButton;
import com.astrolauncher.ui.kit.CapePreview;
import com.astrolauncher.ui.kit.Card;
import com.astrolauncher.ui.kit.EmptyState;
import com.astrolauncher.ui.kit.ErrorState;
import com.astrolauncher.ui.kit.PageHeader;
import com.astrolauncher.ui.kit.Skeleton;
import com.astrolauncher.ui.kit.StepProgress;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cape store. The gallery comes first: a large featured preview with the action for the selected cape
 * (Equip / Remove / Buy / Get free / Sign in), then a grid of cape tiles whose state reads at a glance
 * (EQUIPPED, OWNED, or the price). A side column holds the compact Astro ID panel (full profile
 * management lives on the Accounts page), the gift-code box and the in-game cape mod switch.
 *
 * <p>Presentation only: the catalog/wardrobe calls, buy (checkout in the browser + polling), equip,
 * redeem and the cape-mod setting are the same code as before. The page is cached by MainWindow, so it
 * reloads the catalog and wardrobe when shown again (the player may have signed in on Accounts).
 */
public class StorePage {

    private static final String TAG = "StorePage";
    private static final double TILE_W = 176;
    // The backgrounds list is long, so it sits in its own scroll area about two rows tall (the page itself
    // stays short) and is filtered by category chips above it.
    private static final double THEME_ART_H = 150;
    private static final double VIEW_H = 480; // about two rows of tiles, with the third peeking to show it scrolls

    private final AppContext appContext;
    private final Consumer<NavPage> navigate;
    private final AstroId astro = AstroId.get();
    private final StoreApi api = astro.api();

    // ── state ──
    private List<CapeItem> catalog = new ArrayList<>();
    private Wardrobe wardrobe = new Wardrobe();
    private boolean loading = true;
    private Throwable loadError;
    private String featuredId;
    private boolean stale;
    // Astro Labs card backgrounds — sold through this same store (kind "theme", ids like "bg_forest-spirit")
    // but not wearable, so they get their own small section here instead of the cape gallery/stage above.
    // Ownership lives in the same wardrobe.owned list the capes use; selecting one still happens on Labs.
    private List<CapeItem> themeCatalog = new ArrayList<>();
    private boolean themesLoading = true;
    private Throwable themesError;
    // Set right before a checkout opens, so the poll's "purchase complete" message is worded correctly
    // for whichever kind of item (cape or background) the person just bought.
    private boolean pendingPurchaseIsTheme;
    // The checkout poll is a timer, not an animation: it must keep running in Low-End Mode, so it is
    // deliberately NOT routed through Motion. It only runs while the page is on screen.
    private Timeline poll;
    private int pollTicks;

    // ── layout pieces ──
    private final Label statusLabel = new Label();
    private final VBox galleryBody = new VBox();
    private final FlowPane grid = new FlowPane();
    private final VBox themeBody = new VBox();
    private final FlowPane themeGrid = new FlowPane();
    private final FlowPane themeChips = new FlowPane(6, 6);
    private final ScrollPane themeScroll = CategoryUi.shortScroll(themeGrid, VIEW_H);
    private Object themeFilter; // null = All, else a ThemeArt.Category
    private final FlowPane capeChips = new FlowPane(6, 6);
    private final ScrollPane capeScroll = CategoryUi.shortScroll(grid, VIEW_H);
    private Object capeFilter;  // null = All, LIMITED, else a ThemeArt.CapeCategory
    private static final String LIMITED = "limited";

    // featured stage
    private StackPane stagePane;
    private CapePreview featuredPreview;
    private VBox featuredDetails;
    private VBox featuredSkeleton;
    private Label stateChip;
    private Label featuredName;
    private Label featuredDesc;
    private FlowPane featuredChips;
    private AvButton primaryBtn;
    private AvButton ghostBtn;
    private StepProgress waitProgress;

    // side column
    private Label idName;
    private Label idSub;
    private Label idChip;
    private AvButton idButton;
    private TextField codeField;
    private AvButton redeemBtn;
    private ToggleButton capesSwitch;

    public StorePage(AppContext appContext, Consumer<NavPage> navigate) {
        this.appContext = appContext;
        this.navigate = navigate;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────────────────

    public VBox build() {
        // Direct children are the page's sections so PageTransition can stagger them in.
        VBox root = new VBox();
        root.getStyleClass().addAll("av-page-body", "gap-16");

        AvButton refresh = new AvButton("Refresh", AvButton.Kind.GHOST);
        refresh.setAccessibleText("Refresh the store");
        refresh.setOnAction(e -> load());
        PageHeader header = new PageHeader("Store",
                "Capes are tied to your Astro ID — they follow every Minecraft profile you link.")
                .actions(refresh);
        root.getChildren().add(header);

        if (api.isDemo()) {
            Label demo = new Label("DEMO MODE — no store server is configured. Purchases are local and reset on "
                    + "restart. Set ASTRO_STORE_API to your server URL.");
            demo.getStyleClass().add("store-notice-text");
            demo.setWrapText(true);
            HBox notice = new HBox(demo);
            notice.getStyleClass().add("store-notice");
            root.getChildren().add(notice);
        }

        statusLabel.getStyleClass().add("store-status");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        setStatus("");
        root.getChildren().add(statusLabel);

        // ── gallery (left) ──
        grid.getStyleClass().add("gap-12");
        galleryBody.getStyleClass().add("gap-12");
        Label galleryTitle = new Label("CAPES");
        galleryTitle.getStyleClass().add("av-section-title");

        themeGrid.getStyleClass().add("gap-12");
        themeBody.getStyleClass().add("gap-12");
        themeChips.getStyleClass().add("store-cat-row");
        capeChips.getStyleClass().add("store-cat-row");
        Label themeTitle = new Label("BACKGROUNDS");
        themeTitle.getStyleClass().add("av-section-title");
        Label themeHint = new Label("Astro Labs card backgrounds. Buy here; pick which one is active on the Labs page.");
        themeHint.getStyleClass().add("store-side-sub");
        themeHint.setWrapText(true);

        VBox gallery = new VBox(16, buildStage(), galleryTitle, galleryBody, themeTitle, themeHint, themeBody);
        gallery.setMinWidth(0);
        HBox.setHgrow(gallery, Priority.ALWAYS);

        // ── side column (right) ──
        VBox side = buildSide();

        HBox main = new HBox(16, gallery, side);
        main.setAlignment(Pos.TOP_LEFT);
        root.getChildren().add(main);

        // Cached page: stop the poll when leaving; reload (catalog, wardrobe, sign-in state) on return.
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                stale = true;
                stopPolling();
            } else if (stale) {
                stale = false;
                capesSwitch.setSelected(appContext.settings.astroCapesEnabled);
                load();
            }
        });

        renderAll();
        load();
        return root;
    }

    // ── featured stage ──────────────────────────────────────────────────────

    private StackPane buildStage() {
        StackPane stage = new StackPane();
        stagePane = stage;
        stage.getStyleClass().add("store-stage");

        Region art = new Region();
        art.getStyleClass().add("store-stage-art");
        Region scrim = new Region();
        scrim.getStyleClass().add("store-stage-scrim");

        // Opaque preview card (bright top bar, chamfered V bottom) — same language as the Play stage.
        featuredPreview = new CapePreview(200, 280);
        VBox previewCard = new VBox(featuredPreview);
        previewCard.getStyleClass().add("store-feature-card");
        previewCard.setAlignment(Pos.TOP_CENTER);
        previewCard.setMinWidth(Region.USE_PREF_SIZE);
        previewCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Polygon cut = new Polygon();
        InvalidationListener recut = o -> {
            double w = previewCard.getWidth(), h = previewCard.getHeight(), c = Math.min(26, h / 6);
            if (w <= 0 || h <= 0) return;
            cut.getPoints().setAll(0.0, 0.0, w, 0.0, w, h - c, w / 2, h, 0.0, h - c);
        };
        previewCard.widthProperty().addListener(recut);
        previewCard.heightProperty().addListener(recut);
        previewCard.setClip(cut);

        stateChip = new Label();
        stateChip.getStyleClass().add("av-chip");
        featuredName = new Label();
        featuredName.getStyleClass().add("store-feature-name");
        featuredName.setTextOverrun(OverrunStyle.ELLIPSIS);
        featuredName.setMaxWidth(Double.MAX_VALUE);
        featuredChips = new FlowPane();
        featuredChips.getStyleClass().add("gap-8");
        featuredDesc = new Label();
        featuredDesc.getStyleClass().add("store-feature-desc");
        featuredDesc.setWrapText(true);
        featuredDesc.setMaxWidth(460);

        primaryBtn = new AvButton("Equip", AvButton.Kind.PRIMARY);
        ghostBtn = new AvButton("Remove cape", AvButton.Kind.GHOST);
        HBox actions = new HBox(10, primaryBtn, ghostBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(actions, new Insets(8, 0, 0, 0));

        waitProgress = new StepProgress();
        waitProgress.setStep("Waiting for your purchase to finish in the browser");
        waitProgress.setMaxWidth(420);
        setShown(waitProgress, false);

        featuredDetails = new VBox(8, stateChip, featuredName, featuredChips, featuredDesc, actions, waitProgress);
        featuredDetails.setAlignment(Pos.CENTER_LEFT);

        featuredSkeleton = new VBox(12, Skeleton.line(90), Skeleton.block(260, 34), Skeleton.lines(3), Skeleton.block(160, 44));
        featuredSkeleton.setAlignment(Pos.CENTER_LEFT);
        featuredSkeleton.setMaxWidth(460);
        StackPane detailsSlot = new StackPane(featuredDetails, featuredSkeleton);
        detailsSlot.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(detailsSlot, Priority.ALWAYS);

        HBox row = new HBox(32, previewCard, detailsSlot);
        row.getStyleClass().add("store-stage-content");
        row.setAlignment(Pos.CENTER_LEFT);

        // The slashes' own polygons would otherwise set the pane's preferred height (feedback loop).
        Pane slashes = ValDeco.slashes(0.5);
        slashes.setMinSize(0, 0);
        slashes.setPrefSize(0, 0);
        stage.getChildren().addAll(art, scrim, slashes, row);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(stage.widthProperty());
        clip.heightProperty().bind(stage.heightProperty());
        stage.setClip(clip);
        return stage;
    }

    // ── side column ─────────────────────────────────────────────────────────

    private VBox buildSide() {
        // Astro ID (compact: full profile management lives on Accounts)
        Label idTitle = new Label("ASTRO ID");
        idTitle.getStyleClass().add("av-section-title");
        idName = new Label();
        idName.getStyleClass().add("store-side-big");
        idName.setTextOverrun(OverrunStyle.ELLIPSIS);
        idChip = new Label();
        idChip.getStyleClass().add("av-chip");
        idSub = new Label();
        idSub.getStyleClass().add("store-side-sub");
        idSub.setWrapText(true);
        idButton = new AvButton("Manage in Accounts", AvButton.Kind.GHOST);
        idButton.setMaxWidth(Double.MAX_VALUE);
        idButton.setOnAction(e -> navigate.accept(NavPage.ACCOUNTS));
        VBox idBox = sideCard(idTitle, new HBox(8, idName, idChip), idSub, idButton);

        // Redeem a code
        Label codeTitle = new Label("GIFT OR PROMO CODE");
        codeTitle.getStyleClass().add("av-section-title");
        codeField = new TextField();
        codeField.getStyleClass().add("store-field");
        codeField.setPromptText("Enter code");
        codeField.setAccessibleText("Gift or promo code");
        redeemBtn = new AvButton("Redeem code", AvButton.Kind.GHOST);
        redeemBtn.setMaxWidth(Double.MAX_VALUE);
        redeemBtn.setOnAction(e -> redeem(codeField));
        codeField.setOnAction(e -> redeemBtn.fire());
        VBox codeBox = sideCard(codeTitle, codeField, redeemBtn);

        // In-game rendering
        Label modTitle = new Label("IN-GAME CAPES");
        modTitle.getStyleClass().add("av-section-title");
        capesSwitch = new ToggleButton();
        capesSwitch.getStyleClass().add("store-switch");
        capesSwitch.setAccessibleText("Add the Astro cape mod when launching a Fabric profile");
        capesSwitch.setSelected(appContext.settings.astroCapesEnabled);
        capesSwitch.textProperty().bind(javafx.beans.binding.Bindings.when(capesSwitch.selectedProperty()).then("ON").otherwise("OFF"));
        capesSwitch.setOnAction(e -> {
            appContext.settings.astroCapesEnabled = capesSwitch.isSelected();
            appContext.settingsRepository.save(appContext.settings);
            setStatus(capesSwitch.isSelected()
                    ? "On — the mod is placed in your Fabric mods folder before the next launch."
                    : "Off — the mod will be removed from your Fabric mods folder on the next launch.");
        });
        Label modLabel = new Label("Add the Astro cape mod when launching a Fabric profile");
        modLabel.getStyleClass().add("store-side-label");
        modLabel.setWrapText(true);
        HBox.setHgrow(modLabel, Priority.ALWAYS);
        HBox modRow = new HBox(12, modLabel, capesSwitch);
        modRow.setAlignment(Pos.CENTER_LEFT);
        Label ingame = new Label("Microsoft accounts don’t allow custom capes in vanilla, so ASTRO’s small "
                + "cape mod renders your cape client-side for Fabric profiles. Players on ASTRO see it; "
                + "others see your normal Mojang cape.");
        ingame.getStyleClass().add("store-side-sub");
        ingame.setWrapText(true);
        VBox modBox = sideCard(modTitle, modRow, ingame);

        VBox side = new VBox(12, idBox, codeBox, modBox);
        side.setMinWidth(280);
        side.setPrefWidth(280);
        side.setMaxWidth(280);
        return side;
    }

    private static VBox sideCard(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("store-side");
        return box;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loading
    // ─────────────────────────────────────────────────────────────────────────

    private void load() {
        boolean signedIn = astro.isSignedIn();
        loading = true;
        loadError = null;
        themesLoading = true;
        themesError = null;
        setStatus("");
        renderAll();

        api.catalog().whenComplete((items, err) -> Platform.runLater(() -> {
            loading = false;
            if (err != null) {
                loadError = err;
                renderAll();
                return;
            }
            catalog = withoutAvero(items);
            if (!signedIn) wardrobe = new Wardrobe();
            renderAll();
            if (signedIn) refreshWardrobe(false);
        }));

        // Whatever is currently on sale as a background — the source of truth is the server's live
        // catalog, not a bundled list, so a new one shows up here as soon as it's added to the database.
        api.themeCatalog().whenComplete((items, err) -> Platform.runLater(() -> {
            themesLoading = false;
            themesError = err;
            if (err == null) themeCatalog = items != null ? items : new ArrayList<>();
            renderThemes();
        }));
    }

    private void refreshWardrobe(boolean quiet) {
        if (!astro.isSignedIn()) { wardrobe = new Wardrobe(); renderAll(); return; }
        api.wardrobe().whenComplete((w, err) -> Platform.runLater(() -> {
            if (err != null) { setStatus("Couldn’t refresh your capes: " + rootMsg(err)); return; }
            boolean grew = w.owned.size() > wardrobe.owned.size();
            wardrobe = w;
            renderAll();
            if (!quiet) clearStatus();
            if (grew && poll != null) {
                stopPolling();
                setStatus(pendingPurchaseIsTheme
                        ? "Purchase complete — pick it on the Astro Labs page."
                        : "Purchase complete — your new cape is ready to equip.");
            }
        }));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    private void buy(CapeItem cape) {
        pendingPurchaseIsTheme = cape.id != null && cape.id.startsWith("bg_");
        setStatus("Opening checkout for " + cape.name + "…");
        api.checkoutUrl(cape.id).whenComplete((url, err) -> Platform.runLater(() -> {
            if (err != null) { setStatus("Couldn’t start checkout: " + rootMsg(err)); return; }
            if (url == null) { refreshWardrobe(false); return; } // demo mode
            openInBrowser(url);
            setStatus("Finish the purchase in your browser — this page updates automatically when done.");
            startPolling();
        }));
    }

    private void equip(String capeIdOrNull) {
        setStatus(capeIdOrNull == null ? "Removing cape…" : "Equipping…");
        api.equip(capeIdOrNull).whenComplete((w, err) -> Platform.runLater(() -> {
            if (err != null) { setStatus("Couldn’t update cape: " + rootMsg(err)); return; }
            wardrobe = w;
            renderAll();
            setStatus(capeIdOrNull == null ? "Cape removed." : "Cape equipped. It appears next time you launch.");
        }));
    }

    private void redeem(TextField codeField) {
        String code = codeField.getText();
        if (code == null || code.isBlank()) return;
        if (!astro.isSignedIn()) { setStatus("Sign in to redeem a code."); return; }
        setStatus("Redeeming…");
        api.redeem(code).whenComplete((w, err) -> Platform.runLater(() -> {
            if (err != null) { setStatus("Couldn’t redeem: " + rootMsg(err)); return; }
            codeField.clear();
            wardrobe = w;
            renderAll();
            setStatus("Code redeemed — the cape is now on your account.");
        }));
    }

    private void startPolling() {
        stopPolling();
        pollTicks = 0;
        poll = new Timeline(new KeyFrame(Duration.seconds(4), e -> {
            if (++pollTicks > 75) { stopPolling(); return; }
            refreshWardrobe(true);
        }));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
        updateWaiting();
    }

    private void stopPolling() {
        if (poll != null) { poll.stop(); poll = null; }
        updateWaiting();
    }

    /** Shows the indeterminate "waiting for purchase" bar only while the checkout poll is running. */
    private void updateWaiting() {
        if (waitProgress == null) return;
        boolean waiting = poll != null;
        waitProgress.setIndeterminate(waiting);
        setShown(waitProgress, waiting);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    private void renderAll() {
        renderSide();
        renderGallery();
        renderFeatured();
        renderThemes();
    }

    private void renderSide() {
        boolean signedIn = astro.isSignedIn();
        if (signedIn) {
            var s = astro.session();
            idName.setText(s != null && s.username != null ? s.username : "Signed in");
            idChip.setText("SIGNED IN");
            idChip.getStyleClass().setAll("av-chip", "store-chip-ok");
            idSub.setText("Your capes follow every Minecraft profile you link.");
            idButton.setText("Manage in Accounts");
        } else {
            idName.setText("Not signed in");
            idChip.setText("");
            idChip.getStyleClass().setAll("av-chip");
            idSub.setText(api.isDemo() ? "No Astro server is configured, so there are no accounts to sign in to."
                    : "Sign in with your Astro ID to buy, redeem and equip capes.");
            idButton.setText("Sign in with Astro ID");
        }
        setShown(idChip, signedIn);
        idButton.setDisable(!signedIn && api.isDemo());
        codeField.setDisable(!signedIn);
        redeemBtn.setDisable(!signedIn);
    }

    private void renderGallery() {
        if (loading) {
            FlowPane sk = new FlowPane();
            sk.getStyleClass().add("gap-12");
            for (int i = 0; i < 6; i++) sk.getChildren().add(Skeleton.block(TILE_W, 214));
            galleryBody.getChildren().setAll(sk);
            return;
        }
        if (loadError != null) {
            galleryBody.getChildren().setAll(ErrorState.of(loadError, "Astro", this::load));
            return;
        }
        if (catalog.isEmpty()) {
            galleryBody.getChildren().setAll(new EmptyState("No capes yet", "New capes will show up here as soon as they’re released."));
            return;
        }
        ensureFeatured();
        buildCapeChips();
        fillCapeGrid();
        galleryBody.getChildren().setAll(capeChips, capeScroll);
    }

    /** The old Avero cape is no longer sold here: drop it from whatever the server's catalog still lists. */
    private static List<CapeItem> withoutAvero(List<CapeItem> items) {
        List<CapeItem> out = new ArrayList<>();
        if (items == null) return out;
        for (CapeItem c : items) {
            String id = c.id == null ? "" : c.id.toLowerCase(java.util.Locale.ROOT);
            String nm = c.name == null ? "" : c.name.toLowerCase(java.util.Locale.ROOT);
            if (id.contains("avero") || nm.contains("avero")) continue;
            out.add(c);
        }
        return out;
    }

    /** Category chips for capes (plus Limited, from the server's flag). Only groups that have capes are shown. */
    private void buildCapeChips() {
        java.util.Map<ThemeArt.CapeCategory, Integer> counts = new java.util.EnumMap<>(ThemeArt.CapeCategory.class);
        int limited = 0;
        for (CapeItem c : catalog) {
            counts.merge(ThemeArt.capeCategoryOf(c.id, c.name), 1, Integer::sum);
            if (c.limited) limited++;
        }
        if (LIMITED.equals(capeFilter) && limited == 0) capeFilter = null;
        if (capeFilter instanceof ThemeArt.CapeCategory cc && !counts.containsKey(cc)) capeFilter = null;

        List<CategoryUi.Chip> chips = new ArrayList<>();
        chips.add(new CategoryUi.Chip("All  " + catalog.size(), null));
        if (limited > 0) chips.add(new CategoryUi.Chip("Limited  " + limited, LIMITED));
        for (ThemeArt.CapeCategory c : ThemeArt.CapeCategory.values()) {
            Integer n = counts.get(c);
            if (n != null) chips.add(new CategoryUi.Chip(c.label + "  " + n, c));
        }
        CategoryUi.fill(capeChips, chips, capeFilter, key -> {
            capeFilter = key;
            buildCapeChips();
            fillCapeGrid();
            capeScroll.setVvalue(0);
        });
    }

    private void fillCapeGrid() {
        grid.getChildren().clear();
        for (CapeItem cape : catalog) {
            boolean show = capeFilter == null
                    || (LIMITED.equals(capeFilter) ? cape.limited : capeFilter == ThemeArt.capeCategoryOf(cape.id, cape.name));
            if (show) grid.getChildren().add(capeTile(cape));
        }
    }

    /** Keeps a sensible featured cape: the one selected, else the equipped one, else an owned one, else the first. */
    private void ensureFeatured() {
        if (featuredId != null && find(featuredId) != null) return;
        CapeItem pick = wardrobe.equipped != null ? find(wardrobe.equipped) : null;
        if (pick == null) for (CapeItem c : catalog) if (wardrobe.owned.contains(c.id)) { pick = c; break; }
        if (pick == null && !catalog.isEmpty()) pick = catalog.get(0);
        featuredId = pick == null ? null : pick.id;
    }

    private CapeItem find(String id) {
        for (CapeItem c : catalog) if (c.id != null && c.id.equals(id)) return c;
        return null;
    }

    private void renderFeatured() {
        // When the catalog fails, the gallery below shows the one friendly error with Retry; no second copy here.
        setShown(stagePane, loadError == null);
        boolean showSkeleton = loading;
        setShown(featuredSkeleton, showSkeleton);
        setShown(featuredDetails, !showSkeleton);
        if (loading) { featuredPreview.showLoading(); return; }

        CapeItem cape = loadError != null ? null : find(featuredId);
        if (cape == null) {
            featuredPreview.showEmpty(loadError != null ? "Unavailable" : "No cape");
            stateChip.setText("");
            setShown(stateChip, false);
            featuredName.setText(loadError != null ? "Can’t reach Astro right now" : "Nothing to show yet");
            featuredDesc.setText(loadError != null ? "The cape gallery below has a Retry button."
                    : "When capes are released they will show up here.");
            featuredChips.getChildren().clear();
            setShown(primaryBtn, false);
            setShown(ghostBtn, false);
            return;
        }

        boolean owned = wardrobe.owned.contains(cape.id);
        boolean equipped = cape.id.equals(wardrobe.equipped);
        boolean signedIn = astro.isSignedIn();

        featuredPreview.showCape(cape.id, cape.name, cape.previewUrl);
        stateChip.setText(equipped ? "EQUIPPED" : owned ? "OWNED" : "NOT OWNED");
        stateChip.getStyleClass().setAll("av-chip", equipped ? "store-chip-ok" : owned ? "store-chip-ice" : "store-chip-mute");
        setShown(stateChip, true);
        featuredName.setText(cape.name);
        featuredDesc.setText(cape.description != null && !cape.description.isBlank()
                ? cape.description : "Astro cosmetic cape");

        featuredChips.getChildren().clear();
        Label price = new Label(cape.priceLabel().toUpperCase());
        price.getStyleClass().addAll("av-chip", cape.priceCents <= 0 ? "store-chip-ok" : "store-chip-price");
        featuredChips.getChildren().add(price);
        if (cape.limited) {
            Label lim = new Label("LIMITED");
            lim.getStyleClass().addAll("av-chip", "store-chip-warn");
            featuredChips.getChildren().add(lim);
        }

        // One primary action per screen: Equip / Buy / Get free / Sign in. "Remove" is always ghost.
        setShown(ghostBtn, equipped);
        setShown(primaryBtn, !equipped);
        if (equipped) {
            ghostBtn.setText("Remove cape");
            ghostBtn.setOnAction(e -> equip(null));
        } else if (owned) {
            primaryBtn.setText("Equip cape");
            primaryBtn.setOnAction(e -> equip(cape.id));
        } else if (!signedIn) {
            primaryBtn.setText("Sign in to buy");
            primaryBtn.setOnAction(e -> navigate.accept(NavPage.ACCOUNTS));
        } else {
            primaryBtn.setText(cape.priceCents <= 0 ? "Get free" : "Buy · " + cape.priceLabel());
            primaryBtn.setOnAction(e -> buy(cape));
        }
        primaryBtn.setAccessibleText(primaryBtn.getText() + " " + cape.name);
        updateWaiting();
    }

    /** Gallery tile: opaque, keyboard-reachable; selecting it features the cape. The state chip reads at a glance. */
    private Card capeTile(CapeItem cape) {
        boolean owned = wardrobe.owned.contains(cape.id);
        boolean equipped = cape.id.equals(wardrobe.equipped);

        CapePreview preview = new CapePreview(TILE_W - 2, 150);
        preview.showCape(cape.id, cape.name, cape.previewUrl);

        Label name = new Label(cape.name);
        name.getStyleClass().add("store-tile-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);

        Label state = new Label();
        if (equipped) {
            state.setText("EQUIPPED");
            state.getStyleClass().addAll("av-chip", "store-chip-ok");
        } else if (owned) {
            state.setText("OWNED");
            state.getStyleClass().addAll("av-chip", "store-chip-ice");
        } else {
            state.setText(cape.priceCents <= 0 ? "FREE" : cape.priceLabel().toUpperCase());
            state.getStyleClass().addAll("av-chip", "store-chip-price");
        }
        FlowPane chips = new FlowPane(6, 6, state);
        if (cape.limited && !owned) {
            Label lim = new Label("LIMITED");
            lim.getStyleClass().addAll("av-chip", "store-chip-warn");
            chips.getChildren().add(lim);
        }

        VBox body = new VBox(6, name, chips);
        body.getStyleClass().add("store-tile-body");
        VBox inner = new VBox(preview, body);

        Card card = new Card(inner).interactive();
        card.getStyleClass().add("store-tile");
        card.setActive(cape.id.equals(featuredId));
        card.setMinWidth(TILE_W);
        card.setPrefWidth(TILE_W);
        card.setMaxWidth(TILE_W);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleText(cape.name + ", " + state.getText().toLowerCase());

        Runnable pick = () -> {
            if (cape.id.equals(featuredId)) return;
            featuredId = cape.id;
            for (Node n : grid.getChildren()) if (n instanceof Card c) c.setActive(c == card);
            renderFeatured();
            featuredDetails.setOpacity(0);
            Motion.animate(featuredDetails.opacityProperty(), 1.0, Motion.PANEL);
        };
        card.setOnMouseClicked(e -> pick.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) pick.run();
        });
        return card;
    }

    private void renderThemes() {
        if (themesLoading) {
            FlowPane sk = new FlowPane();
            sk.getStyleClass().add("gap-12");
            for (int i = 0; i < 4; i++) sk.getChildren().add(Skeleton.block(TILE_W, 150));
            themeBody.getChildren().setAll(sk);
            return;
        }
        if (themesError != null) {
            themeBody.getChildren().setAll(ErrorState.of(themesError, "Astro", this::load));
            return;
        }
        if (themeCatalog.isEmpty()) {
            themeBody.getChildren().setAll(new EmptyState("No backgrounds yet",
                    "New backgrounds will show up here as soon as they’re released."));
            return;
        }
        buildThemeChips();
        fillThemeGrid();
        themeBody.getChildren().setAll(themeChips, themeScroll);
    }

    /** Category chips with counts. Only categories that actually have items are shown. */
    private void buildThemeChips() {
        java.util.Map<ThemeArt.Category, Integer> counts = new java.util.EnumMap<>(ThemeArt.Category.class);
        for (CapeItem t : themeCatalog) counts.merge(ThemeArt.categoryOf(t.id, t.name), 1, Integer::sum);
        if (themeFilter instanceof ThemeArt.Category c && !counts.containsKey(c)) themeFilter = null;

        List<CategoryUi.Chip> chips = new ArrayList<>();
        chips.add(new CategoryUi.Chip("All  " + themeCatalog.size(), null));
        for (ThemeArt.Category c : ThemeArt.order()) {
            Integer n = counts.get(c);
            if (n != null) chips.add(new CategoryUi.Chip(c.label + "  " + n, c));
        }
        CategoryUi.fill(themeChips, chips, themeFilter, key -> {
            themeFilter = key;
            buildThemeChips();
            fillThemeGrid();
            themeScroll.setVvalue(0);
        });
    }

    private void fillThemeGrid() {
        themeGrid.getChildren().clear();
        for (CapeItem theme : themeCatalog) {
            if (themeFilter == null || themeFilter == ThemeArt.categoryOf(theme.id, theme.name)) {
                themeGrid.getChildren().add(themeTile(theme));
            }
        }
    }

    /** Background tile: no "Equip" here (backgrounds aren't worn) — buy it, or jump to Labs to pick it. */
    private Card themeTile(CapeItem theme) {
        boolean owned = wardrobe.owned.contains(theme.id);

        // Gradient underneath (shown while the picture decodes or when there is none); the bundled
        // /images/themes/<slug>.jpg on top, falling back to the server's preview URL.
        Region gradient = new Region();
        gradient.getStyleClass().add("store-theme-art");
        double artW = TILE_W - 2;
        StackPane art = new StackPane(gradient);
        art.setMinSize(artW, THEME_ART_H);
        art.setPrefSize(artW, THEME_ART_H);
        art.setMaxSize(artW, THEME_ART_H);
        Node picture = ThemeArt.image(theme.id, theme.name, theme.previewUrl, artW, THEME_ART_H);
        if (picture != null) art.getChildren().add(picture);
        Rectangle artClip = new Rectangle(artW, THEME_ART_H + 20); // rounds the top corners only
        artClip.setArcWidth(30);
        artClip.setArcHeight(30);
        art.setClip(artClip);

        Label name = new Label(theme.name);
        name.getStyleClass().add("store-tile-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);

        Label state = new Label(owned ? "OWNED" : theme.priceCents <= 0 ? "FREE" : theme.priceLabel().toUpperCase());
        state.getStyleClass().addAll("av-chip", owned ? "store-chip-ice" : "store-chip-price");
        FlowPane chips = new FlowPane(6, 6, state);

        VBox body = new VBox(6, name, chips);
        body.getStyleClass().add("store-tile-body");
        VBox inner = new VBox(art, body);

        Card card = new Card(inner).interactive();
        card.getStyleClass().add("store-tile");
        card.setMinWidth(TILE_W);
        card.setPrefWidth(TILE_W);
        card.setMaxWidth(TILE_W);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleText(theme.name + ", " + (owned ? "owned, pick it on the Astro Labs page"
                : theme.priceLabel() + ", not owned"));

        Runnable act = () -> {
            if (owned) {
                setStatus(theme.name + " is on your account — pick it on the Astro Labs page.");
            } else if (!astro.isSignedIn()) {
                navigate.accept(NavPage.ACCOUNTS);
            } else {
                buy(theme);
            }
        };
        card.setOnMouseClicked(e -> act.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) act.run();
        });
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setStatus(String text) {
        statusLabel.setText(text);
        boolean show = text != null && !text.isEmpty();
        setShown(statusLabel, show);
    }

    private void clearStatus() { setStatus(""); }

    private static void setShown(Node n, boolean shown) {
        n.setVisible(shown);
        n.setManaged(shown);
    }

    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not open browser: " + e.getMessage());
            setStatus("Open this link to pay: " + url);
        }
    }

    private static String rootMsg(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() != null && !r.getMessage().isBlank() ? r.getMessage() : "Something went wrong. Please try again.";
    }
}
