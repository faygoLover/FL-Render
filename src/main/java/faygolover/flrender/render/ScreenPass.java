package faygolover.flrender.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;

/** Полноэкранные проходы: один квад на всю цель с шейдером, какими бы ни были матрицы в этот момент. */
public final class ScreenPass {
    private ScreenPass() {}

    /** {@code target} размером с {@code main} (создаёт или меняет размер), линейная фильтрация. */
    public static TextureTarget fit(@Nullable TextureTarget target, RenderTarget main) {
        return fit(target, main, GL11.GL_LINEAR);
    }

    public static TextureTarget fit(@Nullable TextureTarget target, RenderTarget main, int filter) {
        if (target == null) {
            target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            target.setFilterMode(filter);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
            target.setFilterMode(filter);
        }
        return target;
    }

    /** Цвет {@code from} в {@code to} (того же размера) — кадр, который нельзя читать, пока в него рисуют. */
    public static void copyColor(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /** Один квад на всю {@code target} с {@code shader} (без теста глубины; смешивание как задано, если {@code blend}). */
    public static void fullscreen(RenderTarget target, ShaderInstance shader, boolean blend) {
        target.bindWrite(true);
        RenderSystem.disableDepthTest();
        if (!blend) RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
        quad(DefaultVertexFormat.POSITION_TEX, 0.0f);
    }

    /** Чёрный квад на всю привязанную цель с этой прозрачностью (текущий шейдер и смешивание). */
    public static void black(float alpha) {
        quad(DefaultVertexFormat.POSITION_COLOR, alpha);
    }

    private static void quad(VertexFormat format, float alpha) {
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, format);
        float[][] corners = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
        for (float[] c : corners) {
            buffer.vertex(c[0], c[1], 0.0);
            if (format == DefaultVertexFormat.POSITION_TEX) buffer.uv(c[2], c[3]);
            else buffer.color(0.0f, 0.0f, 0.0f, alpha);
            buffer.endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
        mv.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
    }
}
