package com.astrolauncher.service;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.ui.AppContext;

import java.util.List;

/**
 * The launcher has exactly two kinds of sign-in, and "Playing as" picks between them:
 *
 * <ul>
 *   <li><b>Astro account</b> - the Astro ID identity (rank, credits, capes...). It plays under one name on any
 *       server that accepts offline-mode players. Stored as a single OFFLINE {@link Account} with the fixed id
 *       {@link #ASTRO_ACCOUNT_ID}, which {@code AstroProfileSync} creates / updates / removes.</li>
 *   <li><b>Microsoft account</b> - a real Minecraft account, for premium servers. Several saved sessions may exist;
 *       the "preferred" one is the one in use.</li>
 * </ul>
 *
 * <p>The selection itself is {@code settings.activeAccountId} (everything that launches the game already reads it),
 * so this class only decides what it should point at.
 */
public final class AccountChoice {

    private static final String TAG = "AccountChoice";

    /** Fixed id of the one account that represents the signed-in Astro ID. */
    public static final String ASTRO_ACCOUNT_ID = "astro-account";

    public enum Kind { ASTRO, MICROSOFT }

    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AccountChoice() {}

    /** Called whenever the selected account (or the set of accounts) changes through this class. */
    public static void addListener(Runnable r) { LISTENERS.add(r); }

    private static void fire() {
        for (Runnable r : LISTENERS) { try { r.run(); } catch (Exception ignored) { } }
    }

    public static boolean isAstro(Account a) {
        return a != null && a.type == Account.Type.OFFLINE && a.astroLinked;
    }

    /** The Astro account, or null when no Astro ID is signed in (or it has no player name yet). */
    public static Account astro(AppContext ctx) {
        for (Account a : ctx.accountRepository.load()) if (isAstro(a)) return a;
        return null;
    }

    /** The Microsoft account that "Microsoft" stands for: the selected one, else the last used, else the first saved. */
    public static Account preferredMicrosoft(AppContext ctx) {
        List<Account> all = ctx.accountRepository.load();
        String active = ctx.settings.activeAccountId;
        Account first = null;
        Account remembered = null;
        for (Account a : all) {
            if (a.type != Account.Type.MICROSOFT) continue;
            if (a.id.equals(active)) return a;
            if (a.id.equals(ctx.settings.lastMicrosoftAccountId)) remembered = a;
            if (first == null) first = a;
        }
        return remembered != null ? remembered : first;
    }

    /** Which of the two is currently selected, or null when nothing is. */
    public static Kind selectedKind(AppContext ctx) {
        String id = ctx.settings.activeAccountId;
        if (id == null) return null;
        for (Account a : ctx.accountRepository.load()) {
            if (id.equals(a.id)) return a.type == Account.Type.MICROSOFT ? Kind.MICROSOFT : Kind.ASTRO;
        }
        return null;
    }

    /** Selects an exact account (Astro, or one specific saved Microsoft session) and saves the choice. */
    public static void select(AppContext ctx, Account account) {
        if (account == null) return;
        ctx.settings.activeAccountId = account.id;
        if (account.type == Account.Type.MICROSOFT) ctx.settings.lastMicrosoftAccountId = account.id;
        ctx.settingsRepository.save(ctx.settings);
        fire();
    }

    /** Selects the Astro account or the preferred Microsoft account. Returns false when that one isn't signed in. */
    public static boolean select(AppContext ctx, Kind kind) {
        Account target = kind == Kind.ASTRO ? astro(ctx) : preferredMicrosoft(ctx);
        if (target == null) return false;
        select(ctx, target);
        return true;
    }

    /**
     * Keeps the selection sane: drops extra hand-made offline profiles (the Astro account replaces them), and
     * makes sure the selected account still exists. With only one of the two signed in, that one is used
     * automatically. Returns true when the accounts or the selection changed.
     */
    public static boolean ensureValid(AppContext ctx) {
        boolean changed = false;
        try {
            changed = ctx.accountRepository.modify(all ->
                    all.removeIf(a -> a.type == Account.Type.OFFLINE && !a.astroLinked));

            List<Account> all = ctx.accountRepository.load();
            Account astro = null;
            Account ms = null;
            for (Account a : all) {
                if (isAstro(a)) astro = a;
                else if (a.type == Account.Type.MICROSOFT && ms == null) ms = a;
            }
            String id = ctx.settings.activeAccountId;
            boolean valid = id != null && all.stream().anyMatch(a -> id.equals(a.id));
            if (valid) return changed;

            // Dangling or empty selection: use whatever is signed in (Microsoft first when both are).
            Account next = preferredMicrosoft(ctx);
            if (next == null) next = astro;
            String nextId = next == null ? null : next.id;
            if (!java.util.Objects.equals(id, nextId)) {
                ctx.settings.activeAccountId = nextId;
                if (next != null && next.type == Account.Type.MICROSOFT) ctx.settings.lastMicrosoftAccountId = next.id;
                ctx.settingsRepository.save(ctx.settings);
                changed = true;
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not update the selected account: " + e.getMessage());
        }
        if (changed) fire();
        return changed;
    }
}
