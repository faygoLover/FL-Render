package faygolover.flrender.mixin;

import faygolover.flrender.flashlight.Flashlight;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свет фонаря: сущность в луче освещена, как будто рядом факел ({@link Flashlight#entityLight}). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void flrender$flashlight(T entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
        int level = Flashlight.entityLight(entity, partialTick);
        if (level < 0) return;
        int packed = cir.getReturnValueI();
        if (LightTexture.block(packed) >= level) return;
        cir.setReturnValue(LightTexture.pack(level, LightTexture.sky(packed)));
    }
}
