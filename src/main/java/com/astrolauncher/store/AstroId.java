package com.astrolauncher.store;

import com.astrolauncher.auth.AccountSessions;
import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.store.StoreApi.AstroSession;
import com.astrolauncher.ui.AppContext;
import com.astrolauncher.util.CredentialStore;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide Astro account state: one {@link StoreApi}, the signed-in session, and listeners so
 * the Accounts page and the Store page stay in sync.
 *
 * <p>Only the long-lived <b>refresh token</b> is persisted, encrypted in the launcher's credential
 * store — never the password, never the access token. On startup the refresh token is exchanged for
 * a fresh access token, so an expired access token never forces the player to type their password
 * again.
 *
 * <p><b>You stay signed in until you sign out.</b> The session is only ever cleared when the player
 * signs out, or when the server explicitly rejects the refresh token (revoked / expired). A server
 * that is asleep, unreachable or erroring never signs anyone out — the saved session is simply
 * retried in the background.
 */
public final class AstroId implements StoreApi.SessionStore {

    private static final String TAG = "AstroId";
    private static final String KEY = "astro_session"; // refreshToken|userId|email|username|sessionId (no newlines/pipes in fields)
    /** Pre-rename key name. The stored value lives inside the same credentials.dat blob (not a
     *  separate file), so renaming KEY alone would silently orphan every existing player's saved
     *  session the moment they updated — read it once as a fallback and migrate it forward. */
    private static final String LEGACY_KEY = "avero_session";
    private static final AstroId INSTANCE = new AstroId();

    public static AstroId get() { return INSTANCE; }

    private final StoreApi api = new StoreApi();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> linkAttempted = ConcurrentHashMap.newKeySet();
    private volatile AppContext boundCtx;
    private volatile String sessionProblem;
    private AstroSession session;
    /** False when the last attempt to save the session to disk failed, so memory is newer than disk. */
    private boolean diskInSync = true;

    /**
     * Both the Astro session restore and the Microsoft keep-alive rotate (and must persist) a refresh
     * token on EVERY startup — the server invalidates the old one as soon as it issues the new one. If
     * the launcher closes before that write reaches disk, the next launch retries the now-stale old
     * token and gets rejected, which looks exactly like an unexplained sign-out. These two futures let
     * {@link #awaitStartupWork} give that in-flight work a short, bounded window to finish before the
     * app actually exits — see {@code AstroLauncherApplication.stop()}.
     */
    private final CompletableFuture<Void> astroRestoreFirstAttempt = new CompletableFuture<>();
    private volatile CompletableFuture<Void> msKeepAliveDone = CompletableFuture.completedFuture(null);

    private AstroId() {
        api.setSessionStore(this);
        restore();
    }

    public StoreApi api() { return api; }

    /** Gives Astro ID access to the launcher's accounts so it can keep the Astro account in step. Call once at startup. */
    public void bind(AppContext ctx) {
        this.boundCtx = ctx;
        if (isSignedIn()) syncProfiles();
        keepMicrosoftAlive(ctx);
    }

    /**
     * Makes the Astro ID playable: pulls its player name from the server and keeps the launcher's single Astro
     * account in step with it (see {@link AstroProfileSync}). A brand-new Astro ID has no name yet, so its
     * own name is linked first (when it is a valid player name that Mojang doesn't own; if the server refuses,
     * the player can still link another name, or sign in with Microsoft).
     */
    public void syncProfiles() {
        final AppContext c = boundCtx;
        if (c == null || !isSignedIn() || api.isDemo()) return;
        api.me().whenComplete((user, err) -> {
            // Signed out while this request was in flight: do not bring the names back.
            if (!isSignedIn()) return;
            if (err != null || user == null) {
                AppLogger.get().info(TAG, "Could not read the linked names to sync: " + (err == null ? "no data" : rootMessage(err)));
                return;
            }
            if (!AstroProfileSync.hasPlayableName(user.profiles)) { linkDefaultName(c, user); return; }
            applyProfiles(c, user.profiles);
        });
    }

    private void applyProfiles(AppContext c, List<StoreApi.LinkedProfile> profiles) {
        if (!isSignedIn()) return;   // a late reply after sign-out must not re-create the mirrored accounts
        if (AstroProfileSync.apply(c, profiles)) fire();
    }

