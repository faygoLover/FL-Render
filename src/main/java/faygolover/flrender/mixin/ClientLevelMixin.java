package faygolover.flrender.mixin;

import faygolover.flrender.flashlight.Occupancy;
import faygolover.flrender.light.ColorVolume;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Цветной свет и свет фонаря узнают, что в мире поменялся блок или свет (загрузка чанка, пакет света). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void flrender$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        ColorVolume.blockChanged(pos);
        Occupancy.blockChanged(pos);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void flrender$sectionChanged(int x, int y, int z, CallbackInfo ci) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            ColorVolume.sectionChanged(x + dx, y + dy, z + dz);
            Occupancy.sectionChanged(x + dx, y + dy, z + dz);
        }
    }
}
