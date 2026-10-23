package faygolover.flrender.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;

/**
 * Поворот камеры, как у мира (без «крена»). На этапе {@code AFTER_LEVEL} Forge 1.20.1 отдаёт в событии позу проекции
 * (локальный стек GameRenderer), а не вида камеры — свою позу надо строить самим.
 */
public final class CameraPose {
    private CameraPose() {}

    public static PoseStack of(Camera camera) {
        PoseStack pose = new PoseStack();
        pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        pose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180f));
        return pose;
    }
}
