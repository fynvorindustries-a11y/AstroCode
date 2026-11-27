package com.astrolauncher.ui;

import com.astrolauncher.ui.kit.RankToast;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputControl;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Small hidden surprises. Nothing here is advertised in the UI:
 * <ul>
 *   <li>Konami code (up up down down left right left right B A): party mode.</li>
 *   <li>Type "herobrine", "creeper" or "astro" anywhere (not in a text box).</li>
 *   <li>Click the logo in the sidebar 7 times: Myth Busters.</li>
 *   <li>A very rare greeting at startup.</li>
 * </ul>
 */
public final class EasterEggs {

    private static final KeyCode[] KONAMI = {
            KeyCode.UP, KeyCode.UP, KeyCode.DOWN, KeyCode.DOWN,
            KeyCode.LEFT, KeyCode.RIGHT, KeyCode.LEFT, KeyCode.RIGHT, KeyCode.B, KeyCode.A};

    /** Myth, verdict, explanation. */
    private static final List<String[]> MYTHS = List.of(
            new String[] {"Creepers were supposed to be pigs.", "CONFIRMED",
                    "The creeper came from a mix-up while making a pig model: the height and width values were swapped."},
            new String[] {"Minecraft was always called Minecraft.", "BUSTED",
                    "Its early working title was \"Cave Game\" before it became Minecraft."},
            new String[] {"The Warden hunts you by sight.", "BUSTED",
                    "The Warden is blind. It tracks vibrations (steps, blocks, projectiles) and smells you when you are very close."},
            new String[] {"Cave spiders spawn naturally in any cave.", "BUSTED",
                    "They only come from monster spawners, which you normally meet inside abandoned mineshafts."},
            new String[] {"Name a sheep \"jeb_\" and nothing happens.", "BUSTED",
                    "A sheep named jeb_ cycles through every wool colour. Naming a mob \"Dinnerbone\" or \"Grumm\" turns it upside down."},
            new String[] {"Cats keep creepers away.", "CONFIRMED",
                    "Creepers are scared of cats and ocelots and will run from them."},
            new String[] {"You can still craft an enchanted golden apple.", "BUSTED",
                    "The recipe was removed in 1.9. Now they only turn up as loot in certain chests."},
            new String[] {"Hostile mobs spawn in any dark place.", "BUSTED",
                    "In recent Java versions (1.18 and later) hostile mobs spawn at light level 0 only, so a little light goes a long way."},
            new String[] {"Zombie villagers are lost for good.", "BUSTED",
                    "Splash one with a potion of weakness, then feed it a golden apple, and it turns back into a villager with better trade prices."},
            new String[] {"Phantoms appear at random.", "BUSTED",
                    "They show up after you go about three in-game days without sleeping."},
            new String[] {"Endermites only come from the End.", "BUSTED",
                    "Throwing an ender pearl has a small chance to spawn one where you land."},
            new String[] {"Dolphins can lead you to treasure.", "CONFIRMED",
                    "Feed one a raw cod or salmon and it swims toward a nearby buried treasure or shipwreck."},
            new String[] {"Landing on hay bales hurts less.", "CONFIRMED",
                    "A hay bale cuts fall damage by about 80%."},
            new String[] {"All axolotls are equally common.", "BUSTED",
                    "Blue axolotls are the rarest: when two breed, there is only about a 1 in 1,200 chance of a blue baby."},
            new String[] {"A rabbit named Toast looks like any other rabbit.", "BUSTED",
                    "A rabbit with the name Toast gets a special skin, a tribute to a player's pet rabbit."},
            new String[] {"The Far Lands are just a rumour.", "CONFIRMED",
                    "In old Java versions, about 12.5 million blocks from the centre, terrain generation broke down into the strange Far Lands."},
            new String[] {"Water flows forever.", "BUSTED",
                    "Flowing water only spreads about 7 blocks from its source. Two source blocks side by side make an endless supply."},
            new String[] {"The moon has no effect on the game.", "BUSTED",
                    "Moon phases change how often slimes spawn in swamps; a full moon is the best time."},
            new String[] {"Herobrine is hiding in every world.", "BUSTED",
                    "Herobrine started as a 2010 creepypasta. Mojang has joked \"Removed Herobrine\" in patch notes ever since."});

    private final Scene scene;
    private final Node shell;
    private final RankToast toast;
    private final Random rng = new Random();

    private int konamiPos;
    private final StringBuilder typed = new StringBuilder();
    private int logoClicks;
    private final PauseTransition logoReset = new PauseTransition(Duration.seconds(2.5));
    private Timeline party;
    private int mythIndex = -1;

