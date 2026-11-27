package com.astrolauncher.social;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.store.AstroId;
import com.astrolauncher.ui.AppContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Facade for friends/party/chat, mirroring how {@code RankService} wraps {@code RankSync}: one
 * bound singleton, a background scheduler, listeners get told what changed on a background thread.
 *
 * <p>Friends are refreshed every {@link #FRIENDS_PERIOD_S}; chat is polled every
 * {@link #CHAT_PERIOD_S} using the message id the server last returned, so a poll only ever asks for
 * what's new. Both stop firing requests (without erroring at the UI) once the server answers 404/501
 * for either route — the social endpoints not being deployed yet behaves like "no friends feature",
 * not like a broken one — and retry occasionally in case that changes.
 */
public final class SocialService {

    private static final String TAG = "SocialService";
    private static final SocialService INSTANCE = new SocialService();
    public static SocialService get() { return INSTANCE; }

    private static final long FRIENDS_PERIOD_S = 20;
    private static final long CHAT_PERIOD_S = 3;
    private static final long NOT_SUPPORTED_RETRY_MS = 10 * 60_000L;

    private final Object lock = new Object();
    private final List<SocialListener> listeners = new CopyOnWriteArrayList<>();

    private SocialSync sync;
    private ScheduledExecutorService exec;
    private boolean bound;
    private boolean signedIn;

    private volatile List<Friend> friends = Collections.emptyList();
    private long lastChatId = -1;
    private long friendsNotBefore;
    private long chatNotBefore;

    private SocialService() { }

    /** Call once at startup, after {@code AstroId.get().bind(ctx)}. Safe to call repeatedly. */
    public void bind(AppContext ctx) {
        synchronized (lock) {
            if (bound) return;
            bound = true;
            try {
                sync = new SocialSync(AstroId.get().api());
                exec = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "astro-social-sync");
                    t.setDaemon(true);
                    return t;
                });
                exec.scheduleWithFixedDelay(this::friendsTick, 2, FRIENDS_PERIOD_S, TimeUnit.SECONDS);
                exec.scheduleWithFixedDelay(this::chatTick, 4, CHAT_PERIOD_S, TimeUnit.SECONDS);
                AstroId.get().addListener(this::onAccountChanged);
            } catch (Throwable t) {
                bound = false;
                AppLogger.get().error(TAG, "Social system failed to start; the launcher continues without it.", t);
                return;
            }
        }
        onAccountChanged();
    }

    public void shutdown() {
        synchronized (lock) { if (exec != null) exec.shutdownNow(); }
    }

    public void addListener(SocialListener l) { listeners.add(l); }
    public void removeListener(SocialListener l) { listeners.remove(l); }

    /** Best-known friends list right now; refreshed in the background, never blocks the caller. */
    public List<Friend> friendsSnapshot() { return friends; }

    // ------------------------------------------------------------------ account

    private void onAccountChanged() {
        boolean now = AstroId.get().isSignedIn();
        synchronized (lock) {
            if (now == signedIn) return;
            signedIn = now;
            if (!now) {
                friends = Collections.emptyList();
                lastChatId = -1;
                friendsNotBefore = 0;
                chatNotBefore = 0;
            }
        }
        if (!now) for (SocialListener l : listeners) safe(l::onSignedOut);
        else kick();
    }

    /** Nudges both polls to run right away instead of waiting out their period — called on sign-in. */
    private void kick() {
        ScheduledExecutorService e;
        synchronized (lock) { e = exec; }
        if (e != null) { e.execute(this::friendsTick); e.execute(this::chatTick); }
    }

    // ------------------------------------------------------------------ ticks

    private void friendsTick() {
        SocialSync s;
        synchronized (lock) {
            if (!signedIn || System.currentTimeMillis() < friendsNotBefore) return;
            s = sync;
        }
        if (s == null) return;
        SocialSync.Outcome<List<Friend>> o = s.friends();
        if (o.notSupported()) { synchronized (lock) { friendsNotBefore = System.currentTimeMillis() + NOT_SUPPORTED_RETRY_MS; } return; }
        if (!o.ok() || o.data() == null) return; // transient failure or empty answer: keep the last good list, try again next tick
        friends = o.data();
        List<Friend> snapshot = friends;
        for (SocialListener l : listeners) safe(() -> l.onFriendsChanged(snapshot));
    }

    private void chatTick() {
        SocialSync s;
        long after;
        synchronized (lock) {
            if (!signedIn || System.currentTimeMillis() < chatNotBefore) return;
            s = sync;
            after = lastChatId;
        }
        if (s == null) return;
        SocialSync.Outcome<SocialSync.ChatPoll> o = s.pollChat(after);
        if (o.notSupported()) { synchronized (lock) { chatNotBefore = System.currentTimeMillis() + NOT_SUPPORTED_RETRY_MS; } return; }
        if (!o.ok() || o.data() == null) return;
        synchronized (lock) { lastChatId = o.data().latest(); }
        List<ChatMessage> msgs = o.data().messages();
        if (!msgs.isEmpty()) for (SocialListener l : listeners) safe(() -> l.onChatMessages(msgs));
    }

    // ------------------------------------------------------------------ actions (called from the UI; run on the caller's own background thread)

    public SocialSync.Outcome<String> addFriend(String name) { return syncOrUnavailable(s -> s.addFriend(name)); }
    public SocialSync.Outcome<String> removeFriend(String name) { return syncOrUnavailable(s -> s.removeFriend(name)); }
    public SocialSync.Outcome<String> sendChat(String channel, String to, String body) { return syncOrUnavailable(s -> s.sendChat(channel, to, body)); }
    public SocialSync.Outcome<com.google.gson.JsonElement> party(String action, String name) { return syncOrUnavailable(s -> s.party(action, name)); }

    private <T> SocialSync.Outcome<T> syncOrUnavailable(java.util.function.Function<SocialSync, SocialSync.Outcome<T>> call) {
        SocialSync s;
        synchronized (lock) { s = sync; }
        if (s == null) return new SocialSync.Outcome<>(null, true, false, "Not signed in.");
        return call.apply(s);
    }

    private void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { AppLogger.get().warn(TAG, "listener failed: " + t); }
    }
}
