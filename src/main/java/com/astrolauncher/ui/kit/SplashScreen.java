package com.astrolauncher.ui.kit;

import com.astrolauncher.ui.Motion;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's boot splash — a Valorant-style agent reveal built around the player's active
 * skin: a bank of magenta motion-streaks whips in from the right, the character slides in behind
 * them, the HUD brackets snap into the corners, the "ASTRO / LAUNCHER" wordmark types itself in
 * on the left, and a shimmering loading bar runs underneath it for the hold. Held on screen for at
 * least 5 seconds total, then everything fades out together and removes itself. Nothing outside
 * this class needs to know it exists — {@link #playIntro} runs the whole sequence and calls back
 * when it's done.
 *
 * <p>Honors Low-End Mode / "animations off" ({@link Motion#enabled()}): every moving piece still
 * ends up in its final, fully-visible state (nothing is ever permanently missing), it just gets
 * there with a quick fade instead of sliding, sweeping or shimmering.
 */
public final class SplashScreen extends StackPane {

    private final Rectangle scanline = new Rectangle();
    private final List<Text> letters = new ArrayList<>();
    private final List<Polyline> brackets = new ArrayList<>();
    private final List<Rectangle> streaks = new ArrayList<>();
    private final List<Circle> embers = new ArrayList<>();
    private final List<Timeline> emberLoops = new ArrayList<>();

    private final VBox mark;
    private final Rectangle underline;
    private final Text subtitle;
    private final javafx.scene.shape.Polygon accentMark;
    private final ImageView character;
    private final Circle characterGlow = new Circle(190);

    private final Rectangle loadingFill = new Rectangle();
    private final Text loadingLabel = new Text("LOADING");
    private final StackPane loadingBar;

    private Timeline loadingLoop;
    private Timeline glowPulse;
    private Timeline characterFloat;

    public SplashScreen() {
        getStyleClass().add("splash-root");
        setPickOnBounds(true); // block input to the shell underneath while the intro plays

        Region backdrop = new Region();
        backdrop.getStyleClass().add("splash-backdrop");
        backdrop.prefWidthProperty().bind(widthProperty());
        backdrop.prefHeightProperty().bind(heightProperty());

        // The same fine grain texture used elsewhere in the app, tiled very faintly over the whole
        // backdrop — stops the big flat gradient from looking digital/banded and gives it the same
        // slightly textured finish as the rest of the launcher.
        Region grain = new Region();
        grain.getStyleClass().add("splash-grain");
        grain.prefWidthProperty().bind(widthProperty());
        grain.prefHeightProperty().bind(heightProperty());
        grain.setMouseTransparent(true);

        // A single bright line that sweeps top -> bottom once, behind everything, like a boot scan.
        scanline.getStyleClass().add("splash-scanline");
        scanline.setHeight(3);
        scanline.setMouseTransparent(true);
        scanline.widthProperty().bind(widthProperty());
        scanline.setOpacity(0);

        // Soft magenta glow seated behind the character, the way a key-light rims an agent reveal.
        characterGlow.getStyleClass().add("splash-character-glow");
        characterGlow.setMouseTransparent(true);
        characterGlow.setEffect(new GaussianBlur(80));
        characterGlow.setOpacity(0);

        // Diagonal motion-streaks the character slides in behind, fanned out to the right.
        // Fixed, hand-tuned bars (not dynamically computed) so the geometry is predictable and
        // can't come out skewed or oversized on a given window size.
        double[][] streakSpec = { // {widthPx, rightMarginPx, opacity}
                {260, -60, 0.45},
                {150, 70, 0.75},
                {70, 190, 1.00},
        };
        for (double[] spec : streakSpec) {
            streaks.add(buildStreak(spec[0], spec[1], spec[2]));
        }

        // The character art itself: anchored bottom-right, slides in from off-screen on entrance.
        character = new ImageView(loadCharacterArt());
        character.getStyleClass().add("splash-character");
        character.setPreserveRatio(true);
        character.setMouseTransparent(true);
        character.setSmooth(true);
        character.fitHeightProperty().bind(heightProperty().multiply(0.50));
        character.setOpacity(0);

        // A soft, blurred grounding shadow under the character's feet — without it, a cutout this
        // size reads as pasted on rather than standing in the scene. Pinned to the same vertical
        // center line as the character and offset down by half its height, so it tracks the
        // character's feet regardless of how tall it ends up on screen.
        javafx.scene.shape.Ellipse groundShadow = new javafx.scene.shape.Ellipse(110, 18);
        groundShadow.getStyleClass().add("splash-ground-shadow");
        groundShadow.setEffect(new javafx.scene.effect.GaussianBlur(26));
        groundShadow.setMouseTransparent(true);

        // A handful of small drifting embers/sparks around the character — rise slowly, fade out,
        // loop. Pure atmosphere, the kind of detail that sells a "cinematic" reveal over a static one.
        double[][] emberSpec = { // {sizePx, rightMarginPx, topMarginPx, periodMs, delayMs}
                {3.5, 260, 90, 2600, 0},
                {2.5, 90, 180, 3100, 500},
                {4.0, 340, 260, 2900, 1100},
                {2.0, 160, 360, 2400, 300},
                {3.0, 40, 310, 3400, 900},
        };
        for (double[] spec : emberSpec) {
            Circle ember = buildEmber(spec[0], spec[1], spec[2]);
            ember.getProperties().put("periodMs", spec[3]);
            ember.getProperties().put("delayMs", spec[4]);
            embers.add(ember);
        }

        StackPane rightArt = new StackPane();
        rightArt.setMouseTransparent(true);
        rightArt.getChildren().add(characterGlow);
        rightArt.getChildren().addAll(streaks);
        rightArt.getChildren().add(groundShadow);
        rightArt.getChildren().add(character);
        rightArt.getChildren().addAll(embers);
        StackPane.setAlignment(characterGlow, Pos.CENTER_RIGHT);
        // Centered vertically on the right half, nudged a little below dead-center (extra top
        // margin vs. bottom margin shifts the centered area downward).
        StackPane.setAlignment(character, Pos.CENTER_RIGHT);
        StackPane.setMargin(character, new Insets(60, 130, 0, 0));
        StackPane.setAlignment(groundShadow, Pos.CENTER_RIGHT);
        StackPane.setMargin(groundShadow, new Insets(60, 130, 0, 0));
        groundShadow.translateYProperty().bind(character.fitHeightProperty().divide(2).subtract(14));

        // A dark gradient along the left edge keeps the wordmark legible over the character art.
        Region legibilityVeil = new Region();
        legibilityVeil.getStyleClass().add("splash-veil");
        legibilityVeil.setMouseTransparent(true);

        // Four HUD-style corner brackets, framing the whole screen — snap inward on entrance.
        for (Pos corner : new Pos[] {Pos.TOP_LEFT, Pos.TOP_RIGHT, Pos.BOTTOM_LEFT, Pos.BOTTOM_RIGHT}) {
            brackets.add(buildBracket(corner));
        }

        // A small diamond accent beside the wordmark — the one classic "logo mark" touch, echoing
        // the brand pip in the Riot wordmark reference.
        accentMark = new javafx.scene.shape.Polygon(6, 0, 12, 6, 6, 12, 0, 6);
        accentMark.getStyleClass().add("splash-accent-mark");
        accentMark.setOpacity(0);

        String word = "ASTRO";
        HBox lettersRow = new HBox(4);
        lettersRow.setAlignment(Pos.CENTER_LEFT);
        lettersRow.getChildren().add(accentMark);
        HBox.setMargin(accentMark, new Insets(0, 10, 0, 0));
        for (char c : word.toCharArray()) {
            Text t = new Text(String.valueOf(c));
            t.getStyleClass().add("splash-wordmark");
            t.setOpacity(0);
            t.setTranslateY(16);
            letters.add(t);
            lettersRow.getChildren().add(t);
        }

        subtitle = new Text("LAUNCHER");
        subtitle.getStyleClass().add("splash-subtitle");
        subtitle.setOpacity(0);

        underline = new Rectangle(220, 2);
        underline.getStyleClass().add("splash-underline");
        underline.setFill(magentaGradient(0));
        underline.setScaleX(0);

        mark = new VBox(2, lettersRow, underline, subtitle);
        mark.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(underline, new Insets(10, 0, 12, 0));

        loadingBar = buildLoadingBar();
        loadingBar.setOpacity(0);

        VBox leftContent = new VBox(26, mark, loadingBar);
        leftContent.setAlignment(Pos.CENTER_LEFT);
        leftContent.setMaxWidth(420);
        StackPane.setAlignment(leftContent, Pos.CENTER_LEFT);
        StackPane.setMargin(leftContent, new Insets(0, 0, 0, 96));

        getChildren().add(backdrop);
        getChildren().add(grain);
        getChildren().add(rightArt);
        getChildren().add(legibilityVeil);
        getChildren().add(scanline);
        getChildren().addAll(brackets);
        getChildren().add(leftContent);
        for (Polyline b : brackets) StackPane.setAlignment(b, cornerOf(b));
        setAlignment(Pos.CENTER);

        setOpacity(0);
    }

    private static Pos cornerOf(Polyline b) {
        return (Pos) b.getProperties().get("corner");
    }

    /**
     * One straight diagonal ribbon: a plain rotated rectangle, comfortably oversized and recentred
     * on its own middle so the rotation can never pull a visible gap or odd corner into frame.
     * Fixed pixel geometry on purpose — predictable at any window size, nothing computed from
     * bindings that could come out stretched or skewed.
     */
    private Rectangle buildStreak(double width, double rightMargin, double opacity) {
        Rectangle r = new Rectangle(width, 1400);
        r.getProperties().put("baseOpacity", opacity);
        r.setOpacity(0);
        r.setMouseTransparent(true);
        r.setFill(magentaGradient(opacity));
        r.setRotate(-18);
        // A soft blur turns the flat-edged bar into a painterly light ribbon instead of a hard slab.
        r.setEffect(new javafx.scene.effect.GaussianBlur(22));
        // Centered vertically and oversized (1400 tall) so it overshoots top and bottom evenly —
        // no edge of it is ever visible, whatever the window's actual height is.
        StackPane.setAlignment(r, Pos.CENTER_RIGHT);
        StackPane.setMargin(r, new Insets(0, rightMargin, 0, 0));
        return r;
    }

    /** One small glowing spark, parked at a fixed spot (top-right alignment) until its drift loop starts it rising. */
    private Circle buildEmber(double radius, double rightMargin, double topMargin) {
        Circle c = new Circle(radius);
        c.getStyleClass().add("splash-ember");
        c.setEffect(new GaussianBlur(radius));
        c.setMouseTransparent(true);
        c.setOpacity(0);
        StackPane.setAlignment(c, Pos.TOP_RIGHT);
        StackPane.setMargin(c, new Insets(topMargin, rightMargin, 0, 0));
        return c;
    }

    private static LinearGradient magentaGradient(double opacity) {
        return new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ff2f93", 0)),
                new Stop(0.45, Color.web("#ff2f93", 0.65 * opacity)),
                new Stop(1, Color.web("#b24bff", 0.9 * opacity)));
    }

    /** One L-shaped bracket, oriented and offset for the given screen corner. */
    private static Polyline buildBracket(Pos corner) {
        double arm = 34;
        Polyline p;
        Insets margin;
        switch (corner) {
            case TOP_RIGHT -> { p = new Polyline(0, 0, arm, 0, arm, arm); margin = new Insets(34, 34, 0, 0); }
            case BOTTOM_LEFT -> { p = new Polyline(0, 0, 0, arm, arm, arm); margin = new Insets(0, 0, 34, 34); }
            case BOTTOM_RIGHT -> { p = new Polyline(arm, 0, arm, arm, 0, arm); margin = new Insets(0, 34, 34, 0); }
            default -> { p = new Polyline(0, arm, 0, 0, arm, 0); margin = new Insets(34, 0, 0, 34); } // TOP_LEFT
        }
        p.getStyleClass().add("splash-bracket");
        p.getProperties().put("corner", corner);
        StackPane.setMargin(p, margin);
        p.setMouseTransparent(true);
        return p;
    }

    /** Thin track + shimmering fill + "LOADING" label with an animated ellipsis, left-aligned. */
    private StackPane buildLoadingBar() {
        Rectangle track = new Rectangle(240, 3);
        track.getStyleClass().add("splash-loading-track");

        loadingFill.setHeight(3);
        loadingFill.setWidth(0);
        loadingFill.getStyleClass().add("splash-loading-fill");
        loadingFill.setFill(magentaGradient(1));

        Pane track2 = new Pane(track, loadingFill);
        track2.setMaxWidth(240);
        track2.setPrefSize(240, 3);
        StackPane.setAlignment(track, Pos.CENTER_LEFT);
        StackPane.setAlignment(loadingFill, Pos.CENTER_LEFT);

        loadingLabel.getStyleClass().add("splash-loading-label");

        VBox box = new VBox(10, loadingLabel, track2);
        box.setAlignment(Pos.CENTER_LEFT);
        return new StackPane(box);
    }

    /** The player's active skin, bundled for the splash. Null (skipped) only if it's missing. */
    private static Image loadCharacterArt() {
        try {
            var stream = SplashScreen.class.getResourceAsStream("/images/splash-character.png");
            if (stream == null) return null;
            return new Image(stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Plays the full entrance + hold + exit sequence, then runs {@code onDone} (which should remove
     * this node from its parent). Safe to call once; this node is meant to be thrown away after.
     */
    public void playIntro(Runnable onDone) {
        boolean live = Motion.enabled();

        FadeTransition rootIn = new FadeTransition(Duration.millis(live ? 260 : 130), this);
        rootIn.setFromValue(0);
        rootIn.setToValue(1);

        // Stage 1 — HUD brackets snap inward from just past their resting spot, staggered per corner.
        ParallelTransition bracketsIn = new ParallelTransition();
        for (int i = 0; i < brackets.size(); i++) {
            Polyline b = brackets.get(i);
            if (!live) { b.setOpacity(1); continue; }
            double dx = (cornerOf(b) == Pos.TOP_RIGHT || cornerOf(b) == Pos.BOTTOM_RIGHT) ? 16 : -16;
            double dy = (cornerOf(b) == Pos.BOTTOM_LEFT || cornerOf(b) == Pos.BOTTOM_RIGHT) ? 16 : -16;
            b.setOpacity(0);
            b.setTranslateX(dx);
            b.setTranslateY(dy);
            FadeTransition f = new FadeTransition(Duration.millis(260), b);
            f.setFromValue(0); f.setToValue(1);
            TranslateTransition t = new TranslateTransition(Duration.millis(320), b);
            t.setToX(0); t.setToY(0);
            t.setInterpolator(Motion.EASE);
            ParallelTransition one = new ParallelTransition(f, t);
            one.setDelay(Duration.millis(i * 60));
            bracketsIn.getChildren().add(one);
        }

        // Stage 2 — a bright line sweeps once from top to bottom, like a boot scan.
        SequentialTransition scan = new SequentialTransition();
        if (live) {
            double travel = Math.max(getHeight(), 720) + 40;
            scanline.setTranslateY(-20);
            FadeTransition scanFadeIn = new FadeTransition(Duration.millis(120), scanline);
            scanFadeIn.setFromValue(0); scanFadeIn.setToValue(0.9);
            TranslateTransition scanMove = new TranslateTransition(Duration.millis(900), scanline);
            scanMove.setToY(travel);
            scanMove.setInterpolator(Interpolator.EASE_IN);
            FadeTransition scanFadeOut = new FadeTransition(Duration.millis(200), scanline);
            scanFadeOut.setFromValue(0.9); scanFadeOut.setToValue(0);
            scan.getChildren().addAll(scanFadeIn, scanMove, scanFadeOut);
        }

        // Stage 3 — the motion-streaks whip in from off-screen right, fastest streak first.
        ParallelTransition streaksIn = new ParallelTransition();
        for (int i = 0; i < streaks.size(); i++) {
            Rectangle s = streaks.get(i);
            if (!live) { s.setOpacity((double) s.getProperties().getOrDefault("baseOpacity", 1.0)); continue; }
            s.setOpacity(0);
            s.setTranslateX(420);
            FadeTransition f = new FadeTransition(Duration.millis(220), s);
            f.setFromValue(0); f.setToValue(1);
            TranslateTransition t = new TranslateTransition(Duration.millis(520), s);
            t.setToX(0);
            t.setInterpolator(Motion.EASE);
            ParallelTransition one = new ParallelTransition(f, t);
            one.setDelay(Duration.millis(i * 90));
            streaksIn.getChildren().add(one);
        }

        // Stage 4 — the glow blooms, then the character itself slides in from the right and settles.
        FadeTransition glowIn = new FadeTransition(Duration.millis(live ? 500 : 140), characterGlow);
        glowIn.setFromValue(0);
        glowIn.setToValue(live ? 0.55 : 0.45);
        glowIn.setDelay(Duration.millis(live ? 120 : 0));

        character.setTranslateX(live ? 360 : 0);
        FadeTransition charFade = new FadeTransition(Duration.millis(live ? 420 : 160), character);
        charFade.setFromValue(0);
        charFade.setToValue(1);
        TranslateTransition charSlide = new TranslateTransition(Duration.millis(live ? 620 : 1), character);
        charSlide.setToX(0);
        charSlide.setInterpolator(Motion.EASE);
        ParallelTransition charIn = new ParallelTransition(charFade, charSlide);
        charIn.setDelay(Duration.millis(live ? 260 : 0));

        // Stage 5 — the wordmark reveals itself one letter at a time, over the character's left edge.
        ParallelTransition lettersIn = new ParallelTransition();
        if (!live) {
            accentMark.setOpacity(1);
        } else {
            FadeTransition accentIn = new FadeTransition(Duration.millis(220), accentMark);
            accentIn.setFromValue(0);
            accentIn.setToValue(1);
            accentIn.setDelay(Duration.millis(600));
            lettersIn.getChildren().add(accentIn);
        }
        for (int i = 0; i < letters.size(); i++) {
            Text letter = letters.get(i);
            if (!live) { letter.setOpacity(1); letter.setTranslateY(0); continue; }
            FadeTransition f = new FadeTransition(Duration.millis(260), letter);
            f.setFromValue(0); f.setToValue(1);
            TranslateTransition t = new TranslateTransition(Duration.millis(300), letter);
            t.setToY(0);
            t.setInterpolator(Motion.EASE);
            ParallelTransition one = new ParallelTransition(f, t);
            one.setDelay(Duration.millis(640 + i * 70));
            lettersIn.getChildren().add(one);
        }

        // Stage 6 — the underline sweeps out, then the subtitle fades in beneath it.
        Timeline underlineSweep = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(underline.scaleXProperty(), 0, Motion.EASE)),
                new KeyFrame(Duration.millis(live ? 420 : 1), new KeyValue(underline.scaleXProperty(), 1, Motion.EASE)));
        underlineSweep.setDelay(Duration.millis(live ? 1040 : 0));

        FadeTransition subtitleIn = new FadeTransition(Duration.millis(live ? 300 : 120), subtitle);
        subtitleIn.setFromValue(0);
        subtitleIn.setToValue(1);
        subtitleIn.setDelay(Duration.millis(live ? 1360 : 0));

        // Stage 7 — the loading bar fades in last and starts its shimmer + fill loop for the hold.
        FadeTransition loadingIn = new FadeTransition(Duration.millis(live ? 300 : 120), loadingBar);
        loadingIn.setDelay(Duration.millis(live ? 1560 : 0));
        loadingIn.setFromValue(0);
        loadingIn.setToValue(1);

        if (live) {
            // Drives both the bar's fill and the "LOADING NN%" label from one timeline, so the two
            // can never drift out of sync with each other. Eased out — fast start, slow finish — the
            // way real progress bars feel, rather than a flat linear crawl.
            loadingFill.widthProperty().addListener((o, a, b) -> {
                int pct = (int) Math.round(Math.min(100, Math.max(0, b.doubleValue() / 240.0 * 100)));
                loadingLabel.setText("LOADING " + pct + "%");
            });
            loadingLoop = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(loadingFill.widthProperty(), 0)),
                    new KeyFrame(Duration.millis(3300),
                            new KeyValue(loadingFill.widthProperty(), 240, Interpolator.EASE_OUT)));
            loadingLoop.setDelay(Duration.millis(1560));
            loadingLoop.play();

            glowPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(characterGlow.scaleXProperty(), 0.94, Motion.EASE),
                            new KeyValue(characterGlow.scaleYProperty(), 0.94, Motion.EASE)),
                    new KeyFrame(Duration.millis(1400),
                            new KeyValue(characterGlow.scaleXProperty(), 1.08, Motion.EASE),
                            new KeyValue(characterGlow.scaleYProperty(), 1.08, Motion.EASE)));
            glowPulse.setCycleCount(Timeline.INDEFINITE);
            glowPulse.setAutoReverse(true);
            glowPulse.play();

            // A very slow, small vertical bob once the character has settled — just enough that the
            // scene reads as alive rather than a static cutout pasted over the background.
            characterFloat = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(character.translateYProperty(), 0, Motion.EASE)),
                    new KeyFrame(Duration.millis(2200), new KeyValue(character.translateYProperty(), -8, Motion.EASE)));
            characterFloat.setCycleCount(Timeline.INDEFINITE);
            characterFloat.setAutoReverse(true);
            characterFloat.setDelay(Duration.millis(900));
            characterFloat.play();

            for (Circle ember : embers) {
                double period = (double) ember.getProperties().get("periodMs");
                double emberDelay = (double) ember.getProperties().get("delayMs");
                Timeline rise = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(ember.opacityProperty(), 0),
                                new KeyValue(ember.translateYProperty(), 14)),
                        new KeyFrame(Duration.millis(period * 0.25),
                                new KeyValue(ember.opacityProperty(), 0.85)),
                        new KeyFrame(Duration.millis(period * 0.8),
                                new KeyValue(ember.opacityProperty(), 0.85)),
                        new KeyFrame(Duration.millis(period),
                                new KeyValue(ember.opacityProperty(), 0),
                                new KeyValue(ember.translateYProperty(), -150, Interpolator.EASE_OUT)));
                rise.setCycleCount(Timeline.INDEFINITE);
                rise.setDelay(Duration.millis(1200 + emberDelay));
                rise.play();
                emberLoops.add(rise);
            }
        } else {
            loadingFill.setWidth(240);
            loadingLabel.setText("LOADING 100%");
        }

        ParallelTransition entrance = new ParallelTransition(
                rootIn, bracketsIn, scan, streaksIn, glowIn, charIn, lettersIn, underlineSweep, subtitleIn, loadingIn);

        // Hold long enough that entrance + hold + exit totals at least 5s on screen, in both modes.
        PauseTransition hold = new PauseTransition(Duration.millis(live ? 3000 : 4750));

        FadeTransition exit = new FadeTransition(Duration.millis(live ? 380 : 140), this);
        exit.setFromValue(1);
        exit.setToValue(0);
        exit.setInterpolator(Motion.EASE);
        exit.setOnFinished(e -> {
            if (loadingLoop != null) loadingLoop.stop();
            if (glowPulse != null) glowPulse.stop();
            if (characterFloat != null) characterFloat.stop();
            for (Timeline t : emberLoops) t.stop();
            setVisible(false);
            if (onDone != null) onDone.run();
        });

        new SequentialTransition(entrance, hold, exit).play();
    }
}
