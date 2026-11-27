package com.astrolauncher.ui.kit;

import com.astrolauncher.logging.AppLogger;
import com.astrolauncher.model.Account;
import com.astrolauncher.ui.Motion;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The Store's cape previews (big featured card and the gallery tiles): the player's own skin in a fixed,
 * static fighting stance, seen from behind at a three-quarter angle so the selected cape hangs on its back.
 * The cape is four stacked segments, each bent a little further than the one above, so it drapes in a curve —
 * held still, not animated. Drag to turn the player and look around; double-click to reset the view.
 *
 * <p>Geometry is in skin pixels, as in {@link SkinModel3D}: 32 units tall, facing -Z, +Y down, so the player's back
 * (and the cape) are on the +Z side. Any cape texture works: 64x32 / 22x17 cape sheets are mapped properly (outer
 * face on the outside), other images are stretched over the cape. If 3D is unavailable, or the cape or skin can't
 * be built, the flat {@link CapePreview} underneath simply stays visible.
 */
public final class CapeStageModel3D extends StackPane {

    private static final double START_YAW = 125;     // behind the player, turned so both the cape and the stance read
    private static final int SEGMENTS = 4;
    private static final double CAPE_W = 10, CAPE_H = 16, CAPE_Z = 2.8;
    private static final int CAPE_TEX_TARGET = 512;

    // Fighting stance (degrees). Negative X angle swings a limb forward (-Z); Z roll tucks it toward the centre line.
    private static final double LEAN = 12;            // upper body leans into the fight
    private static final double TWIST = 24;           // shoulders turned: bladed stance
    private static final double HEAD_TURN = -16;      // head turns back toward the opponent
    private static final double LEAD_GUARD = -62, LEAD_ROLL = 16;     // player's right arm: bent-up guard
    private static final double REAR_GUARD = -122, REAR_ROLL = 26;    // left arm: guard up by the chin
    private static final double FRONT_LEG = -34, BACK_LEG = 30, LEG_SPLAY = 12;

    /** One full combo: jab, cross, quick jab, kick, then footwork until the loop restarts. */
    private static final double CYCLE = 2.6;
    private static final double STILL_U = 0.14;       // with animations off: frozen mid-jab

    /** A cape ready for the GPU: nearest-neighbour upscaled and the six face rectangles (normalised 0..1). */
    record CapeTexture(Image image, double[][] faces) { }

    private final CapePreview backdrop;
    private final SubScene scene3d;
    private final Group modelHolder = new Group();
    private final Rotate yaw = new Rotate(START_YAW, Rotate.Y_AXIS);
    private final Translate breathe = new Translate();
    private final Translate groundY = new Translate(0, 2.0, 0);
    private final Rotate leadRaise = new Rotate(LEAD_GUARD, Rotate.X_AXIS);
    private final Rotate rearRaise = new Rotate(REAR_GUARD, Rotate.X_AXIS);
    private final Rotate leadRoll = new Rotate(-LEAD_ROLL, Rotate.Z_AXIS);
    private final Rotate rearRoll = new Rotate(REAR_ROLL, Rotate.Z_AXIS);
    private final Rotate frontLeg = new Rotate(FRONT_LEG, Rotate.X_AXIS);
    private final Rotate backLeg = new Rotate(BACK_LEG, Rotate.X_AXIS);
    private final Rotate lean = new Rotate(LEAN, Rotate.X_AXIS);
    private final Rotate twist = new Rotate(TWIST, Rotate.Y_AXIS);
    private final Rotate headTurn = new Rotate(HEAD_TURN, Rotate.Y_AXIS);
    private final Rotate[] capeBend = new Rotate[SEGMENTS];
    private final boolean supported;
    private final Supplier<Account> accountSupplier;

    private CompletableFuture<SkinModel3D.Prepared> skinJob;
    private int ticket;
    private boolean ready;
    private double lastX;

