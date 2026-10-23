package faygolover.flrender.render;

import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.flrender.FlRenderMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.util.List;
import java.util.function.Consumer;

/**
 * Свой постэффект (цепочка {@code shaders/post/*.json}) поверх кадра — не занимает слот постэффекта игры (его держат
 * другие моды). Создаётся при первом вызове и при смене размера окна. Вызывать там, где нужен: на
 * {@code AFTER_LEVEL} (после кадра шейдерпака, до руки) или в оверлее (поверх руки). Если цепочка не загрузилась —
 * больше не пытается, игра не падает.
 */
public final class PostFx {
    private final ResourceLocation id;
    private PostChain chain;
    private int w = -1, h = -1;
    private boolean failed;

    public PostFx(ResourceLocation chainJson) {
        this.id = chainJson;
    }

    /**
     * Прогнать цепочку по основному кадру. {@code uniforms} получает шейдер каждого прохода (по порядку) — задать
     * свои значения. Возвращает false, если цепочка не работает.
     */
    public boolean process(float partialTick, Consumer<EffectInstance> uniforms) {
        if (failed) return false;
        Minecraft mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        try {
            if (chain == null || main.width != w || main.height != h) {
                if (chain != null) chain.close();
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, id);
                chain.resize(main.width, main.height);
                w = main.width;
                h = main.height;
            }
            List<PostPass> passes = ObfuscationReflectionHelper.getPrivateValue(PostChain.class, chain, "f_110009_");
            if (passes != null) for (PostPass p : passes) uniforms.accept(p.getEffect());
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partialTick);
            main.bindWrite(false);
            RenderSystem.enableDepthTest();
            return true;
        } catch (Exception ex) {
            failed = true;
            FlRenderMod.LOGGER.error("FL Render: постэффект {} не загрузился", id, ex);
            return false;
        }
    }

    public void close() {
        if (chain != null) chain.close();
        chain = null;
        w = h = -1;
    }
}