    private void linkDefaultName(AppContext c, StoreApi.AstroUser user) {
        String name = null;
        for (String cand : new String[]{ user.minecraftUsername, user.username,
                session() != null ? session().username : null }) {
            if (cand != null && cand.trim().matches("\\w{3,16}")) { name = cand.trim(); break; }
        }
        if (name == null) { applyProfiles(c, user.profiles == null ? List.of() : user.profiles); return; }
        final String chosen = name;
        // Once per sign-in: a name the server refuses (e.g. it exists at Mojang) must not be retried in a loop.
        if (!linkAttempted.add("default-name:" + (user.id == null ? "" : user.id) + ":" + chosen.toLowerCase())) {
            applyProfiles(c, user.profiles == null ? List.of() : user.profiles);
            return;
        }
        api.linkOffline(chosen).whenComplete((list, ex) -> {
            if (!isSignedIn()) return;
            if (ex != null) {
                AppLogger.get().info(TAG, "Default name \u201c" + chosen + "\u201d was not linked: " + rootMessage(ex));
                applyProfiles(c, user.profiles == null ? List.of() : user.profiles);
                return;
            }
            AppLogger.get().info(TAG, "Linked \u201c" + chosen + "\u201d to the new Astro ID.");
            applyProfiles(c, list);
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() != null ? r.getMessage() : r.getClass().getSimpleName();
    }

    /**
     * Refreshes the active Microsoft account's Minecraft token in the background at startup (and saves
     * the rotated tokens). This keeps the sign-in alive without the player doing anything, and means
     * pressing Play never has to wait on a refresh. A failure here is only logged: the saved account
     * stays signed in and is retried at launch.
     */
    private void keepMicrosoftAlive(AppContext c) {
        try {
            String activeId = c.settings.activeAccountId;
            if (activeId == null) return;
            Account active = c.accountRepository.load().stream()
                    .filter(a -> activeId.equals(a.id)).findFirst().orElse(null);
            if (active == null || active.type != Account.Type.MICROSOFT
                    || active.msRefreshToken == null || active.isMinecraftTokenValid()) return;
            CompletableFuture<Void> done = new CompletableFuture<>();
            msKeepAliveDone = done; // tracked so shutdown can wait for the rotated token to be saved
            AccountSessions.refreshAndSave(c.microsoftAuthService, active).whenComplete((fresh, ex) -> {
                try {
                    if (ex != null) {
                        Throwable r = ex; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                        AppLogger.get().info(TAG, "Microsoft session for " + active.username
                                + " not refreshed at startup (kept; will retry at launch): " + r.getMessage());
                    } else {
                        AppLogger.get().info(TAG, "Microsoft session for " + active.username + " refreshed at startup.");
                        fire();
                    }
                } finally {
                    done.complete(null);
                }
            });
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Microsoft keep-alive skipped: " + e.getMessage());
        }
    }

    /** Loads the persisted refresh token (if any) and refreshes it into a live access token. */
    private void restore() {
        try {
            String raw = CredentialStore.read(KEY);
            boolean fromLegacyKey = false;
            if (raw == null) {
                raw = CredentialStore.read(LEGACY_KEY);
                fromLegacyKey = raw != null;
            }
            if (raw == null) return;
            String[] p = raw.split("\\|", 5);
            if (p.length < 1 || p[0].isBlank()) {
                if (fromLegacyKey) CredentialStore.delete(LEGACY_KEY);
                return;
            }
            if (api.isDemo() || "demo".equals(p[0])) { CredentialStore.delete(KEY); CredentialStore.delete(LEGACY_KEY); return; } // fake session left by the old demo mode
            if (fromLegacyKey) {
                // Carry the session over to the new key name, then drop the old one, so this is a
                // one-time migration rather than a fallback read on every single launch.
                if (CredentialStore.write(KEY, raw)) CredentialStore.delete(LEGACY_KEY);
                AppLogger.get().info(TAG, "Migrated saved session from AveroLauncher to ASTRO naming.");
            }
            AstroSession s = new AstroSession();
            s.refreshToken = p[0];
            if (p.length > 1) s.userId = p[1];
            if (p.length > 2) s.email = p[2];
            if (p.length > 3) s.username = p[3];
            if (p.length > 4) s.sessionId = p[4];
            session = s; // access token is null; the first call (or restoreAsync) refreshes it
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not restore Astro session: " + e.getMessage());
        }
        // Exchange the refresh token for an access token in the background so the UI is ready fast.
        if (session != null && !api.isDemo()) {
            Thread t = new Thread(this::refreshSavedSession, "astro-session-restore");
            t.setDaemon(true);
            t.start();
        } else {
            astroRestoreFirstAttempt.complete(null); // nothing to refresh, so nothing for shutdown to wait on
        }
    }

    /**
     * Turns the saved refresh token into a live access token. Free hosts can take a minute to wake and
     * the player may be offline, so transient failures are retried with back-off and NEVER sign the
     * player out. Only an explicit rejection of the refresh token ends the session.
     */
    private void refreshSavedSession() {
        long[] delaysMs = {0, 8_000, 30_000, 90_000, 300_000, 900_000};
        boolean firstIteration = true;
        for (long delay : delaysMs) {
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    markAstroRestoreDone();
                    return;
                }
            }
            if (current() == null) { markAstroRestoreDone(); return; } // the player signed out in the meantime
            try {
                api.refreshBlocking();
                fire();
                syncProfiles();
                markAstroRestoreDone();
                return;
            } catch (StoreApi.StoreException se) {
                if (se.isSessionRejected()) {
                    // The server said no. Tell the player why instead of quietly dropping them on the
                    // sign-in form — a disabled account looks exactly like a normal sign-out otherwise.
                    sessionProblem = "ACCOUNT_DISABLED".equals(se.code)
                            ? "Your Astro account was disabled by an administrator, so you have been signed out."
                            : "The Astro server no longer accepts your saved session, so you have been signed out.";
                    AppLogger.get().info(TAG, "The server rejected the saved session (" + se.code + "); signed out.");
                    clear();
                    fire();
                    markAstroRestoreDone();
                    return;
                }
                AppLogger.get().info(TAG, "Astro server gave no clear verdict (" + se.code + "/" + se.status
                        + "); keeping the saved sign-in and retrying.");
            } catch (Exception e) {
                // Anything unexpected (bad JSON, I/O) says nothing about the session — keep it.
                AppLogger.get().info(TAG, "Session refresh hit a snag; keeping the saved sign-in: " + e.getMessage());
            }
            // The very first attempt (delay==0) fires almost immediately on startup, which is exactly
            // the one that can race a quick app close — mark it done here so shutdown doesn't wait
            // through the whole multi-minute backoff sequence for later retries.
            if (firstIteration) { markAstroRestoreDone(); firstIteration = false; }
        }
        AppLogger.get().info(TAG, "Astro server still unreachable; staying signed in and refreshing on next use.");
    }

