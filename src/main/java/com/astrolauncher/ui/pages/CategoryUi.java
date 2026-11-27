package com.astrolauncher.ui.pages;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.Consumer;

/** Shared bits for "category chips above a short scrolling grid" (Store capes, Store backgrounds, Labs picker). */
final class CategoryUi {

    private CategoryUi() {}

    /** One chip: what it says, and the key handed back when it is picked (null = "All"). */
    record Chip(String label, Object key) {}

    /**
     * Fills {@code row} with chips (replacing what was there). Hidden entirely when there is only one
     * chip to show. {@code onPick} receives the chip's key when a different chip is chosen.
     */
    static void fill(FlowPane row, List<Chip> chips, Object selectedKey, Consumer<Object> onPick) {
        row.getChildren().clear();
        boolean show = chips.size() > 2; // "All" plus at least two real groups
        row.setVisible(show);
        row.setManaged(show);
        if (!show) return;
        ToggleGroup group = new ToggleGroup();
        for (Chip c : chips) {
            ToggleButton b = new ToggleButton(c.label());
            b.setToggleGroup(group);
            b.getStyleClass().add("store-cat-chip");
            b.setSelected(java.util.Objects.equals(c.key(), selectedKey));
            b.setAccessibleText("Show " + c.label());
            b.setOnAction(e -> {
                if (java.util.Objects.equals(c.key(), selectedKey)) { b.setSelected(true); return; }
                onPick.accept(c.key());
            });
            row.getChildren().add(b);
        }
        // Never leave the group with nothing selected (clicking the active chip again).
        group.selectedToggleProperty().addListener((o, was, now) -> { if (now == null && was != null) was.setSelected(true); });
    }

    /**
     * A scroll area as tall as {@code content} up to {@code maxHeight}, then it scrolls. At the top or bottom
     * of the list the wheel carries on scrolling the page, so the list never traps the mouse.
     */
    static ScrollPane shortScroll(Region content, double maxHeight) {
        ScrollPane sp = new ScrollPane(content);
        sp.getStyleClass().add("store-theme-scroll");
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setMinHeight(0);
        sp.prefViewportHeightProperty().bind(Bindings.min(content.heightProperty().add(10), maxHeight));

        sp.addEventFilter(ScrollEvent.SCROLL, e -> {
            double dy = e.getDeltaY();
            if (dy == 0) return;
            boolean fits = content.getHeight() <= sp.getViewportBounds().getHeight() + 1;
            boolean atTop = dy > 0 && sp.getVvalue() <= 0.0001;
            boolean atBottom = dy < 0 && sp.getVvalue() >= 0.9999;
            if (!(fits || atTop || atBottom)) return;
            Node n = sp.getParent();
            while (n != null && !(n instanceof ScrollPane)) n = n.getParent();
            if (n instanceof ScrollPane outer && outer.getContent() != null) {
                double range = outer.getContent().getBoundsInLocal().getHeight() - outer.getViewportBounds().getHeight();
                if (range > 0) outer.setVvalue(Math.max(0, Math.min(1, outer.getVvalue() - dy / range)));
                e.consume();
            }
        });
        return sp;
    }
}
