package faygolover.flrender.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Technique;
import faygolover.flrender.light.ColorVolume;
import faygolover.flrender.light.LightSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Цветной свет — только картинка: уровни света мира не меняются.
 * <ul>
 *   <li>Блоки-источники (факелы, фонари, лава, ХИС, ...; цвета — {@code config/flrender_colors.json}) подкрашивают
 *       уже освещённое ванилью своим цветом.</li>
 *   <li>Подвижные источники — эффекты модов ({@link #provider}: аномалии, порталы) и предметы в руках, на земле,
 *       в полёте — добавляют свет своего цвета.</li>
 * </ul>
 * Свет растекается по блокам вокруг камеры, как ванильный: огибает углы, сквозь стены не проходит
 * ({@link ColorVolume}). Один проход по кадру — когда кадр мира готов ({@link LatePass}): без пака сразу после
 * сущностей, до эффектов; с паком — по его готовому кадру. Рука, вода, стекло и частицы не подсвечиваются.
 * <p>
 * Техники: {@code flrender.color_light} (блоки и эффекты, со «Среднего»), {@code flrender.held_light} (предметы,
 * со «Среднего»; с шейдерпаком не светит — пак делает это сам).
 */
public final class WorldLight {
    private WorldLight() {}

    /** Источник света: точка или прямоугольный проём. */
    public interface Sink {
        /** Точка: радиус {@code reach} блоков (до 15), цвет 0–1, сила (около 1 — заметно, 1.5 — ярко). */
        void point(Vec3 pos, float reach, float r, float g, float b, float strength);

        /**
         * Вертикальный прямоугольник (проём портала): {@code bottomCentre} — середина нижнего края, {@code normal} —
         * горизонтальная нормаль лицевой стороны; если не {@code bothSides}, светит только вперёд.
         */
        void rect(Vec3 bottomCentre, Vec3 normal, float width, float height, boolean bothSides,
                  float reach, float r, float g, float b, float strength);
    }

    /** Мод, у которого есть источники: раз за кадр отдаёт их в {@code sink}. */
    public interface Provider {
        void collect(float partialTick, Vec3 camera, Sink sink);
    }

    public static final Technique TECHNIQUE = FlRender.technique(FlRender.MODID, "color_light", Quality.MEDIUM);
    public static final Technique HELD = FlRender.technique(FlRender.MODID, "held_light", Quality.MEDIUM);

    /** С паком рука в середине диапазона глубины: ближе этого — рука. */
    private static final float PACK_HAND_DEPTH = 0.75f;
    /** Насколько цвет блоков-источников перекрашивает освещённое ими (1 — чистый цвет, как от светодиода). */
    private static final float TINT_STRENGTH = 0.95f;
    /** Яркость своего канала в перекрашенном (остальные гаснут — свет не кажется тусклее). */
    private static final float TINT_BOOST = 1.8f;

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
        // Техники регистрируются при загрузке класса — до того, как строится вкладка настроек.
    }

    public static void provider(Provider p) {
        PROVIDERS.add(p);
    }

    public static List<Provider> providers() {
        return PROVIDERS;
    }

    static void capture(Matrix4f pose, Matrix4f proj) {
        view = new Matrix4f(pose);
        projection = new Matrix4f(proj);
    }

    /** Сила ночного зрения у камеры (0–1): с ним добавка света пересвечивает — её ослабляем. */
    private static float nightVision(Minecraft mc, float partial) {
        if (!(mc.getCameraEntity() instanceof LivingEntity e) || !e.hasEffect(MobEffects.NIGHT_VISION)) return 0.0f;
        return Mth.clamp(GameRenderer.getNightVisionScale(e, partial), 0.0f, 1.0f);
    }

    static void draw(float partial, boolean pack) {
        boolean blocks = TECHNIQUE.on();
        boolean held = HELD.on() && !faygolover.flrender.client.Oculus.coloredPack();
        if (!blocks && !held || view == null || projection == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ShaderInstance shader = FlShaders.colorLight();
        if (shader == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        // Ночью ярче, днём мягче (днём солнце его перебивает).
        float darken = mc.level.getSkyDarken(partial);
        float dim = Mth.clamp(1.3f - 0.8f * darken, 0.45f, 1.2f);
        boolean ready;
        try {
            ready = ColorVolume.frame(cam, blocks, LightSources.collect(partial, cam, blocks, held, dim));
        } catch (RuntimeException e) {
            FlRenderMod.LOGGER.error("FL Render: цветной свет не обновился", e);
            return;
        }
        if (!ready) return;

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
        ColorVolume.uniforms(shader, cam);
        shader.safeGetUniform("Tint").set(blocks ? TINT_STRENGTH : 0.0f);
        shader.safeGetUniform("Boost").set(TINT_BOOST);
        shader.safeGetUniform("Day").set(Mth.clamp((darken - 0.2f) / 0.8f, 0.0f, 1.0f));
        shader.safeGetUniform("HandDepth").set(pack ? PACK_HAND_DEPTH : 0.0f);
        shader.safeGetUniform("NightVision").set(nightVision(mc, partial));
        ColorVolume.bind();
        ScreenPass.fullscreen(layer, shader, false);
        ColorVolume.unbind();

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
}
