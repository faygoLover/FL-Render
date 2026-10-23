package faygolover.flrender.light;

import java.util.List;

/**
 * Расчёт объёма цветного света (фоновый поток). Свет растекается от источника по блокам, как ванильный: теряет
 * ступень на каждом шаге (больше — в листве, воде), через непрозрачное не проходит, огибает углы.
 * <ul>
 *   <li>{@link #statics} — блоки-источники: в каждом блоке по каналу берётся самый сильный цвет (как ванильный свет:
 *       два факела не светят вдвое ярче), альфа — небесный свет. Это «подкраска» уже освещённого ванилью.</li>
 *   <li>{@link #dynamics} — подвижные источники (эффекты, предметы в руках): свет складывается, спадает квадратично,
 *       хранится вдвое слабее (запас до двойной силы). Это добавка света поверх кадра.</li>
 * </ul>
 * Объём — куб {@code size}³ блоков с углом {@code origin}; индекс блока {@code x + size * (y + size * z)}.
 */
final class LightJobs {
    private LightJobs() {}

    /** Мир объёма: потери света по блокам и небесный свет (0–15). */
    record World(int ox, int oy, int oz, int size, byte[] att, byte[] sky) {
    }

    /** Подвижный источник (координаты мира). Прямоугольник: {@code nx, nz} — нормаль, {@code w, h} > 0. */
    record Dyn(double x, double y, double z, int level, float r, float g, float b, float strength,
               float nx, float nz, float w, float h, boolean bothSides) {
    }

