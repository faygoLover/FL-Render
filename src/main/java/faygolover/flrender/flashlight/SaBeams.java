package faygolover.flrender.flashlight;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Лучи фонарей Survivor's Arsenal ({@code sa_combat:flashlight}) — так же, как их собирает сам мод
 * ({@code FlashlightBeams}/{@code FlashlightBeam.resolve}): включённый фонарь в любой руке у игроков ближе 72 блоков,
 * (порядок и лимит — {@link Flashlight}); место лампы у руки, режим (дальний/ближний), заряд, под водой короче;
 * направление догоняет взгляд с той же задержкой. Свой расчёт, а не вызов мода: у мода он считает задержку по времени
 * между вызовами (второй вызов за кадр сбил бы её), а RP Fixes прячет у него лучи, фонаря которых камера не видит, —
 * настоящему свету они нужны (свет из-за угла). Методы предмета SA — через MethodHandle (его классы тянут GeckoLib).
 */
final class SaBeams {
    private SaBeams() {}

    private static final Class<?> ITEM;
    private static final MethodHandle VISIBLE, HIGH, BATTERY;

    static {
        try {
            ITEM = Class.forName("com.ogaba.sa_survival.item.common.FlashlightItem");
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            VISIBLE = l.findStatic(ITEM, "isLightOutputVisible", MethodType.methodType(boolean.class, ItemStack.class, int.class));
            HIGH = l.findStatic(ITEM, "isHighMode", MethodType.methodType(boolean.class, ItemStack.class));
            BATTERY = l.findStatic(ITEM, "getBatteryPercent", MethodType.methodType(float.class, ItemStack.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Survivor's Arsenal FlashlightItem", e);
        }
    }

    private static boolean visible(ItemStack s, int tick) {
        try {
            return ITEM.isInstance(s.getItem()) && (boolean) VISIBLE.invokeExact(s, tick);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static boolean high(ItemStack s) {
        try {
            return (boolean) HIGH.invokeExact(s);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static float battery(ItemStack s) {
        try {
            return (float) BATTERY.invokeExact(s);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static final double MAX_SOURCE_DISTANCE = 72.0;
    private static final float AIM_LAG = 0.055f;

    private static final Map<UUID, double[]> AIM = new HashMap<>();

    record Found(Beam beam, boolean local, double distSqr) {
    }

    static void collect(Minecraft mc, float partial, Vec3 cam, float dt, List<Found> found) {
        if (mc.level == null || mc.player == null) {
            AIM.clear();
            return;
        }
        boolean firstPersonView = mc.options.getCameraType().isFirstPerson();
        Set<UUID> seen = new HashSet<>();
        for (Player p : mc.level.players()) {
            if (p == null || p.isSpectator()) continue;
            boolean local = p == mc.player;
            if (!local && p.position().distanceToSqr(cam) > MAX_SOURCE_DISTANCE * MAX_SOURCE_DISTANCE) continue;
            ItemStack stack = lit(p);
            if (stack == null) continue;
            seen.add(p.getUUID());
            Beam b = resolve(p, stack, local && firstPersonView, partial, dt);
            found.add(new Found(b, local, cam.distanceToSqr(b.x(), b.y(), b.z())));
        }
        AIM.keySet().retainAll(seen);
    }

    private static ItemStack lit(Player p) {
        ItemStack main = p.getMainHandItem();
        if (visible(main, p.tickCount)) return main;
        ItemStack off = p.getOffhandItem();
        if (visible(off, p.tickCount)) return off;
        return null;
    }

    private static Beam resolve(Player p, ItemStack stack, boolean firstPerson, float partial, float dt) {
        Vec3 eye = p.getEyePosition(partial);
        Vec3 aim = smooth(p.getUUID(), p.getViewVector(partial).normalize(), dt);
        boolean offhand = p.getOffhandItem() == stack;
        Vec3 right = aim.cross(new Vec3(0.0, 1.0, 0.0));
        right = right.lengthSqr() < 1.0e-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
        double side = (p.getMainArm() == HumanoidArm.RIGHT) != offhand ? 1.0 : -1.0;
        Vec3 o = firstPerson
                ? eye.add(right.scale(0.1 * side)).add(0.0, -0.09, 0.0).add(aim.scale(0.24))
                : eye.add(right.scale(0.34 * side)).add(0.0, -0.3, 0.0).add(aim.scale(0.2));
        boolean high = high(stack);
        float coreIn = 23.0f, coreOut = 30.0f, spillIn = 32.0f;
        float spillOut = high ? 42.0f : 41.0f;
        float washIn = high ? 42.0f : 41.0f;
        float washOut = high ? 53.0f : 50.0f;
        float range = high ? 38.0f : 20.0f;
        float intensity = high ? 1.0f : 0.62f;
        float spillW = high ? 0.34f : 0.28f;
        float washW = high ? 0.18f : 0.15f;
        intensity *= Mth.clamp(0.55f + battery(stack) * 1.3f, 0.55f, 1.0f);
        if (p.isUnderWater()) {
            range *= 0.55f;
            coreOut *= 0.88f;
            spillOut *= 0.8f;
            washOut *= 0.7f;
            intensity *= 0.85f;
        }
        return new Beam(o.x, o.y, o.z, aim.x, aim.y, aim.z, range, intensity,
                cos(coreIn), cos(coreOut), cos(spillIn), cos(spillOut), cos(washIn), cos(washOut), spillW, washW,
                Flashlight.R, Flashlight.G, Flashlight.B, p.getId());
    }

    private static float cos(float deg) {
        return Mth.cos(deg * Mth.DEG_TO_RAD);
    }

    private static Vec3 smooth(UUID id, Vec3 look, float dt) {
        double[] s = AIM.get(id);
        if (s == null || dt <= 0.0f) {
            AIM.put(id, new double[]{look.x, look.y, look.z});
            return look;
        }
        double follow = 1.0 - Math.exp(-dt / AIM_LAG);
        s[0] += (look.x - s[0]) * follow;
        s[1] += (look.y - s[1]) * follow;
        s[2] += (look.z - s[2]) * follow;
        Vec3 v = new Vec3(s[0], s[1], s[2]);
        return v.lengthSqr() < 1.0e-8 ? look : v.normalize();
    }
}
