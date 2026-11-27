package com.astrolauncher.ui.pages;

import com.astrolauncher.social.ChatMessage;
import com.astrolauncher.social.Friend;
import com.astrolauncher.social.SocialListener;
import com.astrolauncher.social.SocialService;
import com.astrolauncher.social.SocialSync;
import com.astrolauncher.store.AstroId;
import com.astrolauncher.ui.AppContext;
import com.astrolauncher.ui.kit.AvButton;
import com.astrolauncher.ui.kit.Card;
import com.astrolauncher.ui.kit.PageHeader;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Friends list, party actions, and chat (global / party / a direct message), talking to the Astro
 * server's {@code /api/v1/social/*} routes through {@link SocialService}.
 *
 * <p>One real gap, flagged rather than faked: the server has no "what's my party right now" route —
 * only {@code POST /social/party} to act on it. So this page can send invite/leave/kick, and shows
 * the server's own answer for each, but can't display a live party roster until such a route
 * exists. Everything else (friends list, add/remove, all three chat channels) is wired to the real
 * endpoints as the backend defines them.
 */
public final class FriendsPage {

    private final AppContext appContext;
    private final ExecutorService actions = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "astro-friends-ui-action");
        t.setDaemon(true);
        return t;
    });

    private final VBox friendsList = new VBox(6);
    private final VBox messageList = new VBox(4);
    private final ScrollPane chatScroll = new ScrollPane(messageList);
    private final Label chatTargetLabel = new Label("Global chat");
    private final Label statusLine = new Label();
    private final TextField addFriendField = new TextField();
    private final TextField chatInput = new TextField();

    /** "global" or "party" tab, or a DM target name when chatting 1:1 with a friend. */
    private String channel = "global";
    private String dmTarget;

    private final SocialListener listener = new SocialListener() {
        // onFriendsChanged only ever fires while signed in (SocialService only polls then), so it
        // doubles as the "we're signed in" signal that re-enables chat if this page was already open
        // when the sign-in completed — there's no separate onSignedIn callback for that.
        @Override public void onFriendsChanged(List<Friend> friends) { Platform.runLater(() -> { renderFriends(friends); setChatEnabled(true); }); }
        @Override public void onChatMessages(List<ChatMessage> messages) { Platform.runLater(() -> appendMessages(messages)); }
        @Override public void onSignedOut() { Platform.runLater(() -> { renderFriends(List.of()); messageList.getChildren().clear(); setChatEnabled(false); }); }
    };

    private void setChatEnabled(boolean enabled) {
        chatInput.setDisable(!enabled);
        chatInput.setPromptText(enabled ? "Message..." : "Sign in to chat");
    }

    public FriendsPage(AppContext appContext) {
        this.appContext = appContext;
    }

    public VBox build() {
        VBox root = new VBox(22);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));
        root.getChildren().addAll(new PageHeader("Friends", "Add friends, party up, and chat — global, party, or one to one."), body());

        root.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) {
                SocialService.get().removeListener(listener);
            } else {
                SocialService.get().addListener(listener);
                renderFriends(SocialService.get().friendsSnapshot());
            }
        });
        renderFriends(SocialService.get().friendsSnapshot());
        return root;
    }

    private HBox body() {
        HBox row = new HBox(18, friendsCard(), chatCard());
        row.setFillHeight(true);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        return row;
    }

    // ------------------------------------------------------------------ friends + party

    private Card friendsCard() {
        VBox col = new VBox(14);
        col.setPadding(new Insets(18));
        col.setPrefWidth(320);
        col.setMinWidth(280);

        Label title = new Label("YOUR FRIENDS");
        title.getStyleClass().add("av-section-title");

        addFriendField.setPromptText("Astro ID or Minecraft name");
        addFriendField.getStyleClass().add("search-field-input");
        AvButton add = new AvButton("Add", AvButton.Kind.GHOST);
        add.setOnAction(e -> addFriend());
        addFriendField.setOnAction(e -> addFriend());
        HBox addRow = new HBox(8, addFriendField, add);
        HBox.setHgrow(addFriendField, Priority.ALWAYS);

        ScrollPane listScroll = new ScrollPane(friendsList);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().addAll("page-scroll", "av-page-scroll");
        listScroll.setPrefHeight(320);
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        Label partyTitle = new Label("PARTY");
        partyTitle.getStyleClass().add("av-section-title");
        AvButton invite = new AvButton("Invite", AvButton.Kind.GHOST);
        invite.setOnAction(e -> promptAndPartyAction("invite", "Invite to party", "Player name to invite:"));
        AvButton leave = new AvButton("Leave", AvButton.Kind.GHOST);
        leave.setOnAction(e -> partyAction("leave", null));
        HBox partyRow = new HBox(8, invite, leave);

        statusLine.getStyleClass().add("av-page-sub");
        statusLine.setWrapText(true);

        col.getChildren().addAll(title, addRow, listScroll, partyTitle, partyRow, statusLine);
        return new Card(col);
    }

    private void addFriend() {
        String name = addFriendField.getText() == null ? "" : addFriendField.getText().trim();
        if (name.isEmpty()) return;
        addFriendField.clear();
        actions.submit(() -> {
            SocialSync.Outcome<String> o = SocialService.get().addFriend(name);
            Platform.runLater(() -> {
                setStatus(describe(o, "Friend request sent.", "Couldn't add " + name + "."));
                if (o.ok()) renderFriends(SocialService.get().friendsSnapshot());
            });
        });
    }

    private void removeFriend(Friend f) {
        actions.submit(() -> {
            SocialSync.Outcome<String> o = SocialService.get().removeFriend(f.name);
            Platform.runLater(() -> setStatus(describe(o, f.name + " removed.", "Couldn't remove " + f.name + ".")));
        });
    }

    private void promptAndPartyAction(String action, String title, String prompt) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        d.showAndWait().ifPresent(name -> { if (!name.isBlank()) partyAction(action, name.trim()); });
    }

    private void partyAction(String action, String name) {
        actions.submit(() -> {
            SocialSync.Outcome<com.google.gson.JsonElement> o = SocialService.get().party(action, name);
            Platform.runLater(() -> {
                if (o.notSupported()) setStatus("Party isn't available yet.");
                else if (!o.ok()) setStatus(o.error() != null ? o.error() : "That party action failed.");
                else setStatus("Party: " + action + (name != null ? " " + name : "") + " — " + (o.data() != null ? o.data().toString() : "done") + ".");
            });
        });
    }

    private void setStatus(String s) { statusLine.setText(s); }

    private static String describe(SocialSync.Outcome<String> o, String okMsg, String failMsg) {
        if (o.notSupported()) return "Friends aren't available yet.";
        if (o.transientFailure()) return "Couldn't reach the server — try again in a moment.";
        if (!o.ok()) return o.error() != null ? o.error() : failMsg;
        return o.data() != null ? o.data() : okMsg;
    }

    private void renderFriends(List<Friend> list) {
        friendsList.getChildren().clear();
        if (list == null || list.isEmpty()) {
            Label empty = new Label("No friends yet — add one above.");
            empty.getStyleClass().add("av-page-sub");
            friendsList.getChildren().add(empty);
            return;
        }
        for (Friend f : list) friendsList.getChildren().add(friendRow(f));
    }

    private HBox friendRow(Friend f) {
        Region dot = new Region();
        dot.getStyleClass().add("account-tag-dot");
        if (!f.online) dot.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("offline"), true);

        Label name = new Label(f.name != null ? f.name : "?");
        name.getStyleClass().add("account-name");
        VBox text = new VBox(1, name);
        if (f.online && f.server != null && !f.server.isBlank()) {
            Label server = new Label(f.server);
            server.getStyleClass().add("account-tag-label");
            text.getChildren().add(server);
        }

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);

        AvButton message = new AvButton("Chat", AvButton.Kind.GHOST);
        message.setOnAction(e -> openDm(f.name));
        AvButton remove = new AvButton("Remove", AvButton.Kind.GHOST);
        remove.setOnAction(e -> removeFriend(f));

        HBox row = new HBox(10, dot, text, spring, message, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("account-chip");
        row.setPadding(new Insets(8, 10, 8, 12));
        return row;
    }

    // ------------------------------------------------------------------ chat

    private Card chatCard() {
        VBox col = new VBox(14);
        col.setPadding(new Insets(18));
        HBox.setHgrow(col, Priority.ALWAYS);

        ToggleGroup tg = new ToggleGroup();
        ToggleButton global = tab("Global", tg, true);
        global.setOnAction(e -> switchChannel("global", null));
        ToggleButton party = tab("Party", tg, false);
        party.setOnAction(e -> switchChannel("party", null));
        HBox tabs = new HBox(8, global, party);

        chatTargetLabel.getStyleClass().add("av-section-title");

        chatScroll.setFitToWidth(true);
        chatScroll.getStyleClass().addAll("page-scroll", "av-page-scroll");
        chatScroll.setPrefHeight(360);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        messageList.heightProperty().addListener((o, was, now) -> chatScroll.setVvalue(1.0));

        chatInput.setPromptText(AstroId.get().isSignedIn() ? "Message..." : "Sign in to chat");
        chatInput.setDisable(!AstroId.get().isSignedIn());
        chatInput.getStyleClass().add("search-field-input");
        AvButton send = new AvButton("Send", AvButton.Kind.PRIMARY);
        send.setOnAction(e -> sendMessage());
        chatInput.setOnAction(e -> sendMessage());
        HBox inputRow = new HBox(8, chatInput, send);
        HBox.setHgrow(chatInput, Priority.ALWAYS);

        col.getChildren().addAll(tabs, chatTargetLabel, chatScroll, inputRow);
        return new Card(col);
    }

    private ToggleButton tab(String label, ToggleGroup group, boolean selected) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("av-btn-ghost");
        b.setToggleGroup(group);
        b.setSelected(selected);
        return b;
    }

    private void switchChannel(String ch, String dm) {
        channel = ch;
        dmTarget = dm;
        chatTargetLabel.setText(dm != null ? "Chat with " + dm : (ch.equals("party") ? "Party chat" : "Global chat"));
        messageList.getChildren().clear(); // this page only shows messages received since it's been open — there's no "chat history" route to back-fill from
    }

    private void openDm(String name) {
        if (name == null) return;
        switchChannel("dm", name);
    }

    private void sendMessage() {
        String body = chatInput.getText() == null ? "" : chatInput.getText().trim();
        if (body.isEmpty()) return;
        chatInput.clear();
        String ch = channel, to = dmTarget;
        actions.submit(() -> {
            SocialSync.Outcome<String> o = SocialService.get().sendChat(ch, to, body);
            if (!o.ok()) Platform.runLater(() -> setStatus(o.notSupported() ? "Chat isn't available yet." : (o.error() != null ? o.error() : "Message didn't send.")));
        });
    }

    private void appendMessages(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            boolean mine = channel.equals(normalizedChannel(m.channel)) && (!"dm".equals(channel) || (dmTarget != null && (dmTarget.equalsIgnoreCase(m.from) || isMe(m.from))));
            if (!mine) continue; // showing exactly the open tab's messages, same as switching tabs clearing the view above
            messageList.getChildren().add(bubble(m));
        }
    }

    private static String normalizedChannel(String c) { return c == null ? "global" : c; }

    private boolean isMe(String name) {
        var s = AstroId.get().session();
        return s != null && s.username != null && s.username.equalsIgnoreCase(name);
    }

    private VBox bubble(ChatMessage m) {
        Label from = new Label(isMe(m.from) ? "You" : m.from);
        from.getStyleClass().add("account-tag-label");
        Label body = new Label(m.body);
        body.getStyleClass().add("account-name");
        body.setWrapText(true);
        body.setTextAlignment(TextAlignment.LEFT);
        VBox box = new VBox(2, from, body);
        box.getStyleClass().add("av-chip");
        box.setPadding(new Insets(8, 12, 8, 12));
        box.setMaxWidth(520);
        return box;
    }
}