    private EasterEggs(Scene scene, Node shell, RankToast toast) {
        this.scene = scene;
        this.shell = shell;
        this.toast = toast;
    }

    /** Wires everything up. {@code shell} is the node the effects are applied to. */
    public static EasterEggs install(Scene scene, Node shell, RankToast toast) {
        EasterEggs e = new EasterEggs(scene, shell, toast);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e::onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, e::onKeyTyped);
        e.logoReset.setOnFinished(ev -> e.logoClicks = 0);
        // Very rare startup greeting (about 1 launch in 60), shown after the splash has finished.
        if (e.rng.nextInt(60) == 0) {
            PauseTransition wait = new PauseTransition(Duration.seconds(5));
            wait.setOnFinished(ev -> toast.show("Herobrine was not here.", "Probably.", "#9aa0ff"));
            wait.play();
        }
        return e;
    }

    /** Call for every click on the sidebar logo. */
    public void logoClicked() {
        logoClicks++;
        logoReset.playFromStart();
        if (logoClicks == 4) {
            toast.show("Hmm?", "Keep poking\u2026", "#a78bfa");
        } else if (logoClicks >= 7) {
            logoClicks = 0;
            Platform.runLater(this::showMyth);
        }
    }

    private boolean typingInTextBox() {
        return scene.getFocusOwner() instanceof TextInputControl;
    }

    private void onKeyPressed(KeyEvent e) {
        if (typingInTextBox()) {
            konamiPos = 0;
            return;
        }
        KeyCode code = e.getCode();
        if (code == KONAMI[konamiPos]) {
            konamiPos++;
            if (konamiPos == KONAMI.length) {
                konamiPos = 0;
                partyMode();
            }
        } else {
            konamiPos = code == KONAMI[0] ? 1 : 0;
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (typingInTextBox() || e.isControlDown() || e.isMetaDown()) return;
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || !Character.isLetter(ch.charAt(0))) {
            typed.setLength(0);
            return;
        }
        typed.append(ch.toLowerCase(Locale.ROOT));
        if (typed.length() > 12) typed.delete(0, typed.length() - 12);
        String t = typed.toString();
        if (t.endsWith("herobrine")) {
            typed.setLength(0);
            toast.show("Removed Herobrine.", "Again.", "#9aa0ff");
        } else if (t.endsWith("creeper")) {
            typed.setLength(0);
            shake();
            toast.show("Ssssss\u2026", "Aw man.", "#5ec97a");
        } else if (t.endsWith("astro")) {
            typed.setLength(0);
            toast.show("Astro Launcher", "Play. Explore. Enjoy.", "#c084fc");
        }
    }

    // ───────────────────────────────────────────────────────────── effects

    private void partyMode() {
        if (party != null && party.getStatus() == Animation.Status.RUNNING) return;
        toast.show("Party mode!", "\u2191\u2191\u2193\u2193\u2190\u2192\u2190\u2192 B A", "#f472b6");
        ColorAdjust hue = new ColorAdjust();
        shell.setEffect(hue);
        party = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(hue.hueProperty(), 0)),
                new KeyFrame(Duration.seconds(2.5), new KeyValue(hue.hueProperty(), 1)),
                new KeyFrame(Duration.seconds(5), new KeyValue(hue.hueProperty(), 0)));
        party.setOnFinished(ev -> shell.setEffect(null));
        party.play();
    }

    private void shake() {
        if (!Motion.enabled()) return;
        Timeline t = new Timeline();
        for (int i = 0; i < 8; i++) {
            double dx = (i % 2 == 0 ? 1 : -1) * (7 - i * 0.7);
            t.getKeyFrames().add(new KeyFrame(Duration.millis(i * 45), new KeyValue(shell.translateXProperty(), dx)));
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(8 * 45), new KeyValue(shell.translateXProperty(), 0)));
        t.play();
    }

    private void showMyth() {
        if (mythIndex < 0) mythIndex = rng.nextInt(MYTHS.size());
        ButtonType next = new ButtonType("Next myth", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        while (true) {
            String[] m = MYTHS.get(mythIndex % MYTHS.size());
            Alert a = new Alert(Alert.AlertType.NONE, "", next, ButtonType.CLOSE);
            a.setTitle("Astro Myth Busters");
            a.setHeaderText("Myth: " + m[0]);
            a.setContentText(m[1] + ".  " + m[2]);
            a.getDialogPane().setMinWidth(460);
            boolean again = a.showAndWait().filter(b -> b == next).isPresent();
            mythIndex++;
            if (!again) break;
        }
    }
}