    public CapeStageModel3D(double w, double h, Supplier<Account> accountSupplier) {
        this.accountSupplier = accountSupplier;
        setMinSize(w, h);
        setPrefSize(w, h);
        setMaxSize(w, h);
        setAlignment(Pos.CENTER);
        supported = Platform.isSupported(ConditionalFeature.SCENE3D);

        backdrop = new CapePreview(w, h);

        Group root = new Group();
        scene3d = new SubScene(root, w, h, true,
                Motion.enabled() ? SceneAntialiasing.BALANCED : SceneAntialiasing.DISABLED);
        scene3d.setFill(Color.TRANSPARENT);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setFieldOfView(28);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        camera.getTransforms().addAll(new Rotate(-8, Rotate.X_AXIS), new Translate(0, 0, -86));
        scene3d.setCamera(camera);

        AmbientLight ambient = new AmbientLight(Color.gray(0.70));
        PointLight key = new PointLight(Color.gray(0.58));
        key.setTranslateX(-60);
        key.setTranslateY(-100);
        key.setTranslateZ(-120);
        PointLight rim = new PointLight(Color.color(1.0, 0.55, 0.40, 1).deriveColor(0, 1, 0.45, 1));   // warm edge light
        rim.setTranslateX(70);
        rim.setTranslateY(-30);
        rim.setTranslateZ(90);

        modelHolder.getTransforms().add(yaw);
        root.getChildren().addAll(modelHolder, ambient, key, rim);
        scene3d.setVisible(false);

        if (supported) {
            getChildren().addAll(backdrop, scene3d);
            setCursor(Cursor.OPEN_HAND);
            installDrag();
        } else {
            getChildren().add(backdrop);
        }

        // Static display: the model is posed once (STILL_U, in showCape()) and never looped — no idle
        // timer, no sway, no breathing. Dragging still turns it; see installDrag().
    }

    // ------------------------------------------------------------------ public API (mirrors CapePreview)

    public void showLoading() {
        ticket++;
        ready = false;
        scene3d.setVisible(false);
        backdrop.setBackdropOnly(false);
        backdrop.showLoading();
    }

    public void showEmpty(String hint) {
        ticket++;
        ready = false;
        scene3d.setVisible(false);
        backdrop.setBackdropOnly(false);
        backdrop.showEmpty(hint);
    }

    /** Forget the cached skin (the active account or its skin may have changed). */
    public void refreshSkin() {
        skinJob = null;
    }

    public void showCape(String id, String name, String textureUrl) {
        final int mine = ++ticket;
        ready = false;
        scene3d.setVisible(false);
        backdrop.setBackdropOnly(false);
        backdrop.showCape(id, name, textureUrl);          // instant: tinted glow + flat cape, our fallback
        if (!supported || textureUrl == null || textureUrl.isBlank()) return;

        if (skinJob == null) skinJob = PlayerSkinSource.load(accountSupplier);
        CompletableFuture<SkinModel3D.Prepared> skin = skinJob;
        CompletableFuture<CapeTexture> cape = CompletableFuture.supplyAsync(() -> prepareCape(textureUrl));
        skin.thenCombine(cape, (s, c) -> new Object[]{s, c}).whenComplete((pair, err) -> Platform.runLater(() -> {
            if (mine != ticket) return;                    // a newer cape was picked meanwhile
            if (err != null || pair == null || pair[1] == null) {
                if (err != null) AppLogger.get().warn("CapeStage", "3D cape unavailable: " + err.getMessage());
                return;                                    // keep the flat preview
            }
            try {
                modelHolder.getChildren().setAll(buildFighter((SkinModel3D.Prepared) pair[0], (CapeTexture) pair[1]));
                backdrop.setBackdropOnly(true);
                scene3d.setVisible(true);
                ready = true;
                animate(STILL_U * CYCLE);   // static display: pose once and never loop
            } catch (RuntimeException e) {
                AppLogger.get().warn("CapeStage", "Could not build the 3D player: " + e.getMessage());
                backdrop.setBackdropOnly(false);
            }
        }));
    }

    // ------------------------------------------------------------------ interaction + animation

