package faygolover.flrender.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Глубина кадра, которую шейдеры могут читать, пока рисуют в кадр: раз за кадр упакована в цветную текстуру (24 бита
 * в r, g, b; текстуру нельзя читать, пока в её же буфер рисуют) — и проекция мира, чтобы вернуть из неё расстояния.
 * Снимается, только если она кому-то нужна ({@link #need}): после сущностей (без шейдерпака) или в начале позднего
 * прохода (с паком). Распаковка в GLSL: {@code dot(texture(tex, uv).rgb, vec3(1.0, 1.0/255.0, 1.0/65025.0))}.
 */
public final class SceneDepth {
    private SceneDepth() {}

    private static final List<BooleanSupplier> NEEDS = new CopyOnWriteArrayList<>();

    @Nullable
    private static TextureTarget packed;
    private static boolean ready;
    private static final Matrix4f INV_PROJECTION = new Matrix4f();

    /** Мод говорит, когда ему нужна глубина в этом кадре (например, пока включены мягкие края). */
    public static void need(BooleanSupplier whenNeeded) {
        NEEDS.add(whenNeeded);
    }

    static void capture(Matrix4f projection) {
        ready = false;
        INV_PROJECTION.set(projection).invert();
        boolean needed = false;
        for (BooleanSupplier s : NEEDS) {
            if (s.getAsBoolean()) {
                needed = true;
                break;
            }
        }
        ShaderInstance pack = FlShaders.depthPack();
        if (!needed || pack == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        packed = ScreenPass.fit(packed, main, GL11.GL_NEAREST);
        pack.setSampler("DepthSampler", main.getDepthTextureId());
        ScreenPass.fullscreen(packed, pack, false);
        main.bindWrite(true);
        ready = true;
    }

    /** Есть ли глубина в этом кадре. */
    public static boolean ready() {
        return ready && packed != null;
    }

    /** Текстура упакованной глубины этого кадра или 0. */
    public static int texture() {
        return ready() ? packed.getColorTextureId() : 0;
    }

    /** Обратная проекция мира этого кадра (копия). */
    public static Matrix4f invProjection() {
        return new Matrix4f(INV_PROJECTION);
    }

    public static int width() {
        return Minecraft.getInstance().getMainRenderTarget().width;
    }

    public static int height() {
        return Minecraft.getInstance().getMainRenderTarget().height;
    }
}
