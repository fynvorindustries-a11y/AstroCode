package com.astrolauncher.store;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.service.AccountChoice;
import com.astrolauncher.ui.AppContext;

import java.util.List;
import java.util.Locale;

/**
 * Keeps the launcher's single <b>Astro account</b> in step with the signed-in Astro ID.
 *
 * <p>An Astro ID lives on the Astro server, but the game is started from {@code accounts.json}. The Astro ID is
 * therefore represented there by exactly one {@link Account.Type#OFFLINE} account (fixed id
 * {@link AccountChoice#ASTRO_ACCOUNT_ID}, flagged {@link Account#astroLinked}) that carries the <b>server's UUID</b>
 * for the player name, so the cape agent ({@code self=<uuid>}) and the server agree on who the player is.
 *
 * <p>It is one account with one name: if the server lists several names, the one matching the Astro username
 * is used (else the first). Names are never mirrored as separate accounts, and Microsoft accounts are never
 * linked to or mirrored from the Astro ID. Signing out of the Astro ID removes this account and nothing else.
 */
public final class AstroProfileSync {

    private static final String TAG = "AstroProfileSync";

    private AstroProfileSync() {}

    /** True when {@code profiles} holds a name that can be played as (an unverified, offline-mode one). */
    public static boolean hasPlayableName(List<StoreApi.LinkedProfile> profiles) {
        return pick(profiles, null) != null;
    }

    /** Creates / updates the single Astro account from {@code profiles}. Returns true if anything changed. */
    public static boolean apply(AppContext ctx, List<StoreApi.LinkedProfile> profiles) {
        if (ctx == null || profiles == null) return false;

        StoreApi.AstroSession session = AstroId.get().session();
        StoreApi.LinkedProfile chosen = pick(profiles, session == null ? null : session.username);
        if (chosen == null) return removeAll(ctx);

        final String uuid = dash(norm(chosen.uuid));
        final String name = chosen.username;
        boolean changed = ctx.accountRepository.modify(all -> {
            // Any older mirrored names (from versions that listed one account per name) go away.
            boolean c = all.removeIf(a -> a.astroLinked && !AccountChoice.ASTRO_ACCOUNT_ID.equals(a.id));
            Account mine = null;
            for (Account a : all) if (AccountChoice.ASTRO_ACCOUNT_ID.equals(a.id)) { mine = a; break; }
            if (mine == null) {
                mine = new Account(AccountChoice.ASTRO_ACCOUNT_ID, Account.Type.OFFLINE, name, uuid);
                mine.astroLinked = true;
                all.add(mine);
                return true;
            }
            if (!name.equals(mine.username) || !uuid.equals(mine.minecraftUuid) || !mine.astroLinked) {
                mine.username = name;       // the skin settings (offlineSkinId...) stay with the account
                mine.minecraftUuid = uuid;
                mine.astroLinked = true;
                c = true;
            }
            return c;
        });

        boolean selectionFixed = AccountChoice.ensureValid(ctx);
        if (changed) AppLogger.get().info(TAG, "Astro account set to " + name + ".");
        return changed || selectionFixed;
    }

    /** Signed out of the Astro ID: drop the Astro account. Returns true if anything changed. */
    public static boolean removeAll(AppContext ctx) {
        if (ctx == null) return false;
        boolean changed = ctx.accountRepository.modify(all -> all.removeIf(a -> a.astroLinked));
        boolean selectionFixed = AccountChoice.ensureValid(ctx);
        return changed || selectionFixed;
    }

    /** The name to play as: unverified (offline-mode) only, the Astro username first. */
    private static StoreApi.LinkedProfile pick(List<StoreApi.LinkedProfile> profiles, String preferredName) {
        StoreApi.LinkedProfile first = null;
        if (profiles == null) return null;
        for (StoreApi.LinkedProfile p : profiles) {
            if (p == null || p.verified || p.username == null || norm(p.uuid) == null) continue;
            if (preferredName != null && p.username.equalsIgnoreCase(preferredName)) return p;
            if (first == null) first = p;
        }
        return first;
    }

    /** Lower-case 32-hex form of a UUID, or null when it isn't one. */
    private static String norm(String uuid) {
        if (uuid == null) return null;
        String s = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{32}") ? s : null;
    }

    private static String dash(String hex32) {
        return hex32.substring(0, 8) + "-" + hex32.substring(8, 12) + "-" + hex32.substring(12, 16)
                + "-" + hex32.substring(16, 20) + "-" + hex32.substring(20);
    }
}
