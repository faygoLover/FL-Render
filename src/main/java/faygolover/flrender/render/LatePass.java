package faygolover.flrender.render;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.client.Oculus;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Рисование эффектов, когда включён шейдерпак (Oculus).
 * <p>
 * Пока пак рисует мир, он подменяет ванильные шейдеры своими программами: свечения освещаются как кожа моба, плавный
 * градиент вершин становится одним цветом на треугольник, искажению воздуха нечего копировать, а свой шейдер мода
 * гасится совсем. Поэтому с паком зарегистрированные обработчики ({@link #register}) в проходе мира ничего не рисуют
 * ({@link #deferred} = true): каждый этап мира только запоминает свои матрицы и туман, а на {@code AFTER_LEVEL} — после
 * финального прохода пака, до руки и интерфейса — обработчики вызываются снова, этап за этапом, по готовому кадру,
 * с ванильными (и своими) шейдерами и тестом глубины мира. Без пака ничего не меняется: этапы рисуются как идут.
 * <p>
 * Раз за кадр, когда кадр мира готов (после сущностей без пака; в начале позднего прохода с паком): глубина сцены
 * ({@link SceneDepth}) → цветной свет ({@link WorldLight}) → хуки модов ({@link #onFrameReady}).
 */
public final class LatePass {
    private LatePass() {}

    /** Рисование сущности, отложенное в поздний проход. */
    public interface EntityDraw {
        void draw(PoseStack poseStack, MultiBufferSource.BufferSource buffers);
    }

    /** Кадр мира готов: {@code pack} — это готовый кадр шейдерпака; {@code projection} — проекция мира. */
    public interface FrameHook {
        void ready(float partialTick, boolean pack, Matrix4f projection);
    }

    private record Snapshot(RenderLevelStageEvent.Stage stage, LevelRenderer levelRenderer, Matrix4f pose, Matrix4f projection,
                            Matrix4f modelView, int renderTick, float partialTick, Camera camera, Frustum frustum,
                            float fogStart, float fogEnd, float[] fogColor, FogShape fogShape) {
    }

    private record DeferredEntity(Entity entity, float partialTick, EntityDraw draw) {
    }

    /** Повторённые этапы — отличаются от настоящих классом. */
    private static final class Replay extends RenderLevelStageEvent {
        Replay(Snapshot s) {
            super(s.stage(), s.levelRenderer(), pose(s.pose()), new Matrix4f(s.projection()), s.renderTick(), s.partialTick(),
                    s.camera(), s.frustum());
        }

        private static PoseStack pose(Matrix4f m) {
            PoseStack poseStack = new PoseStack();
            poseStack.last().pose().set(m);
            return poseStack;
        }
    }

    private static final List<Consumer<RenderLevelStageEvent>> RENDERERS = new CopyOnWriteArrayList<>();
    private static final List<FrameHook> HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Snapshot> SNAPSHOTS = new ArrayList<>();
    private static final List<DeferredEntity> ENTITIES = new ArrayList<>();
    /** В этом кадре мир рисует шейдерпак. */
    private static boolean active;
    /** Отложенные сущности рисуются по-настоящему. */
    private static boolean drawingEntities;

    /**
     * Обработчик этапов мира, который с шейдерпаком надо повторить в позднем проходе. Он же подписан на
     * {@link RenderLevelStageEvent} как обычно и в начале проверяет {@link #deferred}. Порядок повтора — порядок регистрации.
     */
    public static void register(Consumer<RenderLevelStageEvent> renderer) {
        RENDERERS.add(renderer);
    }

    /** Хук «кадр мира готов» (после глубины и цветного света). */
    public static void onFrameReady(FrameHook hook) {
        HOOKS.add(hook);
    }

    /** Шейдерпак рисует мир в этом кадре (будет поздний проход). */
    public static boolean active() {
        return active;
    }

    /** Настоящий этап мира при включённом паке: сейчас ничего не рисовать — этап повторится в позднем проходе. */
    public static boolean deferred(RenderLevelStageEvent event) {
        return active && !(event instanceof Replay);
    }

    /** Этап, повторённый в позднем проходе. */
    public static boolean replayed(RenderLevelStageEvent event) {
        return event instanceof Replay;
    }

    /**
     * Для рендерера сущности: с паком запоминает рисование для позднего прохода и возвращает true (сейчас не рисовать).
     * Поза, которую получит {@code draw}, — у ног сущности, как у самого рендерера; во время вызова вернёт false.
     */
    public static boolean deferEntity(Entity entity, float partialTick, EntityDraw draw) {
        if (!active || drawingEntities) return false;
        ENTITIES.add(new DeferredEntity(entity, partialTick, draw));
        return true;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event instanceof Replay) return;
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            SNAPSHOTS.clear();
            ENTITIES.clear();
            active = Oculus.shaderPack();
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            WorldLight.capture(event.getPoseStack().last().pose(), projection);
            if (!active) frameReady(event.getPartialTick(), false, projection);
        }
        if (!active) return;
        if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            replay();
            return;
        }
        SNAPSHOTS.add(new Snapshot(stage, event.getLevelRenderer(), new Matrix4f(event.getPoseStack().last().pose()),
                new Matrix4f(RenderSystem.getProjectionMatrix()), new Matrix4f(RenderSystem.getModelViewMatrix()),
                event.getRenderTick(), event.getPartialTick(), event.getCamera(), event.getFrustum(),
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogColor().clone(),
                RenderSystem.getShaderFogShape()));
    }

    private static void frameReady(float partialTick, boolean pack, Matrix4f projection) {
        SceneDepth.capture(projection);
        WorldLight.draw(partialTick, pack);
        for (FrameHook h : HOOKS) {
            try {
                h.ready(partialTick, pack, projection);
            } catch (RuntimeException e) {
                FlRenderMod.LOGGER.error("FL Render: хук готового кадра упал", e);
            }
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
        }
    }

    private static void replay() {
        if (SNAPSHOTS.isEmpty()) {
            ENTITIES.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.getMainRenderTarget().bindWrite(true);
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        float oldFogStart = RenderSystem.getShaderFogStart();
        float oldFogEnd = RenderSystem.getShaderFogEnd();
        float[] oldFogColor = RenderSystem.getShaderFogColor().clone();
        FogShape oldFogShape = RenderSystem.getShaderFogShape();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        try {
            Snapshot world = SNAPSHOTS.get(SNAPSHOTS.size() - 1);
            frameReady(SNAPSHOTS.get(0).partialTick(), true, world.projection());
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            for (Snapshot s : SNAPSHOTS) {
                RenderSystem.setProjectionMatrix(s.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
                modelView.setIdentity();
                modelView.mulPoseMatrix(s.modelView());
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setShaderFogStart(s.fogStart());
                RenderSystem.setShaderFogEnd(s.fogEnd());
                RenderSystem.setShaderFogColor(s.fogColor()[0], s.fogColor()[1], s.fogColor()[2], s.fogColor()[3]);
                RenderSystem.setShaderFogShape(s.fogShape());
                if (s.stage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) drawEntities(s);
                for (Consumer<RenderLevelStageEvent> renderer : RENDERERS) {
                    try {
                        renderer.accept(new Replay(s));
                    } catch (RuntimeException e) {
                        FlRenderMod.LOGGER.error("FL Render: обработчик позднего прохода упал", e);
                    }
                }
            }
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            RenderSystem.setShaderFogStart(oldFogStart);
            RenderSystem.setShaderFogEnd(oldFogEnd);
            RenderSystem.setShaderFogColor(oldFogColor[0], oldFogColor[1], oldFogColor[2], oldFogColor[3]);
            RenderSystem.setShaderFogShape(oldFogShape);
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            SNAPSHOTS.clear();
            ENTITIES.clear();
        }
    }

    /** Отложенные сущности — там, где ваниль рисовала сущности. */
    private static void drawEntities(Snapshot s) {
        if (ENTITIES.isEmpty()) return;
        Vec3 cam = s.camera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack poseStack = Replay.pose(s.pose());
        drawingEntities = true;
        try {
            for (DeferredEntity d : ENTITIES) {
                if (d.entity().isRemoved()) continue;
                Vec3 p = d.entity().getPosition(d.partialTick());
                poseStack.pushPose();
                poseStack.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                try {
                    d.draw().draw(poseStack, buffers);
                } catch (RuntimeException e) {
                    FlRenderMod.LOGGER.error("FL Render: отложенная сущность не нарисовалась", e);
                }
                poseStack.popPose();
            }
        } finally {
            drawingEntities = false;
        }
        buffers.endBatch();
    }
}
