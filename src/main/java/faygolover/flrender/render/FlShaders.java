package faygolover.flrender.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Свои шейдеры библиотеки. Шейдер, который драйвер не собрал, остаётся null — его проход пропускается, игра не падает.
 * Свои шейдеры мода Oculus гасит, пока шейдерпак рисует мир, — поэтому они работают только вне мира пака
 * (после сущностей без пака, в позднем проходе с паком).
 */
public final class FlShaders {
    private FlShaders() {}

    @Nullable
    private static ShaderInstance depthPack;
    @Nullable
    private static ShaderInstance worldLight;

    public static void onRegisterShaders(RegisterShadersEvent event) {
        register(event, "depth_pack", DefaultVertexFormat.POSITION_TEX, s -> depthPack = s);
        register(event, "world_light", DefaultVertexFormat.POSITION_TEX, s -> worldLight = s);
    }

    private static void register(RegisterShadersEvent event, String name, VertexFormat format, Consumer<ShaderInstance> onLoad) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation(FlRender.MODID, name), format), onLoad);
        } catch (Exception e) {
            FlRenderMod.LOGGER.error("FL Render: шейдер {} не загрузился, его проход выключен", name, e);
        }
    }

    @Nullable
    static ShaderInstance depthPack() {
        return depthPack;
    }

    @Nullable
    static ShaderInstance worldLight() {
        return worldLight;
    }
}
