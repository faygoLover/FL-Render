package faygolover.flrender.light;

import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ChunkEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Цветной свет вокруг камеры (0.3.3, 30.10: разреженный — до дальности прорисовки, как ванильный свет).
 * <ul>
 *   <li><b>Подкраска</b> от блоков-источников хранится только там, где она есть: цвет живёт не дальше 15 блоков от
 *       цветного источника. Секция 16³ рядом с источником получает «кирпич» ({@link LightJobs#BRICK}³, с полем в блок
 *       для плавной фильтрации) в атласе — 3D-текстуре на блоке 5; индекс — целочисленная 3D-текстура на блоке 7: на
 *       каждую секцию в дальности номер кирпича + 1 или 0. Память — по числу источников, не по дальности.</li>
 *   <li>Пересчитываются только кирпичи у изменившейся секции (блок, свет, загрузка чанка): секция и соседи на одну.
 *       Пересборка без перемен (перерисовка чанка модом) ничего не трогает.</li>
 *   <li><b>Добавка</b> от подвижных источников — малый куб у камеры на блоке 6 (средне 48, высоко 64, ультра 96 блоков):
 *       она меняется каждый кадр.</li>
 * </ul>
 * Дальность — прорисовка, не больше настройки «Дальность цветного света» (в чанках) и потолка качества; по высоте —
 * несколько секций над и под камерой. Проход по кадру — {@link faygolover.flrender.render.WorldLight}.
 */
public final class ColorVolume {
    private ColorVolume() {}

    public static final int TINT_UNIT = 5;
    public static final int ADD_UNIT = 6;
    public static final int INDEX_UNIT = 7;

    /** Атлас кирпичей: 16 × 8 × {@code atlasZ}. */
    private static final int ATLAS_X = 16, ATLAS_Y = 8, BRICK = LightJobs.BRICK;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FL Render colour light");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private record BrickJob(long key, int version, SectionCache.Data[] near) {
    }

    private record BrickResult(long key, int version, byte[] data) {
    }

    private record DynResult(int ox, int oy, int oz, int size, byte[] add) {
    }

    private static final ConcurrentLinkedQueue<BrickResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<DynResult> DYN_DONE = new AtomicReference<>();
    private static volatile boolean bricksRunning, dynRunning;

    private static ClientLevel level;

    // ---- что известно о секциях (основной поток)
    /** Проверенные секции (знаем, есть ли в них источники). */
    private static final LongOpenHashSet KNOWN = new LongOpenHashSet();
    /** Секции с цветными источниками. */
    private static final LongOpenHashSet LIT = new LongOpenHashSet();
    /** Сколько секций с источниками в кубе 3³ / 5³ вокруг: кирпич может быть / данные секции нужны кирпичам. */
    private static final Long2IntOpenHashMap NEAR1 = new Long2IntOpenHashMap(), NEAR2 = new Long2IntOpenHashMap();
    /** Проверить (загрузка, грязная). */
    private static final LongLinkedOpenHashSet CHECK = new LongLinkedOpenHashSet();

    // ---- кирпичи
    /** Посчитать заново. */
    private static final LongOpenHashSet PENDING = new LongOpenHashSet();
    /** Посчитано: номер кирпича или −1 (цвета нет / атлас полон). */
    private static final Long2IntOpenHashMap DONE = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap VERSION = new Long2IntOpenHashMap();
    private static final IntArrayList FREE = new IntArrayList();
    private static int nextSlot, bricks;
    private static int atlasTex, atlasZ;
    private static ByteBuffer brickBuf;

    // ---- индекс
    private static int indexTex, idxX0, idxY0, idxZ0, idxN, idxH, idxTexN, idxTexH;
    private static short[] index;
    private static ShortBuffer indexBuf;
    private static boolean indexDirty;
    /** Дальность в секциях: по горизонтали и вверх-вниз от камеры. */
    private static int range, vrange;
    private static int camSx = Integer.MIN_VALUE, camSy, camSz;
    private static boolean mode;
    private static int frames;

    // ---- подвижный свет
    private static int addTex, addTexSize, addOx, addOy, addOz;
    private static boolean addReady;
    private static long jobDynHash, lastDynNanos;
    private static ByteBuffer addBuf;

    static {
        NEAR1.defaultReturnValue(0);
        NEAR2.defaultReturnValue(0);
        DONE.defaultReturnValue(Integer.MIN_VALUE);
        VERSION.defaultReturnValue(0);
    }

    // ------------------------------------------------------------------ события мира

    /** Блок изменился (клиент). */
    public static void blockChanged(BlockPos pos) {
        sectionChanged(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Секция перерисовывается (свет, загрузка чанка). */
    public static void sectionChanged(int sx, int sy, int sz) {
        SectionCache.dirty(sx, sy, sz);
        long k = SectionPos.asLong(sx, sy, sz);
        if (KNOWN.contains(k)) CHECK.add(k);
    }

    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ClientLevel l) || l != level) return;
        int cx = e.getChunk().getPos().x, cz = e.getChunk().getPos().z;
        if (camSx == Integer.MIN_VALUE || Math.abs(cx - camSx) > range || Math.abs(cz - camSz) > range) return;
        for (int sy = camSy - vrange; sy <= camSy + vrange; sy++) CHECK.add(SectionPos.asLong(cx, sy, cz));
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (!(e.getLevel() instanceof ClientLevel l) || l != level) return;
        int cx = e.getChunk().getPos().x, cz = e.getChunk().getPos().z;
        for (int sy = l.getMinSection(); sy < l.getMaxSection(); sy++) {
            long k = SectionPos.asLong(cx, sy, cz);
            if (KNOWN.contains(k)) CHECK.add(k);
        }
    }

    // ------------------------------------------------------------------ качество

    /** Куб подвижного света. */
    private static int addSizeFor(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 48;
            case HIGH -> 64;
            case ULTRA -> 96;
        };
    }

    /** Секций над и под камерой (свет на высоте и так виден недалеко). */
    private static int verticalFor(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 2;
            case HIGH -> 3;
            case ULTRA -> 4;
        };
    }

    /** Потолок дальности, чанков. */
    private static int maxRangeFor(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 12;
            case HIGH -> 24;
            case ULTRA -> 32;
        };
    }

    /** Потолок атласа, кирпичей (по 23 КБ: средне 24 МБ, высоко 48, ультра 96). */
    private static int maxBricksFor(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 1024;
            case HIGH -> 2048;
            case ULTRA -> 4096;
        };
    }

    private static int buildsPerFrame(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 8;
            case HIGH -> 16;
            case ULTRA -> 24;
        };
    }

    private static long dynIntervalNanos(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 200_000_000L;
            case HIGH -> 100_000_000L;
            case ULTRA -> 33_000_000L;
        };
    }

    private static int rangeFor(Quality q) {
        int r = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int limit = faygolover.flrender.config.Settings.colorRange().chunks();
        if (limit > 0) r = Math.min(r, limit);
        return Math.max(1, Math.min(r, maxRangeFor(q)));
    }

    // ------------------------------------------------------------------ кадр

    /**
     * Раз за кадр: обновить объём. {@code blocks} — светят ли блоки-источники, {@code dyn} — подвижные источники этого
     * кадра. Возвращает false, если рисовать нечего.
     */
    public static boolean frame(Vec3 cam, boolean blocks, List<LightJobs.Dyn> dyn) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            resetAll();
        }
        if (level == null) return false;
        Quality q = FlRender.quality();
        frames++;

        // Пак со своим цветным светом (Just Colored, Rethinking Voxels...): ванильные источники он красит сам, а наши
        // блоки (ХИС, блоки цвета) не знает и не освещает — их свет добавляем сами. Пак на ванильном свете (Insanity)
        // и без пака — подкраска освещённого, как обычно.
        boolean skipVanilla = faygolover.flrender.client.Oculus.coloredPack();
        boolean newMode = blocks && !skipVanilla;
        if (newMode != mode) {
            mode = newMode;
            resetBricks();
        }

        int sx = SectionPos.blockToSectionCoord(cam.x), sy = SectionPos.blockToSectionCoord(cam.y), sz = SectionPos.blockToSectionCoord(cam.z);
        int r = rangeFor(q), vr = verticalFor(q);
        if (sx != camSx || sy != camSy || sz != camSz || r != range || vr != vrange) moved(sx, sy, sz, r, vr);

        if (mode && atlasTex == 0) growAtlas(2);
        int budget = buildsPerFrame(q);
        budget = checkSections(budget);
        applyResults(q);
        if (mode) budget = submitBricks(budget);
        if ((frames & 63) == 0) trim(q);
        if (indexDirty) uploadIndex();

        // Подвижные источники — малым кубом у камеры, не чаще, чем велит качество.
        int as = addSizeFor(q);
        int ax = Math.floorDiv((int) Math.floor(cam.x) - as / 2, 16) * 16;
        int ay = Math.floorDiv((int) Math.floor(cam.y) - as / 2, 16) * 16;
        int az = Math.floorDiv((int) Math.floor(cam.z) - as / 2, 16) * 16;
        if (skipVanilla && blocks) packSources(ax, ay, az, as, dyn);
        uploadDyn();
        dynamics(q, ax, ay, az, as, dyn, budget);
        return bricks > 0 && mode || addReady;
    }

    private static void resetAll() {
        KNOWN.clear();
        LIT.clear();
        NEAR1.clear();
        NEAR2.clear();
        CHECK.clear();
        SectionCache.clear();
        camSx = Integer.MIN_VALUE;
        addReady = false;
        DYN_DONE.set(null);
        jobDynHash = 0;
        resetBricks();
    }

    private static void resetBricks() {
        PENDING.clear();
        DONE.clear();
        VERSION.clear();
        FREE.clear();
        RESULTS.clear();
        nextSlot = 0;
        bricks = 0;
        indexDirty = true;
        if (mode && camSx != Integer.MIN_VALUE) refreshTargets();
    }

    private static boolean inRange(long k) {
        return Math.abs(SectionPos.x(k) - camSx) <= range && Math.abs(SectionPos.z(k) - camSz) <= range
                && Math.abs(SectionPos.y(k) - camSy) <= vrange;
    }

    /** Камера ушла в другую секцию или поменялась дальность: индекс, новые секции — проверить, далёкие — забыть. */
    private static void moved(int sx, int sy, int sz, int r, int vr) {
        camSx = sx;
        camSy = sy;
        camSz = sz;
        range = r;
        vrange = vr;
        idxX0 = sx - r;
        idxY0 = sy - vr;
        idxZ0 = sz - r;
        idxN = 2 * r + 1;
        idxH = 2 * vr + 1;
        indexDirty = true;
        // Новые секции в дальности (и полоса на две вокруг — данные для кирпичей у края) — проверить.
        for (int cz = sz - r - 2; cz <= sz + r + 2; cz++) for (int cx = sx - r - 2; cx <= sx + r + 2; cx++) {
            if (!SectionCache.loaded(level, cx, cz)) continue;
            for (int y = sy - vr - 2; y <= sy + vr + 2; y++) {
                long k = SectionPos.asLong(cx, y, cz);
                if (!KNOWN.contains(k)) CHECK.add(k);
            }
        }
        // Ушедшие далеко — забыть (кирпичи освободить).
        LongArrayList drop = new LongArrayList();
        for (long k : KNOWN) if (far(k, 3)) drop.add(k);
        for (long k : drop) {
            KNOWN.remove(k);
            setLit(k, false);
            SectionCache.remove(k);
        }
        LongArrayList gone = new LongArrayList();
        for (var e : DONE.long2IntEntrySet()) if (!inRange(e.getLongKey())) gone.add(e.getLongKey());
        for (long k : gone) freeBrick(k);
        PENDING.removeIf((long k) -> !inRange(k));
        if (mode) refreshTargets();
    }

    private static boolean far(long k, int margin) {
        return Math.abs(SectionPos.x(k) - camSx) > range + margin || Math.abs(SectionPos.z(k) - camSz) > range + margin
                || Math.abs(SectionPos.y(k) - camSy) > vrange + margin;
    }

    /** Кирпичи у известных источников в дальности, ещё не посчитанные, — в очередь. */
    private static void refreshTargets() {
        for (long l : LIT) {
            int x = SectionPos.x(l), y = SectionPos.y(l), z = SectionPos.z(l);
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                long t = SectionPos.asLong(x + dx, y + dy, z + dz);
                if (inRange(t) && !DONE.containsKey(t)) PENDING.add(t);
            }
        }
    }

    private static void setLit(long k, boolean lit) {
        boolean was = LIT.contains(k);
        if (was == lit) return;
        if (lit) LIT.add(k);
        else LIT.remove(k);
        int d = lit ? 1 : -1;
        int x = SectionPos.x(k), y = SectionPos.y(k), z = SectionPos.z(k);
        for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            long n = SectionPos.asLong(x + dx, y + dy, z + dz);
            if (NEAR2.addTo(n, d) + d <= 0) NEAR2.remove(n);
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1 && NEAR1.addTo(n, d) + d <= 0) NEAR1.remove(n);
        }
    }

    /** Секция поменялась: кирпичи вокруг — заново (старые показываются, пока не готовы новые). */
    private static void invalidateAround(long k) {
        int x = SectionPos.x(k), y = SectionPos.y(k), z = SectionPos.z(k);
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            long t = SectionPos.asLong(x + dx, y + dy, z + dz);
            VERSION.addTo(t, 1);
            if (NEAR1.get(t) <= 0) {
                PENDING.remove(t);
                freeBrick(t);
            } else if (mode && inRange(t)) {
                PENDING.add(t);
            }
        }
    }

    /** Проверить секции из очереди: есть ли источники, поменялись ли данные. Возвращает остаток бюджета сборок. */
    private static int checkSections(int budget) {
        int checks = 1024;
        var it = CHECK.iterator();
        while (it.hasNext() && checks-- > 0) {
            long k = it.nextLong();
            int x = SectionPos.x(k), y = SectionPos.y(k), z = SectionPos.z(k);
            if (far(k, 2)) {
                it.remove();
                continue;
            }
            boolean was = LIT.contains(k);
            if (!SectionCache.loaded(level, x, z)) {
                it.remove();
                KNOWN.remove(k);
                SectionCache.remove(k);
                setLit(k, false);
                if (was) invalidateAround(k);
                continue;
            }
            boolean maybe = SectionCache.mayHaveSources(level, x, y, z);
            boolean needData = maybe || SectionCache.cached(k);
            if (needData && budget <= 0) break;
            it.remove();
            KNOWN.add(k);
            boolean changed = false, lit = false;
            if (needData) {
                SectionCache.Data old = SectionCache.peek(k);
                SectionCache.Data d = SectionCache.build(level, x, y, z);
                budget--;
                lit = d.srcPacked().length > 0;
                changed = old == null ? lit : !SectionCache.same(old, d);
                if (!lit && NEAR2.get(k) <= 0) SectionCache.remove(k);
            }
            setLit(k, lit);
            if (changed || was != lit) invalidateAround(k);
        }
        return budget;
    }

    /** Отдать в фон кирпичи из очереди — ближние первыми. */
    private static int submitBricks(int budget) {
        if (bricksRunning || PENDING.isEmpty()) return budget;
        LongArrayList order = new LongArrayList(PENDING);
        order.sort((long a, long b) -> Integer.compare(dist(a), dist(b)));
        List<BrickJob> jobs = new ArrayList<>();
        for (int i = 0; i < order.size() && jobs.size() < 48; i++) {
            long t = order.getLong(i);
            if (!inRange(t)) {
                PENDING.remove(t);
                continue;
            }
            if (NEAR1.get(t) <= 0) {
                PENDING.remove(t);
                freeBrick(t);
                DONE.put(t, -1);
                continue;
            }
            SectionCache.Data[] near = new SectionCache.Data[27];
            int x = SectionPos.x(t), y = SectionPos.y(t), z = SectionPos.z(t);
            boolean ready = true;
            for (int dz = -1; dz <= 1 && ready; dz++) for (int dy = -1; dy <= 1 && ready; dy++) for (int dx = -1; dx <= 1; dx++) {
                SectionCache.Data d = SectionCache.ready(x + dx, y + dy, z + dz);
                if (d == null) {
                    if (budget <= 0) {
                        ready = false;
                        break;
                    }
                    d = SectionCache.build(level, x + dx, y + dy, z + dz);
                    budget--;
                }
                near[(dx + 1) + 3 * ((dy + 1) + 3 * (dz + 1))] = d;
            }
            if (!ready) break;
            PENDING.remove(t);
            jobs.add(new BrickJob(t, VERSION.get(t), near));
        }
        if (jobs.isEmpty()) return budget;
        bricksRunning = true;
        WORKER.execute(() -> {
            try {
                for (BrickJob j : jobs) RESULTS.add(new BrickResult(j.key(), j.version(), LightJobs.brick(j.near())));
            } catch (Throwable th) {
                FlRenderMod.LOGGER.error("FL Render: расчёт цветного света упал", th);
            } finally {
                bricksRunning = false;
            }
        });
        return budget;
    }

    private static int dist(long k) {
        return Math.max(Math.abs(SectionPos.x(k) - camSx), Math.abs(SectionPos.z(k) - camSz)) * 4 + Math.abs(SectionPos.y(k) - camSy);
    }

    /** Готовые кирпичи — в атлас (не больше 64 за кадр). */
    private static void applyResults(Quality q) {
        for (int n = 0; n < 64; n++) {
            BrickResult res = RESULTS.poll();
            if (res == null) return;
            long t = res.key();
            if (!mode || VERSION.get(t) != res.version() || !inRange(t)) continue;
            if (res.data() == null) {
                freeBrick(t);
                DONE.put(t, -1);
                continue;
            }
            int slot = DONE.get(t);
            if (slot < 0) {
                slot = allocate(q);
                if (slot == -2) return;  // атлас вырос — всё считается заново
                if (slot < 0) {
                    DONE.put(t, -1);
                    continue;
                }
                bricks++;
            }
            uploadBrick(slot, res.data());
            DONE.put(t, slot);
            indexDirty = true;
        }
    }

    /** Свободное место в атласе; −1 — атлас полон и расти некуда; −2 — атлас вырос (старые кирпичи пропали). */
    private static int allocate(Quality q) {
        if (!FREE.isEmpty()) return FREE.removeInt(FREE.size() - 1);
        int capacity = ATLAS_X * ATLAS_Y * atlasZ;
        if (atlasTex != 0 && nextSlot < capacity) return nextSlot++;
        int want = atlasTex == 0 ? 2 : atlasZ * 2;
        if (ATLAS_X * ATLAS_Y * want > maxBricksFor(q)) return -1;
        growAtlas(want);
        return -2;
    }

    private static void growAtlas(int z) {
        if (atlasTex == 0) atlasTex = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, atlasTex);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, ATLAS_X * BRICK, ATLAS_Y * BRICK, z * BRICK, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        atlasZ = z;
        // Содержимое прежнего атласа не переносим — посчитать заново (доли секунды в фоне).
        resetBricks();
    }

    private static void freeBrick(long t) {
        int slot = DONE.remove(t);
        if (slot >= 0) {
            FREE.add(slot);
            bricks--;
            indexDirty = true;
        }
    }

    private static void uploadBrick(int slot, byte[] data) {
        if (brickBuf == null) brickBuf = MemoryUtil.memAlloc(BRICK * BRICK * BRICK * 4);
        brickBuf.clear();
        brickBuf.put(data);
        brickBuf.flip();
        int bx = slot % ATLAS_X, by = slot / ATLAS_X % ATLAS_Y, bz = slot / (ATLAS_X * ATLAS_Y);
        unpackDefaults(4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, atlasTex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, bx * BRICK, by * BRICK, bz * BRICK, BRICK, BRICK, BRICK,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, brickBuf);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private static void uploadIndex() {
        indexDirty = false;
        int cells = idxN * idxH * idxN;
        if (index == null || index.length != cells) index = new short[cells];
        else java.util.Arrays.fill(index, (short) 0);
        for (var e : DONE.long2IntEntrySet()) {
            int slot = e.getIntValue();
            if (slot < 0) continue;
            long k = e.getLongKey();
            int x = SectionPos.x(k) - idxX0, y = SectionPos.y(k) - idxY0, z = SectionPos.z(k) - idxZ0;
            if (x < 0 || y < 0 || z < 0 || x >= idxN || y >= idxH || z >= idxN) continue;
            index[x + idxN * (y + idxH * z)] = (short) (slot + 1);
        }
        if (indexBuf == null || indexBuf.capacity() < cells) {
            if (indexBuf != null) MemoryUtil.memFree(indexBuf);
            indexBuf = MemoryUtil.memAllocShort(cells);
        }
        indexBuf.clear();
        indexBuf.put(index);
        indexBuf.flip();
        if (indexTex == 0) indexTex = GL11.glGenTextures();
        unpackDefaults(1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, indexTex);
        if (idxTexN != idxN || idxTexH != idxH) {
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16UI, idxN, idxH, idxN, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, indexBuf);
            idxTexN = idxN;
            idxTexH = idxH;
        } else {
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, idxN, idxH, idxN, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, indexBuf);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        unpackDefaults(4);
    }

    private static void unpackDefaults(int alignment) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
    }

    /** Данные секций, которые больше никому не нужны, — из памяти. */
    private static void trim(Quality q) {
        int as = addSizeFor(q) / 16 + 1;
        LongArrayList drop = new LongArrayList();
        for (long k : SectionCache.keys()) {
            boolean add = Math.abs(SectionPos.x(k) - camSx) <= as && Math.abs(SectionPos.y(k) - camSy) <= as
                    && Math.abs(SectionPos.z(k) - camSz) <= as;
            if (!add && (NEAR2.get(k) <= 0 || far(k, 2))) drop.add(k);
        }
        for (long k : drop) SectionCache.remove(k);
        VERSION.keySet().removeIf((long k) -> far(k, 3));
    }

    // ------------------------------------------------------------------ подвижный свет

    /** Сила света наших блоков-источников поверх пака со своим цветным светом. */
    private static final float PACK_BLOCK_STRENGTH = 1.6f;

    /** Блоки-источники модов (не ванильные) у камеры — как подвижные источники: свет добавляется сам. */
    private static void packSources(int ax, int ay, int az, int as, List<LightJobs.Dyn> dyn) {
        int n = as >> 4;
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            int sx = (ax >> 4) + cx, sy = (ay >> 4) + cy, sz = (az >> 4) + cz;
            long k = SectionPos.asLong(sx, sy, sz);
            if (!LIT.contains(k)) continue;
            SectionCache.Data d = SectionCache.peek(k);
            if (d == null) continue;
            for (int i = 0; i < d.srcPacked().length; i++) {
                int p = d.srcPacked()[i];
                if ((p >> 16 & 1) != 0) continue;
                int local = p & 4095, lv = p >> 12 & 15, rgb = d.srcRgb()[i];
                double x = (sx << 4) + (local & 15) + 0.5, y = (sy << 4) + (local >> 8) + 0.5, z = (sz << 4) + (local >> 4 & 15) + 0.5;
                float top = Math.max(1, Math.max(rgb >> 16 & 255, Math.max(rgb >> 8 & 255, rgb & 255)));
                dyn.add(new LightJobs.Dyn(x, y, z, lv, (rgb >> 16 & 255) / top, (rgb >> 8 & 255) / top, (rgb & 255) / top,
                        PACK_BLOCK_STRENGTH, 0, 0, 0, 0, false));
            }
        }
    }

    private static void dynamics(Quality q, int ax, int ay, int az, int as, List<LightJobs.Dyn> dyn, int budget) {
        if (dynRunning) return;
        long hash = hash(dyn) * 31 + ax * 7919L + ay * 104729L + az + SectionCache.generation() * 1_000_003L;
        long now = System.nanoTime();
        if (hash == jobDynHash || now - lastDynNanos < dynIntervalNanos(q)) return;
        if (dyn.isEmpty()) {
            jobDynHash = hash;
            addReady = false;
            return;
        }
        int n = as >> 4;
        SectionCache.Data[] secs = new SectionCache.Data[n * n * n];
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            int sx = (ax >> 4) + cx, sy = (ay >> 4) + cy, sz = (az >> 4) + cz;
            SectionCache.Data d = SectionCache.ready(sx, sy, sz);
            if (d == null) {
                if (budget-- <= 0) return;
                d = SectionCache.build(level, sx, sy, sz);
            }
            secs[cx + n * (cy + n * cz)] = d;
        }
        jobDynHash = hash;
        lastDynNanos = now;
        List<LightJobs.Dyn> copy = new ArrayList<>(dyn);
        dynRunning = true;
        WORKER.execute(() -> {
            try {
                LightJobs.World w = LightJobs.world(ax, ay, az, as, as, secs);
                DYN_DONE.set(new DynResult(ax, ay, az, as, LightJobs.dynamics(w, copy)));
            } catch (Throwable t) {
                FlRenderMod.LOGGER.error("FL Render: расчёт подвижного света упал", t);
            } finally {
                dynRunning = false;
            }
        });
    }

    private static long hash(List<LightJobs.Dyn> dyn) {
        long h = dyn.size();
        for (LightJobs.Dyn d : dyn) {
            h = h * 31 + Math.round(d.x() * 4);
            h = h * 31 + Math.round(d.y() * 4);
            h = h * 31 + Math.round(d.z() * 4);
            h = h * 31 + d.level();
            h = h * 31 + Math.round(d.strength() * 20);
            h = h * 31 + Math.round(d.r() * 32) * 1089 + Math.round(d.g() * 32) * 33 + Math.round(d.b() * 32);
        }
        return h;
    }

    private static void uploadDyn() {
        DynResult dr = DYN_DONE.getAndSet(null);
        if (dr == null) return;
        int s = dr.size();
        if (addTex == 0 || addTexSize != s) {
            if (addTex == 0) addTex = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, addTex);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, s, s, s, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            addTexSize = s;
            if (addBuf != null) MemoryUtil.memFree(addBuf);
            addBuf = MemoryUtil.memAlloc(s * s * s * 4);
        }
        addBuf.clear();
        addBuf.put(dr.add(), 0, Math.min(dr.add().length, addBuf.capacity()));
        addBuf.flip();
        unpackDefaults(4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, addTex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, s, s, s, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, addBuf);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        addOx = dr.ox();
        addOy = dr.oy();
        addOz = dr.oz();
        addReady = true;
    }

    // ------------------------------------------------------------------ шейдер

    /** Есть ли добавка подвижных источников. */
    public static boolean hasAdd() {
        return addReady && addTex != 0;
    }

    /** Подкраска есть (кирпичи в атласе). */
    public static boolean hasTint() {
        return mode && bricks > 0 && atlasTex != 0 && indexTex != 0;
    }

    /** Uniform-ы прохода: индекс и атлас подкраски, куб подвижного света — относительно камеры. */
    public static void uniforms(ShaderInstance shader, Vec3 cam) {
        shader.safeGetUniform("IndexOrigin").set((float) (idxX0 * 16 - cam.x), (float) (idxY0 * 16 - cam.y), (float) (idxZ0 * 16 - cam.z));
        shader.safeGetUniform("IndexSize").set((float) idxN, (float) idxH, (float) idxN);
        shader.safeGetUniform("AtlasBricks").set((float) ATLAS_X, (float) ATLAS_Y, (float) Math.max(1, atlasZ));
        shader.safeGetUniform("Range").set(range * 16.0f, vrange * 16.0f);
        shader.safeGetUniform("HasTint").set(hasTint() ? 1.0f : 0.0f);
        shader.safeGetUniform("AddOrigin").set((float) (addOx - cam.x), (float) (addOy - cam.y), (float) (addOz - cam.z));
        shader.safeGetUniform("AddSize").set((float) Math.max(1, addTexSize));
        shader.safeGetUniform("HasAdd").set(hasAdd() ? 1.0f : 0.0f);
    }

    /** Привязать 3D-текстуры к своим блокам (до рисования шейдером). */
    public static void bind() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TINT_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, atlasTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + ADD_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, addTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + INDEX_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, indexTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    public static void unbind() {
        for (int unit : new int[]{TINT_UNIT, ADD_UNIT, INDEX_UNIT}) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }
}