    private void markAstroRestoreDone() {
        if (!astroRestoreFirstAttempt.isDone()) astroRestoreFirstAttempt.complete(null);
    }

    /**
     * Gives the in-flight startup token refresh(es) — Astro session restore and Microsoft keep-alive —
     * a short, bounded window to finish persisting before the app exits. Called once from
     * {@code AstroLauncherApplication.stop()}. Never blocks longer than {@code timeoutMs}: if the
     * server is slow or offline it's better to exit on time than to hang the close, and the saved
     * (old) token is simply retried again on the next launch.
     */
    public void awaitStartupWork(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // 1) Let a rotation that is already in flight finish saving its new token, and forbid new ones:
        //    a request we send now could be answered (old token invalidated) after we are gone.
        api.quiesce(timeoutMs);
        try {
            long left = Math.max(1, deadline - System.currentTimeMillis());
            CompletableFuture.allOf(astroRestoreFirstAttempt, msKeepAliveDone)
                    .get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Timed out or interrupted — proceed with shutdown regardless.
        }
        // 2) If a save failed earlier, make one last attempt so the newest token isn't lost.
        flushSession();
    }

    /** Re-writes the current session if an earlier save failed (the in-memory token is newer than disk). */
    private synchronized void flushSession() {
        if (!diskInSync && session != null && session.hasRefresh()) persist(session);
    }

