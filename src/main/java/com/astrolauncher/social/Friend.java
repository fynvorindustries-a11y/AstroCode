package com.astrolauncher.social;

/**
 * One entry from {@code GET /api/v1/social/friends}.
 *
 * <p><b>Field names here are a best-effort guess, not a known contract.</b> The route itself
 * ({@code social_friends}) is a Postgres RPC whose return shape isn't visible from the Edge Function
 * source — only the HTTP contract (path, method, that the body is {@code {friends: [...]}}) is. The
 * fields below are the ones a friends list obviously needs, named the way the rest of this backend
 * names things (camelCase, {@code online}/{@code server} read naturally next to the presence endpoint).
 * Gson leaves anything that doesn't match as the field's default (null/false/0) rather than failing,
 * so this won't crash if the real shape differs — but {@link SocialSync#friends()}'s callers should
 * expect to adjust these field names once the actual JSON is seen from a running server.
 */
public class Friend {
    /** Astro ID username, or the shown Minecraft name if that's what the server keys it by. */
    public String name;
    public boolean online;
    /** Server address they're currently on, or null when offline / not reported. */
    public String server;
    public String uuid;

    public Friend() { }
}