    private void installDrag() {
        setOnMousePressed(e -> { lastX = e.getSceneX(); setCursor(Cursor.CLOSED_HAND); });
        setOnMouseDragged(e -> {
            double dx = e.getSceneX() - lastX;
            lastX = e.getSceneX();
            yaw.setAngle(yaw.getAngle() + dx * 0.9);
        });
        setOnMouseReleased(e -> setCursor(Cursor.OPEN_HAND));
        setOnMouseClicked(e -> { if (e.getClickCount() == 2) yaw.setAngle(START_YAW); });
    }

    /** A smooth 0 -> 1 -> 0 bump while {@code u} is inside [a, b]; 0 elsewhere. */
    private static double pulse(double u, double a, double b) {
        if (u <= a || u >= b) return 0;
        double s = Math.sin(Math.PI * (u - a) / (b - a));
        return s * s;
    }

    /**
     * Poses the fighter at a single fixed instant of the combo (frozen mid-jab) and holds it there.
     * The model is always static — no idle timer runs, no sway, no breathing/footwork — regardless of
     * the app-wide Motion/Low-End setting. Dragging still turns the model (see {@link #installDrag()}),
     * it just never animates on its own.
     */
    private void animate(double t) {
        if (capeBend[0] == null) return;      // no model built yet
        double u = ((t % CYCLE) + CYCLE) % CYCLE / CYCLE;
        yaw.setAngle(START_YAW);

        double jab = pulse(u, 0.04, 0.20) + pulse(u, 0.48, 0.60);     // lead hand
        double cross = pulse(u, 0.26, 0.44);                           // rear hand, hips and shoulders drive it
        double kick = pulse(u, 0.70, 0.88);                            // front leg

        leadRaise.setAngle(LEAD_GUARD - 32 * jab);
        leadRoll.setAngle(-LEAD_ROLL * (1 - 0.9 * jab));
        rearRaise.setAngle(REAR_GUARD + 30 * cross);
        rearRoll.setAngle(REAR_ROLL * (1 - 0.85 * cross));

        double tw = TWIST + 18 * cross - 8 * jab - 4 * kick;
        twist.setAngle(tw);
        headTurn.setAngle(HEAD_TURN - (tw - TWIST) * 0.8);             // the head keeps its eyes on the opponent
        lean.setAngle(LEAN + 3 * cross + 4 * jab - 14 * kick);         // lean back into the kick

        frontLeg.setAngle(FRONT_LEG - 50 * kick);
        backLeg.setAngle(BACK_LEG + 4 * kick);
        breathe.setY(-0.5 * kick);

        // The cape still drapes with a bit of natural curve per segment, just no sway/whip over time.
        capeBend[0].setAngle(13 + 9 * cross + 7 * kick - 4 * jab);
        for (int k = 1; k < SEGMENTS; k++) {
            double c = pulse(u, 0.26, 0.44) + pulse(u, 0.70, 0.88) * 0.8;
            capeBend[k].setAngle(5 + 6 * c);
        }
    }

    // ------------------------------------------------------------------ the player

