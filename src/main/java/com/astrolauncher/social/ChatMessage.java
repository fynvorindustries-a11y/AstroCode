package com.astrolauncher.social;

/**
 * One chat line, exactly as {@code GET /api/v1/social/chat} returns it:
 * <pre>{ id, channel, from, body }</pre>
 * {@code id} is a monotonically increasing message id — the client's "latest id seen" is what the
 * next poll's {@code after} parameter uses, so messages are never re-delivered or skipped.
 */
public class ChatMessage {
    public long id;
    /** "global", "party", or "dm" — whatever the sender's {@code channel} was. */
    public String channel;
    /** The sender's display name (Astro ID or linked Minecraft name — the server decides which). */
    public String from;
    public String body;

    public ChatMessage() { }

    public ChatMessage(long id, String channel, String from, String body) {
        this.id = id; this.channel = channel; this.from = from; this.body = body;
    }
}
