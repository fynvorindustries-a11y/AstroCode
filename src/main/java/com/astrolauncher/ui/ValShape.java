package com.astrolauncher.ui;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import java.util.Map;

/**
 * Gives panels, cards and buttons their silhouette. This used to cut two opposite corners at 45&deg;
 * (Valorant style); it now rounds them instead. The numbers in {@link #CUTS} are kept as "size
 * classes": 9 = call-to-action button (drawn as a full pill), anything else becomes a rounded
 * rectangle whose corner radius is {@code cut * 1.5}.
 *
 * <p>Applied by {@link ValText} as it walks the node tree, only to the classes below. Buttons that
 * use a background <i>image</i> (the Home hero badge) are skipped so the artwork isn't clipped.
 */
public final class ValShape {

    private ValShape() {}

    private static final String APPLIED = "valshape.applied";
    /** Style class -> size of the 45&deg; cut (top-left and bottom-right corners), in px. */
    private static final Map<String, Double> CUTS = Map.ofEntries(
            // filled call-to-action buttons
            Map.entry("primary-cta-button", 9.0), Map.entry("login-button", 9.0), Map.entry("hero-play-button", 9.0),
            Map.entry("hero-setup-button", 9.0), Map.entry("hero-choose-version-button", 9.0),
            Map.entry("play-button", 9.0), Map.entry("play-page-cta", 9.0), Map.entry("ms-hero-button", 9.0),
            // panels and cards (their hairline outline is a background layer, so the cut keeps it)
            Map.entry("dashboard-card", 14.0), Map.entry("settings-section", 12.0), Map.entry("filters-panel", 12.0),
            Map.entry("mod-card", 12.0), Map.entry("modpack-card", 12.0), Map.entry("partner-card", 12.0),
            Map.entry("recommended-card", 12.0), Map.entry("cape-card", 12.0), Map.entry("about-stat-card", 10.0),
            Map.entry("astro-auth-card", 16.0), Map.entry("ms-hero", 16.0), Map.entry("status-chip", 8.0),
            // chrome: nav tabs, search box, account chip, hero tag
            Map.entry("nav-button", 12.0), Map.entry("search-field", 10.0), Map.entry("account-chip", 10.0),
            Map.entry("hero-eyebrow", 6.0),
            // design-system components (kit/)
            // av-btn-play is intentionally absent: that button is a fully rounded pill (see
            // components.css), not Valorant's angled cut — AvButton's sheen clip already falls
            // back to a plain rounded rectangle for any class with no entry here.
            Map.entry("av-btn-primary", 9.0), Map.entry("av-btn-ghost", 9.0),
            Map.entry("av-card", 12.0), Map.entry("av-update-bar", 12.0), Map.entry("av-cape-preview", 14.0), Map.entry("home-logo-tile", 10.0)
    );

    /** Classes whose hairline is a CSS/inline *border* (not a layer) must keep their rectangle. */
    private static boolean hasBorderStyle(Region r) {
        String inline = r.getStyle();
        return inline != null && inline.contains("border-color");
    }

    static void maybe(Region r) {
        if (r.getProperties().containsKey(APPLIED)) return;
        double cut = 0;
        for (String c : r.getStyleClass()) {
            Double v = CUTS.get(c);
            if (v != null) { cut = Math.max(cut, v); }
        }
        if (cut <= 0) return;
        String inline = r.getStyle();
        if (inline != null && inline.contains("background-image")) return;
        if (hasBorderStyle(r)) return;
        final double cutPx = cut;
        r.getProperties().put("valshape.cut", cutPx);

        r.getProperties().put(APPLIED, Boolean.TRUE);
        r.setScaleShape(true);   // layered backgrounds inset the shape, so the outline layer stays 1px
        r.setCenterShape(false);
        r.setPickOnBounds(true);
        update(r);
        r.widthProperty().addListener((o, a, b) -> update(r));
        r.heightProperty().addListener((o, a, b) -> update(r));
    }

    private static void update(Region r) {
        double w = r.getWidth(), h = r.getHeight();
        if (w <= 0 || h <= 0) return;
        Object cutProp = r.getProperties().get("valshape.cut");
        double cut = cutProp instanceof Double d ? d : 9.0;
        Rectangle rr = new Rectangle(w, h);
        double arc = radius(cut, w, h) * 2;
        rr.setArcWidth(arc);
        rr.setArcHeight(arc);
        r.setShape(rr);
    }

    /** Corner radius for a size class: CTA buttons (cut 9) are pills, everything else is softly rounded. */
    public static double radius(double cut, double w, double h) {
        double max = Math.min(w, h) / 2;
        return cut == 9.0 ? max : Math.min(cut * 1.5, max);
    }
}