    /** Собрать мир объёма из секций (все должны быть готовы). */
    static World world(int ox, int oy, int oz, int size, SectionCache.Data[] sections) {
        int n = size >> 4;
        byte[] att = new byte[size * size * size];
        byte[] sky = new byte[size * size * size];
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            SectionCache.Data d = sections[cx + n * (cy + n * cz)];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int src = 16 * (z + 16 * y);
                int dst = (cx << 4) + size * ((cy << 4) + y + size * ((cz << 4) + z));
                System.arraycopy(d.att(), src, att, dst, 16);
                System.arraycopy(d.sky(), src, sky, dst, 16);
            }
        }
        return new World(ox, oy, oz, size, att, sky);
    }

    /** Статический объём: RGBA (rgb — цвет блоков-источников, a — небесный свет). */
    static byte[] statics(World w, SectionCache.Data[] sections, boolean skipVanilla) {
        int size = w.size(), n = size >> 4, cells = size * size * size;
        byte[] out = new byte[cells * 4];
        for (int i = 0; i < cells; i++) out[i * 4 + 3] = (byte) Math.min(255, (w.sky()[i] & 255) * 17);
        Flood flood = new Flood(w);
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            SectionCache.Data d = sections[cx + n * (cy + n * cz)];
            for (int k = 0; k < d.srcPacked().length; k++) {
                int p = d.srcPacked()[k];
                if (skipVanilla && (p >> 16 & 1) != 0) continue;
                int local = p & 4095, level = p >> 12 & 15;
                int lx = local & 15, lz = local >> 4 & 15, ly = local >> 8;
                int start = (cx << 4) + lx + size * ((cy << 4) + ly + size * ((cz << 4) + lz));
                int rgb = d.srcRgb()[k];
                float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
                flood.run(new int[]{start}, level, (cell, lv) -> {
                    float f = lv / 15f;
                    int o = cell * 4;
                    out[o] = (byte) Math.max(out[o] & 255, Math.round(r * f * 255));
                    out[o + 1] = (byte) Math.max(out[o + 1] & 255, Math.round(g * f * 255));
                    out[o + 2] = (byte) Math.max(out[o + 2] & 255, Math.round(b * f * 255));
                });
            }
        }
        return out;
    }

    /** Подвижные источники: RGBA (rgb — добавка света ×0.5, a — не используется). */
    static byte[] dynamics(World w, List<Dyn> sources) {
        int size = w.size(), cells = size * size * size;
        float[] acc = new float[cells * 3];
        Flood flood = new Flood(w);
        for (Dyn s : sources) {
            int[] seeds = seeds(w, s);
            if (seeds.length == 0) continue;
            int level = s.level();
            flood.run(seeds, level, (cell, lv) -> {
                float f = lv / (float) level;
                f = f * f * s.strength();
                int o = cell * 3;
                acc[o] += s.r() * f;
                acc[o + 1] += s.g() * f;
                acc[o + 2] += s.b() * f;
            });
        }
        byte[] out = new byte[cells * 4];
        for (int i = 0; i < cells; i++) {
            out[i * 4] = (byte) Math.min(255, Math.round(acc[i * 3] * 127.5f));
            out[i * 4 + 1] = (byte) Math.min(255, Math.round(acc[i * 3 + 1] * 127.5f));
            out[i * 4 + 2] = (byte) Math.min(255, Math.round(acc[i * 3 + 2] * 127.5f));
        }
        return out;
    }

    /** Блоки, откуда свет начинается: точка — её блок; прямоугольник — блоки перед проёмом (с обеих сторон, если светит в обе). */
    private static int[] seeds(World w, Dyn s) {
        int size = w.size();
        if (s.w() <= 0) {
            int c = cell(w, (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()));
            return c < 0 ? new int[0] : new int[]{c};
        }
        // Вдоль проёма (r = поворот нормали), по высоте; на полблока перед лицом (и за ним, если в обе стороны).
        double rx = s.nz(), rz = -s.nx();
        int cols = Math.max(1, (int) Math.ceil(s.w())), rows = Math.max(1, (int) Math.ceil(s.h()));
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (int side = 0; side < (s.bothSides() ? 2 : 1); side++) {
            double off = side == 0 ? 0.5 : -0.5;
            for (int i = 0; i < cols; i++) for (int j = 0; j < rows; j++) {
                double t = -s.w() / 2.0 + (i + 0.5) * s.w() / cols;
                double x = s.x() + rx * t + s.nx() * off, y = s.y() + (j + 0.5) * s.h() / rows, z = s.z() + rz * t + s.nz() * off;
                int c = cell(w, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (c >= 0 && (w.att()[c] & 255) < 15) set.add(c);
            }
        }
        int[] out = new int[set.size()];
        int k = 0;
        for (int c : set) out[k++] = c;
        return out;
    }

    private static int cell(World w, int x, int y, int z) {
        int s = w.size();
        x -= w.ox();
        y -= w.oy();
        z -= w.oz();
        if (x < 0 || y < 0 || z < 0 || x >= s || y >= s || z >= s) return -1;
        return x + s * (y + s * z);
    }

    interface Visit {
        void at(int cell, int level);
    }

    /** Растекание от блоков-семян: по уровням сверху вниз (потери 1–15 за шаг), каждый блок — один раз с лучшим уровнем. */
    private static final class Flood {
        private final World w;
        private final int size;
        private final byte[] best;
        private final int[] stamp;
        private int run;
        private final int[][] buckets = new int[16][];
        private final int[] counts = new int[16];

        Flood(World w) {
            this.w = w;
            this.size = w.size();
            int cells = size * size * size;
            best = new byte[cells];
            stamp = new int[cells];
            for (int i = 0; i < 16; i++) buckets[i] = new int[256];
        }

        void run(int[] seeds, int level, Visit visit) {
            run++;
            java.util.Arrays.fill(counts, 0);
            for (int s : seeds) push(s, level);
            int s2 = size * size;
            for (int lv = level; lv >= 1; lv--) {
                for (int q = 0; q < counts[lv]; q++) {
                    int c = buckets[lv][q];
                    if ((best[c] & 255) != lv) continue;
                    visit.at(c, lv);
                    if (lv == 1) continue;
                    int x = c % size, y = c / size % size, z = c / s2;
                    if (x > 0) spread(c - 1, lv);
                    if (x < size - 1) spread(c + 1, lv);
                    if (y > 0) spread(c - size, lv);
                    if (y < size - 1) spread(c + size, lv);
                    if (z > 0) spread(c - s2, lv);
                    if (z < size - 1) spread(c + s2, lv);
                }
            }
        }

        private void spread(int n, int lv) {
            int a = w.att()[n] & 255;
            if (a >= 15) return;
            int nl = lv - Math.max(1, a);
            if (nl >= 1) push(n, nl);
        }

        private void push(int c, int lv) {
            if (stamp[c] == run && (best[c] & 255) >= lv) return;
            stamp[c] = run;
            best[c] = (byte) lv;
            int[] b = buckets[lv];
            if (counts[lv] == b.length) buckets[lv] = b = java.util.Arrays.copyOf(b, b.length * 2);
            b[counts[lv]++] = c;
        }
    }
}
