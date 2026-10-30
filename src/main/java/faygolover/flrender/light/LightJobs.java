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
 * Объём — коробка {@code hs × vs × hs} блоков (по горизонтали шире, 30.10) с углом {@code origin}; индекс блока
 * {@code x + hs * (y + vs * z)}.
 */
final class LightJobs {
    private LightJobs() {}

    /** Мир объёма: потери света по блокам и небесный свет (0–15). */
    record World(int ox, int oy, int oz, int hs, int vs, byte[] att, byte[] sky, byte[] blk) {
        int cells() {
            return hs * vs * hs;
        }
    }

    /** Подвижный источник (координаты мира). Прямоугольник: {@code nx, nz} — нормаль, {@code w, h} > 0. */
    record Dyn(double x, double y, double z, int level, float r, float g, float b, float strength,
               float nx, float nz, float w, float h, boolean bothSides) {
    }

    /** Собрать мир объёма из секций (все должны быть готовы; индекс секции {@code cx + nh * (cy + nv * cz)}). */
    static World world(int ox, int oy, int oz, int hs, int vs, SectionCache.Data[] sections) {
        int nh = hs >> 4, nv = vs >> 4, cells = hs * vs * hs;
        byte[] att = new byte[cells];
        byte[] sky = new byte[cells];
        byte[] blk = new byte[cells];
        for (int cz = 0; cz < nh; cz++) for (int cy = 0; cy < nv; cy++) for (int cx = 0; cx < nh; cx++) {
            SectionCache.Data d = sections[cx + nh * (cy + nv * cz)];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int src = 16 * (z + 16 * y);
                int dst = (cx << 4) + hs * ((cy << 4) + y + vs * ((cz << 4) + z));
                System.arraycopy(d.att(), src, att, dst, 16);
                System.arraycopy(d.sky(), src, sky, dst, 16);
                System.arraycopy(d.blk(), src, blk, dst, 16);
            }
        }
        return new World(ox, oy, oz, hs, vs, att, sky, blk);
    }

    /**
     * Статический объём: RGBA (rgb — чистый цвет блоков-источников, умноженный на их долю в ванильном свете блока:
     * где источник главный — 1, «светодиод»; a — небесный свет). {@code none} — без источников (только небо).
     */
    static byte[] statics(World w, SectionCache.Data[] sections, boolean none) {
        int hs = w.hs(), vs = w.vs(), nh = hs >> 4, nv = vs >> 4, cells = w.cells();
        byte[] out = new byte[cells * 4];
        for (int i = 0; i < cells; i++) out[i * 4 + 3] = (byte) Math.min(255, (w.sky()[i] & 255) * 17);
        if (none) return out;
        // Источники одного цвета растекаются вместе, одним проходом (30.10): в каждом блоке берётся лучший уровень —
        // то же, что максимум отдельных растеканий, но сплошной объём из сотен блоков света стоит как один источник.
        it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList[]> byColour =
                new it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<>();
        for (int cz = 0; cz < nh; cz++) for (int cy = 0; cy < nv; cy++) for (int cx = 0; cx < nh; cx++) {
            SectionCache.Data d = sections[cx + nh * (cy + nv * cz)];
            for (int k = 0; k < d.srcPacked().length; k++) {
                int p = d.srcPacked()[k];
                int local = p & 4095, level = p >> 12 & 15;
                int lx = local & 15, lz = local >> 4 & 15, ly = local >> 8;
                var lists = byColour.computeIfAbsent(d.srcRgb()[k], c -> new it.unimi.dsi.fastutil.ints.IntArrayList[]{
                        new it.unimi.dsi.fastutil.ints.IntArrayList(), new it.unimi.dsi.fastutil.ints.IntArrayList()});
                lists[0].add((cx << 4) + lx + hs * ((cy << 4) + ly + vs * ((cz << 4) + lz)));
                lists[1].add(level);
            }
        }
        Flood flood = new Flood(w);
        for (var e : byColour.int2ObjectEntrySet()) {
            int rgb = e.getIntKey();
            // Цвет как есть: оттенок шейдер берёт сам (rgb / max), а яркость цвета — сила подкраски (тёмный — слабее).
            float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
            if (r <= 0 && g <= 0 && b <= 0) continue;
            flood.run(e.getValue()[0].toIntArray(), e.getValue()[1].toIntArray(), (cell, lv) -> {
                float f = Math.min(1f, lv / (float) Math.max(1, w.blk()[cell] & 255));
                int o = cell * 4;
                out[o] = (byte) Math.max(out[o] & 255, Math.round(r * f * 255));
                out[o + 1] = (byte) Math.max(out[o + 1] & 255, Math.round(g * f * 255));
                out[o + 2] = (byte) Math.max(out[o + 2] & 255, Math.round(b * f * 255));
            });
        }
        return out;
    }

    /** Сторона кирпича подкраски: секция 16 и по блоку соседей с каждой стороны (для плавной фильтрации на стыках). */
    static final int BRICK = 18;

    /** Сторона кластера кирпичей в секциях: кластер считается одним заданием (мир — кластер и секция вокруг). */
    static final int CLUSTER = 3;
    /** Сторона мира кластера в секциях. */
    static final int CLUSTER_WORLD = CLUSTER + 2;

    /**
     * Кирпичи подкраски кластера (30.10): мир — {@link #CLUSTER_WORLD}³ секций ({@code near[x + n * (y + n * z)]}),
     * свет растекается один раз на весь кластер. {@code targets} — секции кластера (0..CLUSTER−1 по осям, упаковано
     * {@code x | y << 4 | z << 8}); результат по каждой — RGBA {@link #BRICK}³ или null, если цвета в ней нет.
     */
    static byte[][] bricks(SectionCache.Data[] near, int[] targets) {
        int n = CLUSTER_WORLD, size = n * 16;
        World w = world(0, 0, 0, size, size, near);
        byte[] full = statics(w, near, false);
        byte[][] res = new byte[targets.length][];
        for (int i = 0; i < targets.length; i++) {
            int t = targets[i];
            res[i] = brick(full, size, 16 * (1 + (t & 15)), 16 * (1 + (t >> 4 & 15)), 16 * (1 + (t >> 8 & 15)));
        }
        return res;
    }

    /** Вырезать кирпич секции с углом (x0, y0, z0) из посчитанного мира стороной {@code size}. */
    private static byte[] brick(byte[] full, int size, int x0, int y0, int z0) {
        byte[] out = new byte[BRICK * BRICK * BRICK * 4];
        boolean any = false;
        for (int z = 0; z < BRICK; z++) for (int y = 0; y < BRICK; y++) {
            int src = ((x0 - 1) + size * ((y0 - 1 + y) + size * (z0 - 1 + z))) * 4, dst = BRICK * (y + BRICK * z) * 4;
            System.arraycopy(full, src, out, dst, BRICK * 4);
            if (!any) for (int i = 0; i < BRICK; i++) {
                int o = dst + i * 4;
                if (out[o] != 0 || out[o + 1] != 0 || out[o + 2] != 0) {
                    any = true;
                    break;
                }
            }
        }
        return any ? out : null;
    }

    /** Подвижные источники: RGBA (rgb — добавка света ×0.5, a — не используется). */
    static byte[] dynamics(World w, List<Dyn> sources) {
        int cells = w.cells();
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
        x -= w.ox();
        y -= w.oy();
        z -= w.oz();
        if (x < 0 || y < 0 || z < 0 || x >= w.hs() || y >= w.vs() || z >= w.hs()) return -1;
        return x + w.hs() * (y + w.vs() * z);
    }

    interface Visit {
        void at(int cell, int level);
    }

    /** Растекание от блоков-семян: по уровням сверху вниз (потери 1–15 за шаг), каждый блок — один раз с лучшим уровнем. */
    /** Рабочие массивы растекания по размеру объёма: большой объём подкраски не выделяет их заново на каждый расчёт. */
    private static final class Scratch {
        final byte[] best;
        final int[] stamp;
        int run;

        Scratch(int cells) {
            best = new byte[cells];
            stamp = new int[cells];
        }
    }

    /** Только поток расчёта; два размера — подкраска и подвижный свет. */
    private static final java.util.Map<Integer, Scratch> SCRATCH = new java.util.LinkedHashMap<>();

    private static Scratch scratch(int cells) {
        Scratch sc = SCRATCH.get(cells);
        if (sc == null) {
            if (SCRATCH.size() >= 2) SCRATCH.remove(SCRATCH.keySet().iterator().next());
            SCRATCH.put(cells, sc = new Scratch(cells));
        }
        return sc;
    }

    private static final class Flood {
        private final World w;
        private final int hs, vs;
        private final byte[] best;
        private final int[] stamp;
        private final Scratch sc;
        private int run;
        private final int[][] buckets = new int[16][];
        private final int[] counts = new int[16];

        Flood(World w) {
            this.w = w;
            this.hs = w.hs();
            this.vs = w.vs();
            sc = scratch(w.cells());
            best = sc.best;
            stamp = sc.stamp;
            run = sc.run;
            for (int i = 0; i < 16; i++) buckets[i] = new int[256];
        }

        void run(int[] seeds, int level, Visit visit) {
            int[] levels = new int[seeds.length];
            java.util.Arrays.fill(levels, level);
            run(seeds, levels, visit);
        }

        /** Растекание от многих семян со своими уровнями (один цвет). */
        void run(int[] seeds, int[] levels, Visit visit) {
            int level = 0;
            for (int l : levels) level = Math.max(level, Math.min(15, l));
            run++;
            if (run == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                run = 1;
            }
            sc.run = run;
            java.util.Arrays.fill(counts, 0);
            for (int i = 0; i < seeds.length; i++) if (levels[i] > 0) push(seeds[i], Math.min(15, levels[i]));
            int s2 = hs * vs;
            for (int lv = level; lv >= 1; lv--) {
                for (int q = 0; q < counts[lv]; q++) {
                    int c = buckets[lv][q];
                    if ((best[c] & 255) != lv) continue;
                    visit.at(c, lv);
                    if (lv == 1) continue;
                    int x = c % hs, y = c / hs % vs, z = c / s2;
                    if (x > 0) spread(c - 1, lv);
                    if (x < hs - 1) spread(c + 1, lv);
                    if (y > 0) spread(c - hs, lv);
                    if (y < vs - 1) spread(c + hs, lv);
                    if (z > 0) spread(c - s2, lv);
                    if (z < hs - 1) spread(c + s2, lv);
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
