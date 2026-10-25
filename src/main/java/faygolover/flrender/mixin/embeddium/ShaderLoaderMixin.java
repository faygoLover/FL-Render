package faygolover.flrender.mixin.embeddium;

import faygolover.flrender.flashlight.ChunkPatch;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свет фонаря: дописать шейдер чанков Embeddium при загрузке ({@link ChunkPatch}). */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void flrender$patch(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String src = cir.getReturnValue();
        String out = ChunkPatch.patch(name, src);
        if (out != src) cir.setReturnValue(out);
    }
}
