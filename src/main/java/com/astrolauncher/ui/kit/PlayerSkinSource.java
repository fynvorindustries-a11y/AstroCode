package com.astrolauncher.ui.kit;

import com.astrolauncher.model.Account;
import com.astrolauncher.skins.SkinLibrary;
import com.astrolauncher.skins.SkinService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Finds the skin to put on a 3D player. Same source order as the Home hero: the library skin an offline
 * account wears, then the skin URL Mojang reported, then (Microsoft accounts) a lookup by name. It never fails:
 * with no account, no skin or no network it hands back a built-in default outfit, so the model always shows.
 */
public final class PlayerSkinSource {

    private PlayerSkinSource() {}

    /** Runs on a background thread; {@code account} is read there too (it may hit the disk). */
    public static CompletableFuture<SkinModel3D.Prepared> load(Supplier<Account> account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Account a = account.get();
                SkinModel3D.Prepared p = fromAccount(a);
                if (p != null) return p;
            } catch (Exception ignored) {
                // fall through to the default outfit
            }
            return defaultSkin();
        });
    }

    private static SkinModel3D.Prepared fromAccount(Account acct) throws Exception {
        if (acct == null) return null;
        if (acct.offlineSkinId != null && !acct.offlineSkinId.isBlank()) {
            Path p = new SkinLibrary().pngPath(acct.offlineSkinId);
            if (Files.isRegularFile(p)) {
                long stamp = Files.getLastModifiedTime(p).toMillis();
                return SkinModel3D.prepare(Files.readAllBytes(p), acct.offlineSkinVariant, p + "|" + stamp);
            }
        }
        SkinService skins = new SkinService();
        if (acct.skinUrl != null && !acct.skinUrl.isBlank()) {
            byte[] png = skins.download(acct.skinUrl).get(10, TimeUnit.SECONDS);
            return SkinModel3D.prepare(png, acct.skinVariant, acct.skinUrl);
        }
        boolean microsoft = acct.type == Account.Type.MICROSOFT;
        if (microsoft && acct.username != null && !acct.username.isBlank()) {
            SkinService.RemoteSkin rs = skins.fetchByUsername(acct.username).get(10, TimeUnit.SECONDS);
            return SkinModel3D.prepare(rs.png, rs.variant, "name:" + acct.username);
        }
        return null;
    }

    // ------------------------------------------------------------------ built-in default outfit

    private static SkinModel3D.Prepared defaultSkin() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(drawDefault(), "png", out);
            return SkinModel3D.prepare(out.toByteArray(), "classic", "astro-default-skin");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage drawDefault() {
        int skin = 0xFFC58C6B, hair = 0xFF3B2A1E, shirt = 0xFF2B3445, accent = 0xFFFF4655,
                pants = 0xFF1B2130, shoe = 0xFF0E121B, white = 0xFFFFFFFF, iris = 0xFF3A6EA5, mouth = 0xFF8A5A44;
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 32, 16, skin);                       // head island
        fill(img, 8, 0, 8, 8, hair);                          // hair: whole top face
        fill(img, 0, 8, 8, 4, hair); fill(img, 16, 8, 8, 4, hair); fill(img, 8, 8, 8, 2, hair);   // sides + fringe
        fill(img, 24, 8, 8, 8, hair);                         // back of the head: all hair
        fill(img, 9, 12, 2, 1, white); fill(img, 13, 12, 2, 1, white);
        fill(img, 10, 12, 1, 1, iris); fill(img, 13, 12, 1, 1, iris);
        fill(img, 11, 14, 2, 1, mouth);
        fill(img, 16, 16, 24, 16, shirt);                    // body island
        fill(img, 20, 24, 8, 1, accent);                     // chest stripe
        fill(img, 40, 16, 16, 16, shirt); fill(img, 40, 28, 16, 4, skin);   // right arm, hands
        fill(img, 32, 48, 16, 16, shirt); fill(img, 32, 60, 16, 4, skin);   // left arm
        fill(img, 0, 16, 16, 16, pants); fill(img, 0, 30, 16, 2, shoe);     // right leg
        fill(img, 16, 48, 16, 16, pants); fill(img, 16, 62, 16, 2, shoe);   // left leg
        return img;
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) img.setRGB(i, j, argb);
    }
}
