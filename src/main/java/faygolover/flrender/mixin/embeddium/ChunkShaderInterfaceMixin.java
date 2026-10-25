package faygolover.flrender.mixin.embeddium;

import faygolover.flrender.flashlight.ChunkPatch;
import me.jellysquid.mods.sodium.client.gl.GlObject;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свет фонаря: места его значений в программе чанков и загрузка значений, когда программа включается. */
@Mixin(value = ChunkShaderInterface.class, remap = false)
public abstract class ChunkShaderInterfaceMixin {
    @Unique
    private int[] flrender$loc;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flrender$locate(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        if (context instanceof GlObject program) flrender$loc = ChunkPatch.locate(program.handle());
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void flrender$upload(CallbackInfo ci) {
        ChunkPatch.upload(flrender$loc);
    }
}
