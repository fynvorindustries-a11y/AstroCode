package com.astrolauncher.ui.pages;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.model.auth.AuthModels;
import com.astrolauncher.service.AccountChoice;
import com.astrolauncher.store.AstroId;
import com.astrolauncher.ui.DialogStyler;
import com.astrolauncher.ui.AppContext;
import com.astrolauncher.ui.NavPage;
import com.astrolauncher.util.UiAccents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.UUID;

/**
 * Two separate sign-ins, and a "Playing as" choice between them:
 * <ul>
 *   <li><b>Astro account</b> - the Astro ID (rank, credits, capes...), one name, for offline-mode servers.</li>
 *   <li><b>Microsoft account</b> - the real Minecraft account, for premium servers.</li>
 * </ul>
 * Play launches as whichever is selected; with only one signed in, that one is used automatically.
 *
 * Two columns so nothing needs scrolling: the two sign-in cards on the left, "Playing as" and skins on the right.
 */
public class AccountsPage {

    private static final String TAG = "AccountsPage";

    private final AppContext appContext;
    private List<Account> accounts;
    private final FlowPane accountListBox = new FlowPane(10, 10);   // saved Microsoft sessions: compact tiles that wrap
    private final VBox playingAsBox = new VBox(10);                  // the two-option "Playing as" choice
    private Node savedSessionsHeader;
    private final Label statusLabel   = new Label();
    private AstroIdCard astroCard;
    private SkinsPanel skinsPanel;
    private Dialog<Void> deviceAlert;
    private javafx.animation.Timeline deviceTimer;
    private final AstroId astro = AstroId.get();
    private final Runnable astroListener = () -> Platform.runLater(this::refreshAccountList);

    public AccountsPage(AppContext appContext) {
        this.appContext = appContext;
        this.accounts   = appContext.accountRepository.load();
    }

    public VBox build() {
        VBox root = new VBox(12);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(22, 32, 20, 32));

        // ── Page header ──
        Label title = new Label("Accounts");
        title.getStyleClass().add("page-title");
        HBox titleRow = new HBox(14, UiAccents.pageBadge(NavPage.ACCOUNTS, 40), title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(
                "Two separate sign-ins. Your Astro account is your launcher identity (rank, credits, capes, "
                + "backgrounds) and works on any server that accepts offline-mode players. Your Microsoft account "
                + "is your real Minecraft account, for premium servers.");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);

        root.getChildren().addAll(titleRow, UiAccents.pageUnderline(), subtitle);

        // ══ LEFT column: the two sign-in cards, compact enough to fit without scrolling ══

        // ── Microsoft card ──
        Button msBtn = new Button("Sign in with Microsoft");
        msBtn.getStyleClass().add("ms-hero-button");
        msBtn.setOnAction(e -> startMicrosoftSignIn());
        // A Button's minimum width is just an ellipsis, so in a tight layout JavaFX squeezed it down to "…".
        // Keep it at its full size, and let it stretch across the card.
        msBtn.setMinWidth(Region.USE_PREF_SIZE);
        msBtn.setMaxWidth(Double.MAX_VALUE);

        Label heroTitle = new Label("Play with your real Minecraft account");
        heroTitle.getStyleClass().add("ms-hero-title");
        heroTitle.setStyle("-fx-font-size: 17px;");   // the card is narrower now, so a smaller title than the old full-width hero
        heroTitle.setWrapText(true);                  // wrap instead of cutting off with "..." on a narrow window
        heroTitle.setMinHeight(Region.USE_PREF_SIZE);
        Label heroSub = new Label("One click: your browser opens and the code is copied for you. "
                + "ASTRO never sees your password.");
        heroSub.getStyleClass().add("ms-hero-sub");
        heroSub.setWrapText(true);
        heroSub.setMinHeight(Region.USE_PREF_SIZE);

        VBox heroTitleBox = new VBox(heroTitle);
        HBox.setHgrow(heroTitleBox, Priority.ALWAYS);
        HBox heroTop = new HBox(14, microsoftLogo(26), heroTitleBox);
        heroTop.setAlignment(Pos.CENTER_LEFT);
        VBox hero = new VBox(10, heroTop, heroSub, msBtn);
        hero.getStyleClass().add("ms-hero");
        hero.setStyle("-fx-padding: 16 18 16 18;");   // the stylesheet's 24/26 padding is for a full-width hero

