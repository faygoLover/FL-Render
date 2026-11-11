package faygolover.flrender.api;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Лучи фонарей других модов для настоящего света фонаря (техника {@code flrender.flashlight}, без шейдерпака): блоки в
 * луче освещаются при рисовании, сквозь стены свет не идёт, сущности получают уровень света как от факела. Каждый
 * луч — свой цвет и своя форма. Вместе с лучами фонарей Survivor's Arsenal — до {@link #MAX}: свои (камеры) первыми,
 * остальные по расстоянию. Только клиентский код. @since 0.4.0
 */
public final class LightBeams {
    private LightBeams() {}

    /** Сколько лучей освещает кадр. */
    public static final int MAX = 8;

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    /** Источник лучей: раз за кадр (до рисования мира) кладёт в {@code out} лучи, которые сейчас светят. */
    @FunctionalInterface
    public interface Source {
        /**
         * @param partial доля тика
         * @param cam     камера
         * @param dt      реальные секунды с прошлого кадра (0 — первый кадр), для плавного поворота луча
         */
        void collect(Minecraft mc, float partial, Vec3 cam, float dt, List<Spec> out);
    }

    /**
     * Один луч. Позиция и направление — в мире (направление единичное). Конусы — косинусы половинных углов: ядро
     * (in, out), спад, широкий ореол; веса спада и ореола относительно ядра. Цвет — 0..1, мягкий. {@code owner} — id
     * сущности, у которой лампа (её рука освещается как у факела рядом). {@code local} — фонарь камеры.
     */
    public record Spec(double x, double y, double z, double dx, double dy, double dz, float range, float intensity,
                       float coreIn, float coreOut, float spillIn, float spillOut, float washIn, float washOut,
                       float spillWeight, float washWeight, float r, float g, float b, int owner, boolean local) {

        /** Лампа во все стороны (керосиновая): шар радиуса {@code range}. */
        public static Spec point(double x, double y, double z, float range, float intensity, float r, float g, float b,
                                 int owner, boolean local) {
            return new Spec(x, y, z, 0.0, 1.0, 0.0, range, intensity, -0.999f, -1.0f, -0.999f, -1.0f, -0.999f, -1.01f,
                    0.0f, 0.0f, r, g, b, owner, local);
        }
    }

    /** Косинус половинного угла в градусах — для конусов {@link Spec}. */
    public static float cos(float degrees) {
        return Mth.cos(degrees * Mth.DEG_TO_RAD);
    }

    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** Для FL Render. */
    public static List<Source> sources() {
        return SOURCES;
    }
}
