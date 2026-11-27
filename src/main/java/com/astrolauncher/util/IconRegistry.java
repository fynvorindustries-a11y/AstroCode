package com.astrolauncher.util;

import com.astrolauncher.ui.NavPage;
import javafx.scene.shape.SVGPath;

/**
 * Small, consistent line-icon set (24x24 grid, stroke-based) for the sidebar
 * nav and a couple of chrome spots (search field). Replaces the old mix of
 * unicode glyphs (\u2318 for Versions, \u263A for Accounts, \u2699 reused for
 * both Mods and Settings) with one deliberate, on-brand family: the same
 * stroke weight and corner language everywhere, and a couple of shapes —
 * the four-square "blocks" glyph for Mods in particular — chosen to nod at
 * Minecraft itself rather than being generic dashboard icons.
 *
 * Fill/stroke color is left to CSS ({@code .nav-icon} and its
 * {@code :selected}/hover descendant selectors in dark-theme.css) so the
 * same path recolors with the button state instead of being baked in here.
 */
public final class IconRegistry {

    private IconRegistry() {}

    public static SVGPath icon(NavPage page) {
        SVGPath svg = build(pathFor(page));
        if (page == NavPage.PLAY) {
            // The play triangle reads more clearly solid than as a thin outline at
            // sidebar size — every other glyph in the set stays a stroked line icon.
            svg.getStyleClass().add("nav-icon-fill");
        }
        return svg;
    }

    /** Door-with-arrow "log out" glyph for the sidebar. */
    public static SVGPath logout() {
        return build("M9 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 H9 M16 17 L21 12 L16 7 M21 12 H9");
    }

    /** Small magnifying-glass glyph used inside the top-bar search field. */
    public static SVGPath search() {
        return build("M11 4 A7 7 0 1 0 11.01 4 Z M16.2 16.2 L21 21");
    }

    private static SVGPath build(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("nav-icon");
        return svg;
    }

    private static String pathFor(NavPage page) {
        switch (page) {
            case HOME:
                // Roof + walls + a doorway notch open at the bottom.
                return "M4 12 L12 5 L20 12 M6.5 10.3 V19 H17.5 V10.3 M10 19 V14 H14 V19";
            case PLAY:
                return "M8 5 L19 12 L8 19 Z";
            case MODS:
                // Four blocks in a 2x2 grid — a nod at Minecraft's own building block,
                // and visually distinct from the Settings glyph below (previously both
                // reused the same gear).
                return "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z";
            case SERVERS:
                return "M4 5 H20 V8 H4 Z M4 11 H20 V14 H4 Z M4 17 H20 V20 H4 Z";
            case STORE:
                // Shopping bag: body + handle.
                return "M5.5 8 H18.5 L19.5 20 H4.5 Z M9 8 V6.5 A3 3 0 0 1 15 6.5 V8";
            case RANKS:
                // Hexagonal emblem with a diamond inside: the rank badge silhouette.
                return "M12 3 L19.5 7.5 V16.5 L12 21 L4.5 16.5 V7.5 Z M12 8 L15.2 12 L12 16 L8.8 12 Z";
            case LABS:
                // Four-point sparkle for the special-features page.
                return "M12 3 L14.2 9.8 L21 12 L14.2 14.2 L12 21 L9.8 14.2 L3 12 L9.8 9.8 Z";
            case ACCOUNTS:
                // A person: round head over rounded shoulders. The head is two half-circle arcs (a zero-length
                // arc is unreliable across renderers) and the shoulders are an open curve with round caps.
                return "M12 4.6 A3.6 3.6 0 1 1 12 11.8 A3.6 3.6 0 1 1 12 4.6 Z "
                        + "M4.8 20 C4.8 15.9 7.9 13.9 12 13.9 C16.1 13.9 19.2 15.9 19.2 20";
            case SETTINGS:
                // A cog: eight evenly spaced teeth around a round hub. (Mods keeps the block grid above.)
                return "M 10.36 4.89 L 10.58 2.51 L 13.42 2.51 L 13.64 4.89 A 7.3 7.3 0 0 1 15.87 5.81 L 17.71 4.28 L 19.72 6.29 L 18.19 8.13 A 7.3 7.3 0 0 1 19.11 10.36 L 21.49 10.58 L 21.49 13.42 L 19.11 13.64 A 7.3 7.3 0 0 1 18.19 15.87 L 19.72 17.71 L 17.71 19.72 L 15.87 18.19 A 7.3 7.3 0 0 1 13.64 19.11 L 13.42 21.49 L 10.58 21.49 L 10.36 19.11 A 7.3 7.3 0 0 1 8.13 18.19 L 6.29 19.72 L 4.28 17.71 L 5.81 15.87 A 7.3 7.3 0 0 1 4.89 13.64 L 2.51 13.42 L 2.51 10.58 L 4.89 10.36 A 7.3 7.3 0 0 1 5.81 8.13 L 4.28 6.29 L 6.29 4.28 L 8.13 5.81 A 7.3 7.3 0 0 1 10.36 4.89 Z M 15.2 12 A 3.2 3.2 0 1 1 8.8 12 A 3.2 3.2 0 1 1 15.2 12 Z";
            case ABOUT:
                return "M12 21 A9 9 0 1 1 12.01 21 Z M12 11 V16.2 M12 7.6 L12 7.61";
            default:
                return "M4 4 H20 V20 H4 Z";
        }
    }
}
