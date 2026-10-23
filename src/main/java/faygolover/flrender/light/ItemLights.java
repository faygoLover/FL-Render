package faygolover.flrender.light;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Свет от предметов: в руках (у игроков и мобов), лежащих на земле и летящих (брошенная ХИС). Цвет и сила — из
 * {@link ColorTable} (предмет-блок берёт цвет блока). С шейдерпаком не светит — это делает сам пак.
 */
final class ItemLights {
    private ItemLights() {}

    private static final double MAX_DISTANCE = 48.0;

    static void collect(float partial, Vec3 cam, List<LightJobs.Dyn> out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.isRemoved() || e.isInvisible() && !(e instanceof ItemEntity)) continue;
            Vec3 p = e.getPosition(partial);
            if (p.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) continue;
            if (e instanceof LivingEntity living) {
                ItemStack main = living.getMainHandItem(), off = living.getOffhandItem();
                ColorTable.Entry a = ColorTable.of(main), b = ColorTable.of(off);
                ColorTable.Entry best = a == null ? b : b == null ? a : a.level() >= b.level() ? a : b;
                if (best != null) add(out, p.add(0, e.getBbHeight() * 0.6, 0), best);
            } else if (e instanceof ItemEntity item) {
                ColorTable.Entry en = ColorTable.of(item.getItem());
                if (en != null) add(out, p.add(0, 0.25, 0), en);
            } else if (e instanceof ThrowableItemProjectile thrown) {
                ColorTable.Entry en = ColorTable.of(thrown.getItem());
                if (en != null) add(out, p.add(0, e.getBbHeight() / 2, 0), en);
            }
        }
    }

    private static void add(List<LightJobs.Dyn> out, Vec3 p, ColorTable.Entry e) {
        out.add(new LightJobs.Dyn(p.x, p.y, p.z, Math.max(2, e.level()), e.r(), e.g(), e.b(), 0.9f, 0, 0, 0, 0, false));
    }
}