    private Group buildFighter(SkinModel3D.Prepared p, CapeTexture cape) {
        var baseMat = SkinModel3D.material(p.base());
        var overMat = SkinModel3D.material(p.overlay());
        double aw = p.slim() ? 3 : 4;
        double armX = 4 + aw / 2;

        Group bases = new Group();
        Group overlays = new Group();
        Group upper = new Group();     // head, torso, arms, cape: leans and twists as one
        Group lower = new Group();     // legs
        Group pose = new Group(bases, overlays);

        lean.setPivotY(4);
        headTurn.setPivotY(-8);

        // head, body
        part(bases, overlays, baseMat, overMat, 8, 8, 8, 0, -12, 0, 0, 0, 32, 0, 0.5, headTurn);
        part(bases, overlays, baseMat, overMat, 8, 12, 4, 0, -2, 0, 16, 16, 16, 32, 0.25);
        // arms: raise (X) then tuck toward the centre line (Z); both pivot at the shoulder
        part(bases, overlays, baseMat, overMat, aw, 12, 4, -armX, -2, 0, 40, 16, 40, 32, 0.25,
                pivot(leadRaise, -armX, -8), pivot(leadRoll, -armX, -8));
        part(bases, overlays, baseMat, overMat, aw, 12, 4, armX, -2, 0, 32, 48, 48, 48, 0.25,
                pivot(rearRaise, armX, -8), pivot(rearRoll, armX, -8));
        // legs: left foot forward, right foot back, both splayed a little; pivot at the hip
        part(bases, overlays, baseMat, overMat, 4, 12, 4, -2, 10, 0, 0, 16, 0, 32, 0.25,
                pivot(backLeg, -2, 4), pivot(new Rotate(LEG_SPLAY, Rotate.Z_AXIS), -2, 4));
        part(bases, overlays, baseMat, overMat, 4, 12, 4, 2, 10, 0, 16, 48, 0, 48, 0.25,
                pivot(frontLeg, 2, 4), pivot(new Rotate(-LEG_SPLAY, Rotate.Z_AXIS), 2, 4));

        // Everything above the hips goes through the lean/twist; the legs stay planted. Parts were added in the order
        // head, body, lead arm, rear arm, legs: move the first four of each layer into 'upper', base layers before
        // overlays (a transparent overlay pixel still writes depth, so overlays must be drawn after every base).
        for (int i = 0; i < 4; i++) upper.getChildren().add(bases.getChildren().get(0));
        for (int i = 0; i < 4; i++) upper.getChildren().add(overlays.getChildren().get(0));
        lower.getChildren().addAll(bases.getChildren());
        lower.getChildren().addAll(overlays.getChildren());
        upper.getChildren().add(buildCape(cape));
        upper.getTransforms().addAll(lean, twist, breathe);

        pose.getChildren().setAll(lower, upper);
        pose.getTransforms().add(groundY);
        return new Group(pose);
    }

    private static Rotate pivot(Rotate r, double x, double y) {
        r.setPivotX(x);
        r.setPivotY(y);
        r.setPivotZ(0);
        return r;
    }

    private static void part(Group bases, Group overlays, javafx.scene.paint.PhongMaterial baseMat,
                             javafx.scene.paint.PhongMaterial overMat, double w, double h, double d,
                             double cx, double cy, double cz, int bu, int bv, int ou, int ov, double grow,
                             Rotate... transforms) {
        MeshView base = SkinModel3D.box(w, h, d, cx, cy, cz, bu, bv, 0);
        base.setMaterial(baseMat);
        MeshView over = SkinModel3D.box(w, h, d, cx, cy, cz, ou, ov, grow);
        over.setMaterial(overMat);
        // Order matters: the first transform in the list is applied last, so (raise, tuck) tucks first and then raises.
        base.getTransforms().addAll(transforms);
        over.getTransforms().addAll(transforms);
        bases.getChildren().add(base);
        overlays.getChildren().add(over);
    }

    // ------------------------------------------------------------------ the cape

    /** Four nested slices; slice k rotates about its own top edge, carrying every slice below it. */
    private Group buildCape(CapeTexture tex) {
        var mat = SkinModel3D.material(tex.image());
        double top = -8, segH = CAPE_H / SEGMENTS;
        Group root = new Group();
        Group parent = root;
        for (int k = 0; k < SEGMENTS; k++) {
            double y0 = top + k * segH;
            Group seg = new Group();
            MeshView m = capeSlice(CAPE_W, segH, 1, 0, y0 + segH / 2, CAPE_Z, tex.faces(), k);
            m.setMaterial(mat);
            seg.getChildren().add(m);
            Rotate r = new Rotate(0, 0, y0, CAPE_Z, Rotate.X_AXIS);   // positive = hem swings backward
            capeBend[k] = r;
            seg.getTransforms().add(r);
            parent.getChildren().add(seg);
            parent = seg;
        }
        return root;
    }