        statusLabel.getStyleClass().add("play-card-hint");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        // Saved Microsoft sessions sit under the Microsoft card; the list hides itself when there are none.
        savedSessionsHeader = sectionLabel("SAVED MICROSOFT SESSIONS");
        refreshAccountList();

        // Astro sign-in / sign-out changes what "Playing as" offers.
        astro.addListener(astroListener);
        root.sceneProperty().addListener((o, was, now) -> { if (now == null) astro.removeListener(astroListener); });

        // ── Astro ID card ──
        astroCard = new AstroIdCard(appContext, null);
        astroCard.setStyle("-fx-padding: 16 18 16 18;");

        VBox.setMargin(savedSessionsHeader, new Insets(4, 0, 0, 0));
        VBox left = new VBox(10, sectionLabel("SIGN IN"), hero, statusLabel, savedSessionsHeader, accountListBox,
                orDivider(), astroCard);
        left.setMinWidth(0);

        // ══ RIGHT column: the account list (it decides whose skin is edited) and the skins ══
        Node accountsHeader = sectionLabel("PLAYING AS");
        Node skinsHeader = sectionLabel("SKINS");
        VBox.setMargin(skinsHeader, new Insets(8, 0, 0, 0));
        skinsPanel = new SkinsPanel(appContext, this::rebuildAccountRows);

        // A framed card matching the sign-in cards on the left.
        VBox accountsCard = new VBox(playingAsBox);
        accountsCard.getStyleClass().add("settings-section");
        accountsCard.setPadding(new Insets(14));

        VBox right = new VBox(10, accountsHeader, accountsCard, skinsHeader, skinsPanel);
        right.setMinWidth(0);

        // ── Two columns: sign-in on the left, skins on the right ──
        GridPane body = new GridPane();
        body.setHgap(22);
        ColumnConstraints leftCol = new ColumnConstraints();
        leftCol.setPercentWidth(42);
        leftCol.setMinWidth(340);
        ColumnConstraints rightCol = new ColumnConstraints();
        rightCol.setPercentWidth(58);
        body.getColumnConstraints().addAll(leftCol, rightCol);
        body.add(left, 0, 0);
        body.add(right, 1, 0);
        GridPane.setValignment(left, VPos.TOP);
        GridPane.setValignment(right, VPos.TOP);

