package faygolover.flrender.flashlight;

import net.minecraft.util.Mth;

/**
 * Один луч фонаря на этот кадр: откуда (мир), куда (единичный вектор), дальность, сила и форма пятна —
 * три вложенных конуса как у Survivor's Arsenal (ядро, спад, широкий ореол; косинусы углов), цвет лампы.
 * Свет в точке — {@link #amount}: та же формула, что в шейдере чанков ({@code ChunkPatch}). {@code owner} — id сущности,
 * у которой фонарь в руке. Лампа во все стороны (керосинка) — все косинусы около -1.
 */
public record Beam(double x, double y, double z, double dx, double dy, double dz, float range, float intensity,
                   float coreIn, float coreOut, float spillIn, float spillOut, float washIn, float washOut,
                   float spillWeight, float washWeight, float r, float g, float b, int owner) {

    /** Сила света вблизи (до затухания): около 1 — как от факела рядом. */
    public static final float GAIN = 1.6f;
    /** Затухание с расстоянием: 1 / (1 + k d²). */
    public static final float FALLOFF = 0.025f;
    /** Затухание до нуля — от этой доли дальности до полной. */
    public static final float FADE_START = 0.62f;

    /** Свет луча в точке мира (без учёта стен и наклона поверхности), 0 — вне конуса. */
    public float amount(double px, double py, double pz) {
        double vx = px - x, vy = py - y, vz = pz - z;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 1.0e-4 || dist >= range) return 0.0f;
        float c = (float) ((vx * dx + vy * dy + vz * dz) / dist);
        if (c <= washOut) return 0.0f;
        return shape(c) * atten((float) dist) * intensity * GAIN;
    }

    float shape(float c) {
        float core = smooth(coreOut, coreIn, c);
        float spill = smooth(spillOut, spillIn, c);
        float wash = smooth(washOut, washIn, c);
        return (core + spill * spillWeight + wash * washWeight) / (1.0f + spillWeight + washWeight);
    }

    float atten(float d) {
        return 1.0f / (1.0f + FALLOFF * d * d) * (1.0f - smooth(range * FADE_START, range, d)) * smooth(0.05f, 0.5f, d);
    }

    static float smooth(float e0, float e1, float v) {
        float t = Mth.clamp((v - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
