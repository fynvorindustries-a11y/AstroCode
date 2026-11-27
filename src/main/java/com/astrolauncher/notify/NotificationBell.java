package com.astrolauncher.notify;

import java.time.Duration;
import java.time.Instant;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;

/** Bell button with an unread badge (hidden at 0) and a dropdown list backed by {@link NotificationCenter}. */
public final class NotificationBell extends StackPane {

    private final NotificationCenter center = NotificationCenter.get();
    private final Label badge = new Label();
    private final VBox list = new VBox(8);
    private final Popup popup = new Popup();

    public NotificationBell() {
        getStyleClass().add("av-bell");

        SVGPath bell = new SVGPath();
        bell.setContent("M6 17 V11 A6 6 0 0 1 18 11 V17 L20 19 H4 Z M10 21 A2 2 0 0 0 14 21");
        bell.getStyleClass().add("av-bell-icon");

        Button btn = new Button();
        btn.setGraphic(bell);
        btn.getStyleClass().add("av-bell-btn");
        btn.setAccessibleText("Notifications");
        btn.setOnAction(e -> toggle(btn));

        badge.getStyleClass().add("av-bell-badge");
        badge.setMouseTransparent(true);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(-2, -2, 0, 0));
        getChildren().addAll(btn, badge);

        popup.setAutoHide(true);
        popup.getContent().add(buildPanel());

        center.unreadProperty().addListener((o, a, b) -> refreshBadge());
        center.items().addListener((ListChangeListener<NotificationCenter.Item>) c -> rebuildList());
        refreshBadge();
        rebuildList();
    }

    private Region buildPanel() {
        Label title = new Label("Notifications");
        title.getStyleClass().add("av-bell-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button clear = new Button("Clear all");
        clear.getStyleClass().add("av-bell-clear");
        clear.setOnAction(e -> center.clear());
        HBox head = new HBox(8, title, sp, clear);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(10, head, list);
        panel.getStyleClass().add("av-bell-panel");
        panel.setPrefWidth(340);
        panel.setPadding(new Insets(14));
        return panel;
    }

    private void toggle(Region anchor) {
        if (popup.isShowing()) { popup.hide(); return; }
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMaxX() - 340, b.getMaxY() + 8);
        center.markAllRead();   // opening the list counts as reading it
    }

    private void refreshBadge() {
        int n = center.unreadProperty().get();
        badge.setText(n > 9 ? "9+" : String.valueOf(n));
        badge.setVisible(n > 0);
        badge.setManaged(n > 0);
    }

    private void rebuildList() {
        list.getChildren().clear();
        if (center.items().isEmpty()) {
            Label empty = new Label("You're all caught up.");
            empty.getStyleClass().add("av-bell-empty");
            list.getChildren().add(empty);
            return;
        }
        for (var i : center.items()) {
            Label t = new Label(i.title());
            t.getStyleClass().add("av-bell-item-title");
            t.setWrapText(true);
            Label body = new Label(i.body() == null ? "" : i.body());
            body.getStyleClass().add("av-bell-item-body");
            body.setWrapText(true);
            body.setVisible(i.body() != null && !i.body().isBlank());
            body.setManaged(body.isVisible());
            Label when = new Label(ago(i.at()));
            when.getStyleClass().add("av-bell-item-time");
            VBox item = new VBox(2, t, body, when);
            item.getStyleClass().add("av-bell-item");
            list.getChildren().add(item);
        }
    }

    private static String ago(Instant at) {
        long m = Duration.between(at, Instant.now()).toMinutes();
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        return (m / 60) + " h ago";
    }
}
