package faygolover.flrender.light;

import faygolover.flrender.FlRenderMod;
import faygolover.flrender.render.WorldLight;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Подвижные источники кадра: эффекты модов ({@link WorldLight#provider}) и предметы ({@link ItemLights}). */
public final class LightSources {
    private LightSources() {}

    private static final double MAX_DISTANCE = 64.0;

    public static List<LightJobs.Dyn> collect(float partial, Vec3 cam, boolean effects, boolean items, float dim) {
        List<LightJobs.Dyn> out = new ArrayList<>();
        if (effects) {
            WorldLight.Sink sink = new WorldLight.Sink() {
                @Override
                public void point(Vec3 pos, float reach, float r, float g, float b, float strength) {
                    if (strength <= 0.0f || pos.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) return;
                    out.add(new LightJobs.Dyn(pos.x, pos.y, pos.z, level(reach), r, g, b, strength * dim, 0, 0, 0, 0, false));
                }

                @Override
                public void rect(Vec3 bottomCentre, Vec3 normal, float width, float height, boolean bothSides,
                                 float reach, float r, float g, float b, float strength) {
                    if (strength <= 0.0f || bottomCentre.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) return;
                    double len = Math.sqrt(normal.x * normal.x + normal.z * normal.z);
                    if (len < 1.0e-4 || width <= 0 || height <= 0) {
                        point(bottomCentre.add(0, height / 2.0, 0), reach, r, g, b, strength);
                        return;
                    }
                    out.add(new LightJobs.Dyn(bottomCentre.x, bottomCentre.y, bottomCentre.z, level(reach), r, g, b, strength * dim,
                            (float) (normal.x / len), (float) (normal.z / len), width, height, bothSides));
                }
            };
            for (WorldLight.Provider p : WorldLight.providers()) {
                try {
                    p.collect(partial, cam, sink);
                } catch (RuntimeException e) {
                    FlRenderMod.LOGGER.error("FL Render: источник цветного света упал", e);
                }
            }
        }
        if (items) ItemLights.collect(partial, cam, out);
        return out;
    }

    private static int level(float reach) {
        return Math.max(2, Math.min(15, Math.round(reach)));
    }
}
