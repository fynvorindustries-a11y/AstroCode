package com.astrolauncher.ui.kit;

import com.astrolauncher.ui.Motion;
import javafx.scene.control.Button;
import javafx.scene.control.Skin;
import javafx.scene.control.skin.ButtonSkin;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * The launcher's button. A drop-in {@link Button} (same {@code setOnAction}, {@code setDisable}...)
 * that adds, with no inline styling:
 * <ul>
 *   <li>an angled light sheen that sweeps across on hover (primary and play kinds),</li>
 *   <li>a 0.98 press scale,</li>
 *   <li>the shared look from {@code components.css} ({@code av-btn-primary|ghost|play}).</li>
 * </ul>
 * All motion goes through {@link Motion}, so Low-End Mode turns it off live.
 */
public class AvButton extends Button {

    public enum Kind { PRIMARY, GHOST, PLAY }

    private final Kind kind;

    public AvButton(String text) {
        this(text, Kind.GHOST);
    }

    public AvButton(String text, Kind kind) {
        super(text);
        this.kind = kind;
        getStyleClass().add("av-btn");
        getStyleClass().add(switch (kind) {
            case PRIMARY -> "av-btn-primary";
            case PLAY -> "av-btn-play";
            case GHOST -> "av-btn-ghost";
        });
        armedProperty().addListener((o, was, now) -> {
            double s = now ? 0.98 : 1.0;
            Motion.animate(scaleXProperty(), s, Motion.PRESS);
            Motion.animate(scaleYProperty(), s, Motion.PRESS);
        });
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SheenSkin(this, kind != Kind.GHOST);
    }

    /** ButtonSkin plus a clipped, mouse-transparent layer holding the sweeping sheen band. */
    private static final class SheenSkin extends ButtonSkin {
        private static final double BAND_W = 34;

        private final Pane layer = new Pane();
        private final Polygon band = new Polygon();
        // The sheen is clipped to the button's own rounded outline (ValShape radius, or a full pill
        // for buttons with no ValShape entry such as PLAY), so it never spills past the curved ends.
        private final Rectangle pillClip = new Rectangle();
        // Two little white "studs" near the bottom corners of the PLAY pill (from the reference art).
        private final Circle studL = new Circle(2.4);
        private final Circle studR = new Circle(2.8);
        private final boolean playKind;
        private final boolean enabledKind;
        private double lastW = -1, lastH = -1;

        SheenSkin(AvButton b, boolean enabledKind) {
            super(b);
            this.enabledKind = enabledKind;
            this.playKind = b.kind() == Kind.PLAY;
            if (playKind) {
                for (Circle c : new Circle[] {studL, studR}) {
                    c.getStyleClass().add("av-btn-play-stud");
                    c.setMouseTransparent(true);
                    c.setManaged(false);
                    getChildren().add(c);
                }
            }
            band.getStyleClass().add("av-btn-sheen");
            layer.getChildren().add(band);
            layer.setMouseTransparent(true);
            layer.setManaged(false);
            layer.setVisible(false);
            getChildren().add(layer);

            if (enabledKind) {
                b.hoverProperty().addListener((o, was, now) -> {
                    if (now && !b.isDisabled() && Motion.enabled()) sweep(b);
                    else if (!now) layer.setVisible(false);
                });
            }
        }

        private void sweep(AvButton b) {
            double h = b.getHeight();
            double skew = h * 0.5;
            layer.setVisible(true);
            band.setTranslateX(-BAND_W - skew);
            Motion.animate(band.translateXProperty(), b.getWidth() + skew, javafx.util.Duration.millis(320));
        }

        @Override
        protected void layoutChildren(double x, double y, double w, double h) {
            super.layoutChildren(x, y, w, h);
            if (!enabledKind) return;
            double bw = getSkinnable().getWidth(), bh = getSkinnable().getHeight();
            if (playKind) {
                // lip is 4px tall, so the visible body is bh-4
                studL.setCenterX(Math.max(18, bh * 0.36));  studL.setCenterY(bh - 4 - Math.max(9, bh * 0.20));
                studR.setCenterX(bw - Math.max(18, bh * 0.36)); studR.setCenterY(bh - 4 - Math.max(11, bh * 0.24));
            }
            layer.resizeRelocate(0, 0, bw, bh);
            if (bw == lastW && bh == lastH) return;
            lastW = bw;
            lastH = bh;
            double skew = bh * 0.5;
            band.getPoints().setAll(skew, 0.0, skew + BAND_W, 0.0, BAND_W, bh, 0.0, bh);
            Object cutProp = getSkinnable().getProperties().get("valshape.cut");
            Shape activeClip;
            if (cutProp instanceof Double d) {
                double arc = com.astrolauncher.ui.ValShape.radius(d, bw, bh) * 2;
                pillClip.setWidth(bw);
                pillClip.setHeight(bh);
                pillClip.setArcWidth(arc);
                pillClip.setArcHeight(arc);
                activeClip = pillClip;
            } else {
                // No cut registered: this is a plain rounded/pill button — arc = full height gives
                // a true pill (semicircle ends) rather than a sharp or barely-rounded rectangle.
                pillClip.setWidth(bw);
                pillClip.setHeight(bh);
                pillClip.setArcWidth(bh);
                pillClip.setArcHeight(bh);
                activeClip = pillClip;
            }
            layer.setClip(activeClip);
        }
    }
}
