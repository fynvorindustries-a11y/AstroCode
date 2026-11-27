package com.astrolauncher.ui;

public enum NavPage {
    HOME("Home", "\u2302"),
    PLAY("Play", "\u25B6"),
    MODS("Mods", "\u2699"),
    SERVERS("Servers", "\u2261"),
    STORE("Store", "\u2605"),
    RANKS("Ranks", "\u2666"),
    LABS("Astro Labs", "\u2726"),
    ACCOUNTS("Accounts", "\u263A"),
    SETTINGS("Settings", "\u2699"),
    ABOUT("About", "\u2139");

    private final String label;
    private final String icon;

    NavPage(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    /** The only pages that sit in the left rail; every other page is reached from the top bar. */
    public static boolean inSidebar(NavPage p) {
        return p == HOME || p == PLAY || p == STORE || p == SETTINGS;
    }

    /** Pages with their own button in the top bar, in order. Astro Labs is the lettered (text) one. */
    public static NavPage[] topBar() {
        return new NavPage[] {LABS, RANKS, MODS, ABOUT};
    }
}
