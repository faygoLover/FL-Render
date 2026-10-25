package faygolover.flrender.mixin;

import faygolover.flrender.flashlight.Flashlight;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Свет фонаря: блок-сущность (сундук, табличка, ...) в луче освещена, как будто рядом факел. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @ModifyArg(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"),
            index = 4)
    private static int flrender$flashlight(BlockEntity blockEntity, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           net.minecraft.client.renderer.MultiBufferSource buffers, int packed, int overlay) {
        int level = Flashlight.blockEntityLight(blockEntity.getBlockPos());
        if (level < 0 || LightTexture.block(packed) >= level) return packed;
        return LightTexture.pack(level, LightTexture.sky(packed));
    }
}