        root.getChildren().add(body);
        return root;
    }

    // ── Account list ───────────────────────────────────────────────────────

    /** Rebuilds the account rows AND the skins panel (the active account decides where skins are applied). */
    private void refreshAccountList() {
        rebuildAccountRows();
        if (skinsPanel != null) skinsPanel.refresh();
    }

    private void rebuildAccountRows() {
        // Always re-read: tokens are refreshed and saved in the background, and this page must never
        // write an out-of-date copy of them back to disk.
        AccountChoice.ensureValid(appContext);
        accounts = appContext.accountRepository.load();

        accountListBox.getChildren().clear();
        boolean anyMicrosoft = false;
        for (Account account : accounts) {
            if (account.type != Account.Type.MICROSOFT) continue;
            anyMicrosoft = true;
            accountListBox.getChildren().add(buildAccountTile(account));
        }
        if (savedSessionsHeader != null) {
            savedSessionsHeader.setVisible(anyMicrosoft);
            savedSessionsHeader.setManaged(anyMicrosoft);
        }
        accountListBox.setVisible(anyMicrosoft);
        accountListBox.setManaged(anyMicrosoft);

        rebuildPlayingAs();
    }

    // ── Playing as ─────────────────────────────────────────────────────────

    /** Exactly two options: the Astro account and the Microsoft account. Play uses the selected one. */
    private void rebuildPlayingAs() {
        Account astroAcc = AccountChoice.astro(appContext);
        Account msAcc = AccountChoice.preferredMicrosoft(appContext);
        AccountChoice.Kind selected = AccountChoice.selectedKind(appContext);

        playingAsBox.getChildren().setAll(
                playingAsOption(AccountChoice.Kind.ASTRO, astroAcc, selected == AccountChoice.Kind.ASTRO),
                playingAsOption(AccountChoice.Kind.MICROSOFT, msAcc, selected == AccountChoice.Kind.MICROSOFT));

        Label hint = new Label(astroAcc == null && msAcc == null
                ? "Sign in on the left to start playing."
                : astroAcc == null || msAcc == null
                    ? "Only one account is signed in, so Play uses it automatically."
                    : "Play launches with the account selected here. The top bar shows it too.");
        hint.getStyleClass().add("play-card-hint");
        hint.setWrapText(true);
        playingAsBox.getChildren().add(hint);
    }

    private Node playingAsOption(AccountChoice.Kind kind, Account account, boolean selected) {
        boolean astroKind = kind == AccountChoice.Kind.ASTRO;
        boolean ready = account != null;

        Label tag = new Label(astroKind ? "ASTRO ACCOUNT" : "MICROSOFT ACCOUNT");
        tag.getStyleClass().addAll("acct-tile-tag", astroKind ? "acct-tag-astro" : "acct-tag-ms");

        Label name;
        if (ready) {
            name = new Label(account.username);
        } else if (astroKind && astro.isSignedIn()) {
            name = new Label("Choose a player name below");
        } else {
            name = new Label("Not signed in");
        }
        name.getStyleClass().add("acct-tile-name");
        name.setMinWidth(0);

        VBox text = new VBox(3, tag, name);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Node face;
        if (ready) {
            face = buildAvatar(account);
        } else {
            StackPane blank = new StackPane(new Label(astroKind ? "A" : "M"));
            blank.setMinSize(42, 42); blank.setMaxSize(42, 42);
            blank.setStyle("-fx-background-color: rgba(255,255,255,0.06);");
            blank.setOpacity(0.5);
            face = blank;
        }

        HBox tile = new HBox(12, face, text);
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.getStyleClass().add("acct-tile");
        if (selected) tile.getStyleClass().add("acct-tile-active");
        tile.setMaxWidth(Double.MAX_VALUE);

        if (selected) {
            Label badge = new Label("PLAYING");
            badge.getStyleClass().add("acct-tile-badge");
            tile.getChildren().add(badge);
        } else if (ready) {
            Label use = new Label("USE");
            use.getStyleClass().add("acct-tile-use");
            tile.getChildren().add(use);
            Runnable choose = () -> {
                AccountChoice.select(appContext, kind);
                refreshAccountList();
            };
            tile.setCursor(Cursor.HAND);
            tile.setFocusTraversable(true);
            tile.setOnMouseClicked(e -> choose.run());
            tile.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { choose.run(); e.consume(); }
            });
        } else {
            tile.setOpacity(0.6);
        }
        return tile;
    }

    /** Small section heading: a red tick, the title, and a hairline that runs out to the edge of the column. */
    private static Node sectionLabel(String text) {
        Region bar = new Region();
        bar.getStyleClass().add("sect-bar");
        bar.setMinSize(3, 14); bar.setPrefSize(3, 14); bar.setMaxSize(3, 14);
        Label l = new Label(text);
        l.getStyleClass().add("card-title");
        Region line = new Region();
        line.getStyleClass().add("sect-line");
        line.setMinHeight(1); line.setPrefHeight(1); line.setMaxHeight(1);
        HBox.setHgrow(line, Priority.ALWAYS);
        HBox row = new HBox(8, bar, l, line);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** "—— OR ——" between the two ways of signing in. */
    private static Node orDivider() {
        Region l = new Region(), r = new Region();
        for (Region x : new Region[] {l, r}) {
            x.getStyleClass().add("sect-line");
            x.setMinHeight(1); x.setPrefHeight(1); x.setMaxHeight(1);
            HBox.setHgrow(x, Priority.ALWAYS);
        }
        Label or = new Label("OR");
        or.getStyleClass().add("sect-or");
        HBox row = new HBox(10, l, or, r);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /**
     * One saved Microsoft session as a compact tile: face, name. The one in use has a red edge; clicking another
     * switches to it (and selects Microsoft as the account to play as). The cross removes the saved session.
     */
    private Node buildAccountTile(Account account) {
        boolean isActive = account.id.equals(appContext.settings.activeAccountId);

        StackPane avatar = buildAvatar(account);

        Label name = new Label(account.username);
        name.getStyleClass().add("acct-tile-name");
        name.setMinWidth(0);

        Label tag = new Label("MICROSOFT");
        tag.getStyleClass().addAll("acct-tile-tag", "acct-tag-ms");
        HBox tagRow = new HBox(8, tag);
        tagRow.setAlignment(Pos.CENTER_LEFT);

        VBox text = new VBox(3, name, tagRow);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox tile = new HBox(12, avatar, text);
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.getStyleClass().add("acct-tile");
        if (isActive) tile.getStyleClass().add("acct-tile-active");
        tile.setPrefWidth(246);
        tile.setMinWidth(246);

        if (isActive) {
            Label badge = new Label("PLAYING");
            badge.getStyleClass().add("acct-tile-badge");
            tile.getChildren().add(badge);
        } else {
            Label use = new Label("USE");
            use.getStyleClass().add("acct-tile-use");
            tile.getChildren().add(use);
            Runnable choose = () -> {
                AccountChoice.select(appContext, account);
                refreshAccountList();
            };
            tile.setCursor(Cursor.HAND);
            tile.setFocusTraversable(true);
            tile.setOnMouseClicked(e -> choose.run());
            tile.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { choose.run(); e.consume(); }
            });
        }

        Button removeBtn = new Button("\u2715");
        removeBtn.getStyleClass().add("acct-tile-x");
        removeBtn.setTooltip(new Tooltip("Remove this saved Microsoft session"));
        removeBtn.setOnMouseClicked(MouseEvent::consume);   // removing must not also "choose" the tile underneath
        removeBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            DialogStyler.style(confirm);
            confirm.setTitle("Remove Account");
            confirm.setHeaderText("Remove \u201c" + account.username + "\u201d?");
            confirm.setContentText("This removes the saved session from this launcher. "
                    + "You can always sign back in.");
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                // One locked read-modify-write, so a background token refresh can't write the removed account back.
                appContext.accountRepository.modify(all -> all.removeIf(a -> a.id.equals(account.id)));
                if (account.id.equals(appContext.settings.lastMicrosoftAccountId)) {
                    appContext.settings.lastMicrosoftAccountId = null;
                    appContext.settingsRepository.save(appContext.settings);
                }
                // Selection falls back to whatever is still signed in (Astro account, or another Microsoft session).
                if (account.id.equals(appContext.settings.activeAccountId)) {
                    appContext.settings.activeAccountId = null;
                    appContext.settingsRepository.save(appContext.settings);
                }
                refreshAccountList();
            });
        });
        tile.getChildren().add(removeBtn);
        return tile;
    }

    /** 42px rounded avatar. Microsoft accounts load their skin face; everything falls back to a coloured initial. */
    private static StackPane buildAvatar(Account account) {
        boolean ms = account.type == Account.Type.MICROSOFT;
        StackPane avatar = new StackPane();
        avatar.setMinSize(42, 42); avatar.setMaxSize(42, 42);
        avatar.setStyle(ms
                ? "-fx-background-color: linear-gradient(to bottom right, #2a6a9c, #14304f); -fx-background-radius: 0;"
                : "-fx-background-color: linear-gradient(to bottom right, #8a6a1f, #3d2d0a); -fx-background-radius: 0;");
        Label initial = new Label(account.username != null && !account.username.isBlank()
                ? account.username.substring(0, 1).toUpperCase() : "?");
        initial.setStyle("-fx-text-fill: " + (ms ? "#ece8e1" : "#f0c060") + "; -fx-font-size: 17px; -fx-font-weight: bold;");
        avatar.getChildren().add(initial);

        if (!ms && account.offlineSkinId != null && !account.offlineSkinId.isBlank()) {
            // Offline account wearing a library skin: draw its real face from the saved PNG.
            java.nio.file.Path png = new com.astrolauncher.skins.SkinLibrary().pngPath(account.offlineSkinId);
            if (java.nio.file.Files.isRegularFile(png)) {
                com.astrolauncher.ui.kit.SkinFace.load(png.toUri().toString(), face -> {
                    if (face == null) return;
                    ImageView fv = new ImageView(face);
                    fv.setFitWidth(42); fv.setFitHeight(42); fv.setSmooth(true);
                    avatar.getChildren().setAll(fv);
                });
            }
        }
        if (ms && account.minecraftUuid != null && !account.minecraftUuid.isBlank()) {
            try {
                String id = account.minecraftUuid.replace("-", "");
                // 4x the displayed size, shown smoothed down: crisp on any screen scaling.
                Image img = new Image("https://mc-heads.net/avatar/" + id + "/168", 168, 168, true, true, true);
                ImageView iv = new ImageView(img);
                iv.setSmooth(true);
                iv.setFitWidth(42); iv.setFitHeight(42);
                Rectangle clip = new Rectangle(42, 42);
                clip.setArcWidth(0); clip.setArcHeight(0);
                iv.setClip(clip);
                img.progressProperty().addListener((o, was, now) -> {
                    if (now.doubleValue() >= 1.0 && !img.isError() && !avatar.getChildren().contains(iv)) {
                        avatar.getChildren().setAll(iv);
                    }
                });
            } catch (Exception ignored) { /* keep the initial */ }
        }
        return avatar;
    }

    private static Label autoChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("auto-chip");
        l.setMinWidth(Region.USE_PREF_SIZE); // never collapse to "..."
        return l;
    }

    /** Four-square Microsoft mark, drawn so no image asset is needed. */
    private static Node microsoftLogo(double size) {
        double g = size * 0.06, c = (size - g) / 2;
        Rectangle r1 = new Rectangle(c, c, Color.web("#f25022"));
        Rectangle r2 = new Rectangle(c, c, Color.web("#7fba00"));
        Rectangle r3 = new Rectangle(c, c, Color.web("#00a4ef"));
        Rectangle r4 = new Rectangle(c, c, Color.web("#ffb900"));
        GridPane grid = new GridPane();
        grid.setHgap(g); grid.setVgap(g);
        grid.add(r1, 0, 0); grid.add(r2, 1, 0); grid.add(r3, 0, 1); grid.add(r4, 1, 1);
        StackPane box = new StackPane(grid);
        box.setMinSize(size + 18, size + 18); box.setMaxSize(size + 18, size + 18);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 0;");
        return box;
    }

    // ── Microsoft sign-in ──────────────────────────────────────────────────

    private void startMicrosoftSignIn() {
        setStatus("Requesting sign-in code from Microsoft\u2026");
        appContext.microsoftAuthService.signIn(this::showDeviceCode)
                .whenComplete((account, ex) -> Platform.runLater(() -> {
                    closeDeviceAlert();
                    if (ex != null) {
                        setStatus("Sign-in failed: " + rootMsg(ex));
                        AppLogger.get().error(TAG, "Microsoft sign-in failed", ex);
                    } else {
                        accounts = appContext.accountRepository.load();
                        accounts.removeIf(a -> a.minecraftUuid != null
                                && a.minecraftUuid.equals(account.minecraftUuid));
                        accounts.add(account);
                        appContext.accountRepository.save(accounts);
                        appContext.settings.activeAccountId = account.id;
                        appContext.settings.lastMicrosoftAccountId = account.id;
                        appContext.settingsRepository.save(appContext.settings);
                        refreshAccountList();
                        setStatus("Signed in as " + account.username + ".");
                    }
                }));
    }

    private void showDeviceCode(AuthModels.DeviceCodeResponse code) {
        Platform.runLater(() -> {
            setStatus("Go to " + code.verification_uri + " and enter code: " + code.user_code);
            copyToClipboard(code.user_code);   // already on the clipboard; the dialog lets you copy again
            final String pageUrl = prefilledUrl(code); // opens with the code already typed in where Microsoft supports it
            openBrowser(pageUrl);

            closeDeviceAlert();
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle("Sign in with Microsoft");
            dlg.setHeaderText(null);
            try {
                javafx.scene.Scene owner = statusLabel.getScene();
                if (owner != null) {
                    dlg.initOwner(owner.getWindow());
                    dlg.getDialogPane().getStylesheets().setAll(owner.getStylesheets());
                }
            } catch (Exception ignored) { }
            DialogPane pane = dlg.getDialogPane();
            pane.getStyleClass().add("device-dialog");

            Label heading = new Label("Sign in with Microsoft");
            heading.getStyleClass().add("device-title");
            Label step = new Label("Your browser was opened. Paste this code on the Microsoft page:");
            step.getStyleClass().add("device-step");
            step.setWrapText(true);

            // Read-only but selectable, so the code can be copied by hand too (Ctrl+C / right-click).
            TextField codeField = new TextField(code.user_code);
            codeField.setEditable(false);
            codeField.getStyleClass().add("device-code-field");
            codeField.setAlignment(Pos.CENTER);
            codeField.setOnMouseClicked(e -> codeField.selectAll());

            Button copyBtn = new Button("Copy code");
            copyBtn.getStyleClass().add("primary-cta-button");
            copyBtn.setDefaultButton(false);
            copyBtn.setOnAction(e -> {
                copyToClipboard(code.user_code);
                codeField.selectAll();
                copyBtn.setText("Copied \u2713");
                javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.6));
                t.setOnFinished(ev -> copyBtn.setText("Copy code"));
                t.play();
            });
            Button openBtn = new Button("Open Microsoft page");
            openBtn.getStyleClass().add("secondary-button");
            openBtn.setOnAction(e -> openBrowser(pageUrl));
            HBox buttons = new HBox(10, copyBtn, openBtn);
            buttons.setAlignment(Pos.CENTER);

            Label uri = new Label(code.verification_uri);
            uri.getStyleClass().add("device-uri");

            ProgressIndicator spin = new ProgressIndicator();
            spin.setPrefSize(16, 16);
            spin.setMaxSize(16, 16);
            Label timer = new Label();
            timer.getStyleClass().add("device-timer");
            startDeviceTimer(timer, code.expires_in);
            Label waiting = new Label("Waiting for you to finish \u2014 this window closes automatically.");
            waiting.getStyleClass().add("device-waiting");
            waiting.setWrapText(true);
            HBox wait = new HBox(10, spin, waiting);
            wait.setAlignment(Pos.CENTER_LEFT);

            VBox box = new VBox(14, heading, step, codeField, buttons, uri, wait, timer);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(6, 10, 4, 10));
            box.setPrefWidth(400);
            pane.setContent(box);
            pane.getButtonTypes().setAll(ButtonType.CLOSE);

            dlg.show();
            deviceAlert = dlg;
            Platform.runLater(() -> { codeField.requestFocus(); codeField.selectAll(); });
        });
    }

    private static void copyToClipboard(String text) {
        try {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        } catch (Exception e) { AppLogger.get().warn(TAG, "Clipboard unavailable: " + e.getMessage()); }
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception e) { AppLogger.get().warn(TAG, "Could not open browser: " + e.getMessage()); }
    }

    private void closeDeviceAlert() {
        if (deviceTimer != null) { deviceTimer.stop(); deviceTimer = null; }
        if (deviceAlert != null) { deviceAlert.hide(); deviceAlert = null; }
    }

    /** Microsoft's /link page accepts ?otc=CODE to pre-fill the code; if it ignores it, the code is still on the clipboard. */
    private static String prefilledUrl(AuthModels.DeviceCodeResponse code) {
        String base = code.verification_uri;
        if (base == null || code.user_code == null) return base;
        if (base.contains("?")) return base;
        return base + "?otc=" + java.net.URLEncoder.encode(code.user_code, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void startDeviceTimer(Label label, int seconds) {
        if (deviceTimer != null) deviceTimer.stop();
        final int[] left = { Math.max(seconds, 0) };
        Runnable paint = () -> label.setText(left[0] > 0
                ? String.format("Code expires in %d:%02d", left[0] / 60, left[0] % 60) : "Code expired \u2014 close this and try again.");
        paint.run();
        deviceTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), ev -> {
            if (left[0] > 0) left[0]--;
            paint.run();
        }));
        deviceTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        deviceTimer.play();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setStatus(String text) {
        statusLabel.setText(text);
        boolean show = text != null && !text.isEmpty();
        statusLabel.setVisible(show);
        statusLabel.setManaged(show);
    }

    private static String rootMsg(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        String m = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        return m != null && !m.isBlank() ? m : "Something went wrong. Please try again.";
    }
}