    /**
     * One horizontal slice of the cape box. Faces: 0 -Z (against the body), 1 +Z (outside), 2 -X, 3 +X, 4 top,
     * 5 bottom; {@code faces} holds a normalised rectangle (u0, v0, du, dv) for each, and the slice picks its own
     * band of the tall faces. Corner order matches {@link SkinModel3D#box}.
     */
    private static MeshView capeSlice(double w, double h, double d, double cx, double cy, double cz,
                                      double[][] faces, int slice) {
        double hw = w / 2, hh = h / 2, hd = d / 2;
        float x0 = (float) (cx - hw), x1 = (float) (cx + hw), y0 = (float) (cy - hh), y1 = (float) (cy + hh),
                z0 = (float) (cz - hd), z1 = (float) (cz + hd);
        float[][] pos = {
                {x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0},   // -Z
                {x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1},   // +Z (outside)
                {x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1},   // -X
                {x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0},   // +X
                {x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0},   // top
                {x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1},   // bottom
        };
        TriangleMesh mesh = new TriangleMesh();
        float[] points = new float[6 * 12];
        float[] tex = new float[6 * 8];
        int[] idx = new int[6 * 12];
        int fi = 0;
        for (int f = 0; f < 6; f++) {
            System.arraycopy(pos[f], 0, points, f * 12, 12);
            double[] r = faces[f].clone();
            if (f < 4) {                                   // tall faces: take this slice's band
                r[1] = r[1] + r[3] * slice / SEGMENTS;
                r[3] = r[3] / SEGMENTS;
            }
            float u0 = (float) r[0], v0 = (float) r[1], u1 = (float) (r[0] + r[2]), v1 = (float) (r[1] + r[3]);
            System.arraycopy(new float[]{u0, v0, u1, v0, u1, v1, u0, v1}, 0, tex, f * 8, 8);
            int a = f * 4, b = a + 1, c = a + 2, e = a + 3;
            System.arraycopy(new int[]{a, a, e, e, c, c, a, a, c, c, b, b}, 0, idx, fi, 12);
            fi += 12;
        }
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(tex);
        mesh.getFaces().setAll(idx);
        MeshView v = new MeshView(mesh);
        v.setCullFace(CullFace.NONE);
        return v;
    }

    // ------------------------------------------------------------------ cape texture (any thread)

    /** Loads and upscales a cape texture; null if it can't be read. Blocks: call off the FX thread. */
    static CapeTexture prepareCape(String url) {
        try {
            Image src = new Image(url, false);   // synchronous
            if (src.isError() || src.getWidth() <= 0) return null;
            return prepareCape(src);
        } catch (Exception e) {
            return null;
        }
    }

    static CapeTexture prepareCape(Image src) {
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        PixelReader pr = src.getPixelReader();
        if (pr == null || w <= 0 || h <= 0) return null;
        int f = Math.max(1, (int) Math.ceil(CAPE_TEX_TARGET / (double) w));
        f = Math.min(f, 32);
        WritableImage out = new WritableImage(w * f, h * f);
        var pw = out.getPixelWriter();
        for (int y = 0; y < h * f; y++) {
            for (int x = 0; x < w * f; x++) pw.setArgb(x, y, pr.getArgb(x / f, y / f));
        }
        return new CapeTexture(out, faceRects(w, h));
    }

    /** Standard cape sheet (64x32 or 22x17, any HD multiple) -> its six faces; anything else -> stretched whole. */
    static double[][] faceRects(int w, int h) {
        double ratio = w / (double) h;
        double tw, th;
        if (Math.abs(ratio - 2.0) < 0.05) { tw = 64; th = 32; }
        else if (Math.abs(ratio - 22.0 / 17.0) < 0.05) { tw = 22; th = 17; }
        else {
            double[] whole = {0, 0, 1, 1};
            return new double[][]{whole, whole, whole, whole, whole, whole};
        }
        return new double[][]{
                rect(12, 1, 10, 16, tw, th),   // -Z: inside (against the body)
                rect(1, 1, 10, 16, tw, th),    // +Z: the visible design
                rect(0, 1, 1, 16, tw, th),     // -X
                rect(11, 1, 1, 16, tw, th),    // +X
                rect(1, 0, 10, 1, tw, th),     // top
                rect(11, 0, 10, 1, tw, th),    // bottom
        };
    }

    private static double[] rect(double x, double y, double w, double h, double tw, double th) {
        return new double[]{x / tw, y / th, w / tw, h / th};
    }
}
