package faygolover.flrender.light;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Что цветной свет знает о мире, по секциям 16³ (только клиент, основной поток): сколько свет теряет в каждом блоке
 * (как у ванильного света: 15 — не проходит), небесный свет (днём солнце перебивает цвет) и цветные источники.
 * Секция пересобирается, когда в ней меняется блок или свет ({@link #dirty}); готовые данные не меняются —
 * фоновый расчёт читает их без блокировок.
 */
final class SectionCache {
    private SectionCache() {}

    /** Данные секции. {@code att}, {@code sky}, {@code blk} (ванильный свет блоков) — по блоку (x + 16 * (z + 16 * y)); источники — индекс | уровень << 12 | без пака << 16. */
    record Data(byte[] att, byte[] sky, byte[] blk, int[] srcPacked, int[] srcRgb) {
    }

    private static final int[] NO_INTS = new int[0];
    private static final Data BELOW = new Data(filled((byte) 15), new byte[4096], new byte[4096], NO_INTS, NO_INTS);
    private static final Data ABOVE = new Data(new byte[4096], filled((byte) 15), new byte[4096], NO_INTS, NO_INTS);
    private static final Data UNLOADED = new Data(new byte[4096], new byte[4096], new byte[4096], NO_INTS, NO_INTS);

    private static final Long2ObjectOpenHashMap<Data> CACHE = new Long2ObjectOpenHashMap<>();
    /** Почти белые источники не считать (качество ниже «Высокого»). */
    static volatile boolean skipSubtle;
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    /** Растёт при каждой смене данных секции — по нему видно, что объём пора пересчитать. */
    private static int generation;

    private static byte[] filled(byte v) {
        byte[] a = new byte[4096];
        java.util.Arrays.fill(a, v);
        return a;
    }

    static void dirty(int sx, int sy, int sz) {
        long k = SectionPos.asLong(sx, sy, sz);
        if (CACHE.containsKey(k)) DIRTY.add(k);
    }

    static void clear() {
        CACHE.clear();
        DIRTY.clear();
        generation++;
    }

    static int generation() {
        return generation;
    }

    /** Данные секции, если готовы и не устарели; иначе null. */
    static Data ready(int sx, int sy, int sz) {
        long k = SectionPos.asLong(sx, sy, sz);
        return DIRTY.contains(k) ? null : CACHE.get(k);
    }

    /** Собрать (пересобрать) секцию сейчас. */
    static Data build(ClientLevel level, int sx, int sy, int sz) {
        long k = SectionPos.asLong(sx, sy, sz);
        Data d = scan(level, sx, sy, sz);
        Data old = CACHE.put(k, d);
        DIRTY.remove(k);
        if (old == null || !same(old, d)) generation++;
        return d;
    }

    /** Есть ли секция в кэше (хоть и устаревшая). */
    static boolean cached(long k) {
        return CACHE.containsKey(k);
    }

    static boolean isDirty(long k) {
        return DIRTY.contains(k);
    }

    static Data peek(long k) {
        return CACHE.get(k);
    }

    static void remove(long k) {
        CACHE.remove(k);
        DIRTY.remove(k);
    }

    static it.unimi.dsi.fastutil.longs.LongSet keys() {
        return CACHE.keySet();
    }

    /** Те же данные (пересборка без перемен — например, перерисовка чанка модом динамического света). */
    static boolean same(Data a, Data b) {
        return a == b || java.util.Arrays.equals(a.att(), b.att()) && java.util.Arrays.equals(a.blk(), b.blk())
                && java.util.Arrays.equals(a.sky(), b.sky()) && java.util.Arrays.equals(a.srcPacked(), b.srcPacked())
                && java.util.Arrays.equals(a.srcRgb(), b.srcRgb());
    }

    /**
     * Быстро, по палитре секции: могут ли в ней быть цветные источники. false — точно нет (или чанк не загружен);
     * true — надо собрать секцию и посмотреть.
     */
    static boolean mayHaveSources(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return false;
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return false;
        LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        return sec != null && !sec.hasOnlyAir() && sec.maybeHas(st -> ColorTable.of(st) != null);
    }

    static boolean loaded(ClientLevel level, int sx, int sz) {
        return level.getChunkSource().getChunk(sx, sz, false) != null;
    }

    /** Убрать секции вне коробки (в секциях) — память. */
    static void trim(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        CACHE.keySet().removeIf((long k) -> {
            int x = SectionPos.x(k), y = SectionPos.y(k), z = SectionPos.z(k);
            boolean out = x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ;
            if (out) DIRTY.remove(k);
            return out;
        });
    }

    private static Data scan(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSection()) return BELOW;
        if (sy >= level.getMaxSection()) return ABOVE;
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return UNLOADED;
        LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        LayerLightEventListener skyListener = level.getLightEngine().getLayerListener(LightLayer.SKY);
        DataLayer skyLayer = skyListener.getDataLayerData(SectionPos.of(sx, sy, sz));
        byte[] sky = new byte[4096];
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        if (skyLayer != null) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                sky[x + 16 * (z + 16 * y)] = (byte) skyLayer.get(x, y, z);
            }
        } else {
            java.util.Arrays.fill(sky, (byte) skyListener.getLightValue(new BlockPos(x0 + 8, y0 + 8, z0 + 8)));
        }
        // Ванильный свет блоков: какую долю света в блоке даёт цветной источник (там, где он главный, — чистый цвет).
        byte[] blk = new byte[4096];
        DataLayer blkLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(sx, sy, sz));
        if (blkLayer != null) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                blk[x + 16 * (z + 16 * y)] = (byte) blkLayer.get(x, y, z);
            }
        }
        if (sec == null || sec.hasOnlyAir()) return new Data(new byte[4096], sky, blk, NO_INTS, NO_INTS);
        byte[] att = new byte[4096];
        IntArrayList packed = new IntArrayList();
        IntArrayList rgb = new IntArrayList();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState s = sec.getBlockState(x, y, z);
            if (s.isAir()) continue;
            int i = x + 16 * (z + 16 * y);
            p.set(x0 + x, y0 + y, z0 + z);
            att[i] = (byte) Math.min(15, Math.max(0, s.getLightBlock(level, p)));
            ColorTable.Entry e = ColorTable.of(s);
            if (e == null || skipSubtle && ColorTable.subtle(e)) continue;
            int lv = ColorTable.level(s, e);
            if (lv <= 0) continue;
            packed.add(i | lv << 12 | (e.withShaders() ? 0 : 1 << 16));
            rgb.add(Math.round(e.r() * 255) << 16 | Math.round(e.g() * 255) << 8 | Math.round(e.b() * 255));
        }
        return new Data(att, sky, blk, packed.toIntArray(), rgb.toIntArray());
    }
}
