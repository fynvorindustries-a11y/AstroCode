package com.averolauncher.notify;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationCenterTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try { Platform.startup(up::countDown); } catch (IllegalStateException alreadyRunning) { up.countDown(); }
        assertTrue(up.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    private static void fx(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> { try { r.run(); } finally { done.countDown(); } });
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @BeforeEach
    void reset() throws Exception { fx(() -> NotificationCenter.get().clear()); }

    @Test
    void startsEmptyWithNoUnread() throws Exception {
        fx(() -> { assertEquals(0, NotificationCenter.get().items().size()); assertEquals(0, NotificationCenter.get().unreadProperty().get()); });
    }

    @Test
    void postAddsNewestFirstAndCountsUnread() throws Exception {
        fx(() -> {
            NotificationCenter c = NotificationCenter.get();
            c.post("A", "first"); c.post("B", "second");
            assertEquals("B", c.items().get(0).title());
            assertEquals(2, c.unreadProperty().get());
        });
    }

    @Test
    void duplicatePostReplacesInsteadOfStacking() throws Exception {
        fx(() -> {
            NotificationCenter c = NotificationCenter.get();
            c.post("Update available", "x"); c.post("Update available", "x");
            assertEquals(1, c.items().size());
            assertEquals(1, c.unreadProperty().get());
        });
    }

    @Test
    void markAllReadZeroesTheBadgeButKeepsItems() throws Exception {
        fx(() -> {
            NotificationCenter c = NotificationCenter.get();
            c.post("A", null); c.post("B", "b");
            c.markAllRead();
            assertEquals(0, c.unreadProperty().get());
            assertEquals(2, c.items().size());
        });
    }

    @Test
    void inboxIsBounded() throws Exception {
        fx(() -> {
            NotificationCenter c = NotificationCenter.get();
            for (int i = 0; i < 200; i++) c.post("n" + i, "b");
            assertEquals(50, c.items().size());
            assertEquals("n199", c.items().get(0).title());
        });
    }
}
