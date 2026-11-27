package com.astrolauncher.social;

import java.util.List;

/** Callbacks from {@link SocialService}. Invoked on background threads: UI code must hop to the FX thread. */
public interface SocialListener {
    /** The full friends list changed (added/removed/online state) — replace, don't diff. */
    default void onFriendsChanged(List<Friend> friends) { }
    /** One or more new chat messages arrived, oldest first. */
    default void onChatMessages(List<ChatMessage> messages) { }
    /** Fired whenever sign-in state flips, so the UI can clear a stale list/history. */
    default void onSignedOut() { }
}
