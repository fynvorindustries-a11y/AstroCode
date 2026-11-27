package com.astrolauncher.notify;

import java.time.Instant;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * In-app notification inbox shown by the top-bar bell. Session-scoped (not persisted): it holds things the
 * launcher itself raised (update available, ...). Text may include release metadata from GitHub, so it is only ever
 * shown as plain text in a Label (never as HTML/markup). Safe to call {@link #post} from any thread.
 */
public final class NotificationCenter {

    public record Item(long id, String title, String body, Instant at, boolean read) { }

    private static final int MAX = 50;
    private static final NotificationCenter INSTANCE = new NotificationCenter();

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper unread = new ReadOnlyIntegerWrapper(0);
    private long nextId = 1;

    private NotificationCenter() { }

    public static NotificationCenter get() { return INSTANCE; }

    public ObservableList<Item> items() { return FXCollections.unmodifiableObservableList(items); }

    public ReadOnlyIntegerProperty unreadProperty() { return unread.getReadOnlyProperty(); }

    /** Adds a notification (newest first). A second post with the same title+body replaces the old one. */
    public void post(String title, String body) {
        runFx(() -> {
            items.removeIf(i -> i.title().equals(title) && java.util.Objects.equals(i.body(), body));
            items.add(0, new Item(nextId++, title, body, Instant.now(), false));
            while (items.size() > MAX) items.remove(items.size() - 1);
            recount();
        });
    }

    public void markAllRead() {
        runFx(() -> {
            List<Item> copy = items.stream().map(i -> new Item(i.id(), i.title(), i.body(), i.at(), true)).toList();
            items.setAll(copy);
            recount();
        });
    }

    public void clear() { runFx(() -> { items.clear(); recount(); }); }

    private void recount() { unread.set((int) items.stream().filter(i -> !i.read()).count()); }

    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }
}