    // ---------------------------------------------------------- SessionStore

    @Override
    public synchronized AstroSession current() { return session; }

    @Override
    public synchronized void persist(AstroSession s) {
        this.session = s;
        if (s == null || !s.hasRefresh()) { CredentialStore.delete(KEY); diskInSync = true; return; }
        String value = String.join("|",
                nz(s.refreshToken), nz(s.userId), nz(s.email), nz(s.username), nz(s.sessionId));
        // Refresh tokens are single-use: if this write is lost, the next launch presents a token the
        // server already retired and the player is signed out. Retry instead of failing silently.
        boolean ok = false;
        for (int attempt = 0; attempt < 4 && !ok; attempt++) {
            ok = CredentialStore.write(KEY, value);
            if (!ok) {
                try { Thread.sleep(150L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        diskInSync = ok;
        if (!ok) AppLogger.get().warn(TAG, "Could not save the rotated Astro session to disk; will retry before exit.");
    }

    /**
     * Another launcher process may have rotated the refresh token since we loaded ours (two windows,
     * or an updater relaunch overlapping the old process). The saved copy is then the live one and
     * ours is already retired. Only trusted when our own last save succeeded (else disk is the older one).
     */
    @Override
    public synchronized void reloadFromDisk() {
        if (session == null || !diskInSync) return;
        try {
            String raw = CredentialStore.read(KEY);
            if (raw == null) return;
            String[] p = raw.split("\\|", 5);
            if (p.length < 1 || p[0].isBlank() || p[0].equals(session.refreshToken)) return;
            session.refreshToken = p[0];
            session.accessToken = null;       // the access token belonged to the retired refresh token
            session.accessExpiresAtMs = 0;
            if (p.length > 1 && !p[1].isBlank()) session.userId = p[1];
            if (p.length > 4 && !p[4].isBlank()) session.sessionId = p[4];
            AppLogger.get().info(TAG, "Adopted a newer saved session written by another launcher process.");
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not re-read the saved session: " + e.getMessage());
        }
    }

    @Override
    public synchronized void clear() {
        this.session = null;
        linkAttempted.clear();
        CredentialStore.delete(KEY);
        // The Astro account goes with the Astro ID; saved Microsoft accounts stay.
        try { AstroProfileSync.removeAll(boundCtx); } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not remove the Astro account: " + e.getMessage());
        }
    }

    private static String nz(String s) { return s == null ? "" : s.replace("|", "").replace("\n", ""); }

    // -------------------------------------------------------------- lifecycle

    /** The current session, or null when signed out. */
    public synchronized AstroSession session() {
        if (session != null && !session.hasRefresh()) { session = null; }
        return session;
    }

    public boolean isSignedIn() { return session() != null; }

    /** Called after a successful login/register: adopt + persist the session. */
    public synchronized void signedIn(AstroSession s) {
        sessionProblem = null;
        persist(s);
        fire();
        syncProfiles();
    }

    /** Revokes the server-side session, clears local tokens, and notifies listeners. */
    public void signOut() {
        sessionProblem = null;
        // Revoke the session on the server, but never make the player wait on the network for it: after at most
        // two seconds the launcher signs out locally either way (the revoke request carries on in the background).
        api.logout()
                .completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((v, e) -> { clear(); fire(); });
    }

    /**
     * Why the saved sign-in ended by itself, or null. The UI shows this once — a player who finds the
     * login form again without explanation reasonably assumes the launcher lost their password.
     */
    public String takeSessionProblem() {
        String p = sessionProblem;
        sessionProblem = null;
        return p;
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void fire() { for (Runnable r : listeners) { try { r.run(); } catch (Exception ignored) { } } }

    /**
     * Returns {@code account} with a valid Minecraft token, refreshing through Microsoft and
     * persisting the new tokens if needed. Offline accounts pass straight through.
     */
    public CompletableFuture<Account> freshAccount(AppContext ctx, Account account) {
        return AccountSessions.refreshAndSave(ctx == null ? null : ctx.microsoftAuthService, account);
    }
}