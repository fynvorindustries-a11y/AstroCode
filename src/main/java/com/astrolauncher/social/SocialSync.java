package com.astrolauncher.social;

import com.astrolauncher.store.StoreApi;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Talks to the Astro server's social endpoints (friends, party, chat; see the backend's
 * {@code /api/v1/social/*} routes). Mirrors {@code RankSync}'s shape deliberately: every call goes
 * through {@link StoreApi#authedJson}, so it gets the same transparent access-token refresh, and
 * every result distinguishes "the server doesn't have this yet" (404/501 — keep working with
 * nothing, don't nag the player) from "the network/server hiccuped" (retry later, don't show an
 * error) from "the server rejected this specific request" (show {@code error} to the player).
 *
 * <p>The routes themselves use a normal Astro access token here (not the in-game launch token with
 * the "social" scope — {@code socialAuthed} on the server accepts either), so none of this needs the
 * game to be running.
 */
public final class SocialSync {

    public record Outcome<T>(T data, boolean notSupported, boolean transientFailure, String error) {
        public boolean ok() { return error == null && !notSupported && !transientFailure; }
    }

    /** {@code GET /social/chat} answers both the new messages and the id to poll with next time. */
    public record ChatPoll(List<ChatMessage> messages, long latest) { }

    private static final Gson GSON = new Gson();
    private static final long TIMEOUT_S = 20;
    private final StoreApi api;

    public SocialSync(StoreApi api) { this.api = api; }

    // ------------------------------------------------------------------ friends

    public Outcome<List<Friend>> friends() {
        return exec("GET", "/social/friends", null, o -> {
            List<Friend> out = new ArrayList<>();
            if (o.has("friends") && o.get("friends").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("friends")) {
                    if (!e.isJsonObject()) continue;
                    try { out.add(GSON.fromJson(e, Friend.class)); } catch (Exception ignored) { /* unexpected shape for this one entry; skip it, not the whole list */ }
                }
            }
            return out;
        });
    }

    /** {@code name} may be an Astro ID username or a Minecraft name — the server tries the Astro ID first. */
    public Outcome<String> addFriend(String name) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        return exec("POST", "/social/friends/add", b, SocialSync::result);
    }

    public Outcome<String> removeFriend(String name) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        return exec("POST", "/social/friends/remove", b, SocialSync::result);
    }

    // ------------------------------------------------------------------ chat

    /**
     * {@code after = -1} asks only for the current latest id (used to start a poll loop without
     * replaying every message ever sent); pass that back in as {@code after} on the next call, and
     * each call after that pass whatever {@code latest()} returned.
     */
    public Outcome<ChatPoll> pollChat(long after) {
        return exec("GET", "/social/chat?after=" + after, null, o -> {
            List<ChatMessage> msgs = new ArrayList<>();
            if (o.has("messages") && o.get("messages").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("messages")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject m = e.getAsJsonObject();
                    if (!m.has("id") || !m.has("body")) continue;
                    msgs.add(new ChatMessage(
                            m.get("id").getAsLong(),
                            m.has("channel") && !m.get("channel").isJsonNull() ? m.get("channel").getAsString() : "global",
                            m.has("from") && !m.get("from").isJsonNull() ? m.get("from").getAsString() : "?",
                            m.get("body").getAsString()));
                }
            }
            long latest = o.has("latest") && !o.get("latest").isJsonNull() ? o.get("latest").getAsLong() : after;
            return new ChatPoll(msgs, latest);
        });
    }

    /** {@code channel}: "global", "party", or "dm" (max 8 chars server-side). {@code to}: DM target name, else null. */
    public Outcome<String> sendChat(String channel, String to, String body) {
        JsonObject b = new JsonObject();
        b.addProperty("channel", channel);
        if (to != null) b.addProperty("to", to); else b.add("to", null);
        b.addProperty("body", body);
        return exec("POST", "/social/chat", b, SocialSync::result);
    }

    // ------------------------------------------------------------------ party

    /**
     * {@code action}/{@code name} pass straight through to the server's {@code social_party} RPC, whose
     * accepted action strings aren't visible from the route alone; {@code invite} (with a target
     * name), {@code leave} and {@code kick} (with a target name) are the obvious candidates for a
     * party of this shape. Adjust the action names {@link com.astrolauncher.social.SocialService}
     * sends if the server rejects one with a different expected spelling.
     */
    public Outcome<JsonElement> party(String action, String name) {
        JsonObject b = new JsonObject();
        b.addProperty("action", action);
        if (name != null) b.addProperty("name", name); else b.add("name", null);
        return exec("POST", "/social/party", b, o -> o.has("result") ? o.get("result") : null);
    }

    // ------------------------------------------------------------------ presence & lookup

    /** Tells the server which linked name you're playing as right now, and on which server. */
    public Outcome<Boolean> presence(String name, String server) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        if (server != null) b.addProperty("server", server); else b.add("server", null);
        return exec("POST", "/social/presence", b, o -> o.has("ok") && o.get("ok").getAsBoolean());
    }

    /** Of the given player names, which ones are also on the Astro client right now. */
    public Outcome<List<String>> lookup(List<String> names) {
        JsonObject b = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String n : names) arr.add(n);
        b.add("names", arr);
        return exec("POST", "/social/lookup", b, o -> {
            List<String> out = new ArrayList<>();
            if (o.has("astro") && o.get("astro").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("astro")) if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ plumbing

    private static String result(JsonObject o) {
        if (!o.has("result") || o.get("result").isJsonNull()) return null;
        JsonElement r = o.get("result");
        // getAsString() on a primitive gives the bare value ("ok", not "\"ok\""); result could in
        // principle also be a non-primitive RPC return, so fall back to the raw JSON for that case.
        return r.isJsonPrimitive() ? r.getAsString() : r.toString();
    }

    private <T> Outcome<T> exec(String method, String path, JsonObject body, Function<JsonObject, T> parse) {
        try {
            JsonObject o = api.authedJson(method, path, body).get(TIMEOUT_S, TimeUnit.SECONDS);
            return new Outcome<>(parse.apply(o), false, false, null);
        } catch (Exception e) {
            Throwable r = e;
            while (r.getCause() != null && r.getCause() != r) r = r.getCause();
            if (r instanceof StoreApi.StoreException se) {
                if (se.status == 404 || se.status == 405 || se.status == 501 || "NO_SERVER".equals(se.code)) {
                    return new Outcome<>(null, true, false, se.getMessage());
                }
                return new Outcome<>(null, false, se.isTransient(), se.getMessage());
            }
            return new Outcome<>(null, false, true, r.getMessage());
        }
    }
}
