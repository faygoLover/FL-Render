package faygolover.flrender.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Technique;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Цветной свет, который эффекты бросают на мир вокруг (аномалии, порталы, ...), — только картинка: уровни света мира
 * не меняются. Моды отдают источники ({@link #provider}), библиотека рисует их все одним проходом по глубине:
 * каждый источник подсвечивает поверхности в своём радиусе своим цветом, тем сильнее, чем больше они к нему повёрнуты.
 * Один раз за кадр — когда кадр мира готов ({@link LatePass}): без пака сразу после сущностей, до эффектов (освещённые
 * потом, огненные шары выбеливались); с паком — по его готовому кадру. Рука не освещается.
 * <p>
 * Техника {@code flrender.world_light} (со «Среднего»); ближайших источников: среднее 4, высокое 8, ультра 12.
 */
public final class WorldLight {
    private WorldLight() {}

    /** Источник света: точка или прямоугольный проём. */
    public interface Sink {
        /** Точка: радиус {@code reach} блоков, цвет 0–1, сила (около 1 — заметно, 1.5 — ярко). */
        void point(Vec3 pos, float reach, float r, float g, float b, float strength);

        /**
         * Вертикальный прямоугольник (проём портала): {@code bottomCentre} — середина нижнего края, {@code normal} —
         * горизонтальная нормаль лицевой стороны; за лицом свет слабее, если не {@code bothSides}.
         */
        void rect(Vec3 bottomCentre, Vec3 normal, float width, float height, boolean bothSides,
                  float reach, float r, float g, float b, float strength);
    }

    /** Мод, у которого есть источники: раз за кадр отдаёт их в {@code sink} (все, библиотека выберет ближайшие). */
    public interface Provider {
        void collect(float partialTick, Vec3 camera, Sink sink);
    }

    public static final Technique TECHNIQUE = FlRender.technique(FlRender.MODID, "world_light", Quality.MEDIUM);

    private static final int MAX_LIGHTS = 12;
    private static final int STRIDE = 12;
    private static final double MAX_DISTANCE = 48.0;
    /** С паком рука в середине диапазона глубины: ближе этого — рука. */
    private static final float PACK_HAND_DEPTH = 0.75f;

    private record Light(Vec3 pos, float reach, float r, float g, float b, float strength,
                         float nx, float nz, float width, float height, boolean bothSides, double dist2) {
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    @Nullable
    private static TextureTarget scene;
    @Nullable
    private static TextureTarget layer;
    @Nullable
    private static Matrix4f view;
    @Nullable
    private static Matrix4f projection;

    public static void init() {
        // Техника регистрируется при загрузке класса — до того, как строится вкладка настроек.
    }

    public static void provider(Provider p) {
        PROVIDERS.add(p);
    }

    /** Сколько ближайших источников рисовать сейчас (0 — техника выключена). */
    public static int budget() {
        if (!TECHNIQUE.on()) return 0;
        return switch (FlRender.quality()) {
            case LOW, MEDIUM -> 4;
            case HIGH -> 8;
            case ULTRA -> MAX_LIGHTS;
        };
    }

    static void capture(Matrix4f pose, Matrix4f proj) {
        view = new Matrix4f(pose);
        projection = new Matrix4f(proj);
    }

    static void draw(float partial, boolean pack) {
        int budget = budget();
        if (budget == 0 || view == null || projection == null || PROVIDERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ShaderInstance shader = FlShaders.worldLight();
        if (shader == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        List<Light> lights = collect(partial, cam);
        if (lights.isEmpty()) return;

        float[] data = new float[MAX_LIGHTS * STRIDE];
        // Ночью ярче, днём мягче (днём солнце его перебивает).
        float day = mc.level.getSkyDarken(partial);
        float dim = Mth.clamp(1.3f - 0.8f * day, 0.45f, 1.2f);
        int n = Math.min(budget, lights.size());
        for (int i = 0; i < n; i++) {
            Light l = lights.get(i);
            int k = i * STRIDE;
            data[k] = (float) (l.pos.x - cam.x);
            data[k + 1] = (float) (l.pos.y - cam.y);
            data[k + 2] = (float) (l.pos.z - cam.z);
            data[k + 3] = l.reach;
            data[k + 4] = l.r;
            data[k + 5] = l.g;
            data[k + 6] = l.b;
            data[k + 7] = l.strength * dim;
            data[k + 8] = l.nx * l.height;
            data[k + 9] = 0.0f;
            data[k + 10] = l.nz * l.height;
            data[k + 11] = l.bothSides ? -l.width : l.width;
        }

        RenderTarget main = mc.getMainRenderTarget();
        scene = ScreenPass.fit(scene, main);
        layer = ScreenPass.fit(layer, main);
        ScreenPass.copyColor(main, scene);
        layer.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        layer.clear(Minecraft.ON_OSX);

        shader.setSampler("DepthSampler", main.getDepthTextureId());
        shader.setSampler("SceneSampler", scene.getColorTextureId());
        shader.safeGetUniform("InvProjMat").set(new Matrix4f(projection).invert());
        shader.safeGetUniform("InvViewMat").set(new Matrix4f(view).invert());
        shader.safeGetUniform("LightData").set(data);
        shader.safeGetUniform("LightCount").set(n);
        shader.safeGetUniform("HandDepth").set(pack ? PACK_HAND_DEPTH : 0.0f);
        ScreenPass.fullscreen(layer, shader, false);

        // Освещённые пиксели поверх кадра (остальное в слое прозрачное).
        ShaderInstance blit = GameRenderer.getPositionTexShader();
        if (blit != null) {
            RenderSystem.setShaderTexture(0, layer.getColorTextureId());
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            ScreenPass.fullscreen(main, blit, true);
            RenderSystem.disableBlend();
        }
        main.bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private static List<Light> collect(float partial, Vec3 cam) {
        List<Light> out = new ArrayList<>();
        Sink sink = new Sink() {
            @Override
            public void point(Vec3 pos, float reach, float r, float g, float b, float strength) {
                double d2 = pos.distanceToSqr(cam);
                if (d2 > MAX_DISTANCE * MAX_DISTANCE || strength <= 0.0f) return;
                out.add(new Light(pos, reach, r, g, b, strength, 0, 0, 0, 0, false, d2));
            }

            @Override
            public void rect(Vec3 bottomCentre, Vec3 normal, float width, float height, boolean bothSides,
                             float reach, float r, float g, float b, float strength) {
                Vec3 centre = bottomCentre.add(0, height / 2.0, 0);
                double d2 = centre.distanceToSqr(cam);
                double far = MAX_DISTANCE + Math.max(width, height);
                if (d2 > far * far || strength <= 0.0f) return;
                double len = Math.sqrt(normal.x * normal.x + normal.z * normal.z);
                if (len < 1.0e-4) {
                    point(centre, reach + height / 2f, r, g, b, strength);
                    return;
                }
                out.add(new Light(bottomCentre, reach, r, g, b, strength, (float) (normal.x / len), (float) (normal.z / len),
                        Math.max(width, 0.01f), Math.max(height, 0.01f), bothSides, d2));
            }
        };
        for (Provider p : PROVIDERS) {
            try {
                p.collect(partial, cam, sink);
            } catch (RuntimeException e) {
                FlRenderMod.LOGGER.error("FL Render: источник цветного света упал", e);
            }
        }
        out.sort(Comparator.comparingDouble(Light::dist2));
        return out;
    }
}
