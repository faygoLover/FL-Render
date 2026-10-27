package faygolover.flrender.flashlight;

import faygolover.flrender.FlRenderMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.Tags;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Что задерживает луч фонаря, по половинкам блока: на блок — байт, бит на каждую из 8 половинок (x + 2y + 4z).
 * Целые блоки держат свет целиком. Двери, люки, калитки (тонкие пластины), плиты и ступени — половинки, которые их форма
 * столкновения покрывает хотя бы на четверть. Остальные неполные блоки (тропинка, грядка, сундуки, мебель и тренажёры
 * модов) — только почти целиком заполненные половинки: иначе их видимые грани лежат внутри «занятых» половинок и
 * затеняют сами себя (чёрные края тропинок, чёрные грани моделей). Заборы, факелы, трава, цветы, стекло и решётки
 * свет не держат.
 * <p>
 * Куб 96³ блоков вокруг камеры (6³ секций) — 3D-текстура с кольцевой адресацией (блок → его координата по модулю 96):
 * при движении грузятся только вошедшие секции. Шейдер чанков идёт по ней от поверхности к лампе
 * ({@code ChunkPatch}); для сущностей — {@link #clear} на процессоре по тем же данным. Только поток отрисовки.
 */
public final class Occupancy {
    private Occupancy() {}

    static final int SECTIONS = 6;
    public static final int SIZE = SECTIONS * 16;
    /** Блок текстуры (выше тех, что знает GlStateManager, — его кэш не сбивается). */
    public static final int UNIT = 13;

    /** Доля половинки, при которой она держит свет: двери, люки, калитки, плиты, ступени — и прочие неполные блоки. */
    private static final double PLATE_FILL = 0.25, MODEL_FILL = 0.95;
    /** Секций пересобрать за кадр (первое заполнение ~0,3 с). */
    private static final int SCANS_PER_FRAME = 12;
    private static final int UPLOADS_PER_FRAME = 48;

    private static final byte[] EMPTY = new byte[4096];
    private static final Long2ObjectOpenHashMap<byte[]> CACHE = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    private static final Reference2IntOpenHashMap<BlockState> MASKS = new Reference2IntOpenHashMap<>();
    /** Какая секция сейчас лежит в каждом месте текстуры. */
    private static final long[] SLOT = new long[SECTIONS * SECTIONS * SECTIONS];

    static {
        MASKS.defaultReturnValue(-1);
        Arrays.fill(SLOT, Long.MIN_VALUE);
    }

    private static ClientLevel level;
    private static int tex;
    private static ByteBuffer buf;
    /** Нижний угол куба (в секциях). */
    private static int minSx, minSy, minSz;
    private static boolean placed;

    public static void blockChanged(BlockPos pos) {
        mark(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public static void sectionChanged(int sx, int sy, int sz) {
        mark(sx, sy, sz);
    }

    private static void mark(int sx, int sy, int sz) {
        long k = SectionPos.asLong(sx, sy, sz);
        if (CACHE.containsKey(k)) DIRTY.add(k);
    }

    /** Раз за кадр до рисования мира: куб на место, недостающие секции — собрать и загрузить. */
    static void frame(Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            CACHE.clear();
            DIRTY.clear();
            Arrays.fill(SLOT, Long.MIN_VALUE);
            placed = false;
        }
        if (level == null) return;
        ensureTexture();
        int half = SECTIONS / 2;
        int nx = (int) Math.floor(cam.x / 16.0 + 0.5) - half;
        int ny = (int) Math.floor(cam.y / 16.0 + 0.5) - half;
        int nz = (int) Math.floor(cam.z / 16.0 + 0.5) - half;
        if (!placed || nx != minSx || ny != minSy || nz != minSz) {
            minSx = nx;
            minSy = ny;
            minSz = nz;
            placed = true;
            CACHE.keySet().removeIf((long k) -> {
                int x = SectionPos.x(k) - minSx, y = SectionPos.y(k) - minSy, z = SectionPos.z(k) - minSz;
                boolean out = x < -1 || y < -1 || z < -1 || x > SECTIONS || y > SECTIONS || z > SECTIONS;
                if (out) DIRTY.remove(k);
                return out;
            });
        }
        int scans = SCANS_PER_FRAME, uploads = UPLOADS_PER_FRAME;
        // Ближние секции первыми: от центра куба наружу.
        for (int ring = 0; ring <= half && uploads > 0; ring++) {
            for (int cz = 0; cz < SECTIONS; cz++) for (int cy = 0; cy < SECTIONS; cy++) for (int cx = 0; cx < SECTIONS; cx++) {
                int r = Math.max(Math.abs(cx - half + (cx < half ? 1 : 0)), Math.max(Math.abs(cy - half + (cy < half ? 1 : 0)), Math.abs(cz - half + (cz < half ? 1 : 0))));
                if (r != ring) continue;
                int sx = minSx + cx, sy = minSy + cy, sz = minSz + cz;
                long k = SectionPos.asLong(sx, sy, sz);
                int slot = Math.floorMod(sx, SECTIONS) + SECTIONS * (Math.floorMod(sy, SECTIONS) + SECTIONS * Math.floorMod(sz, SECTIONS));
                byte[] data = CACHE.get(k);
                boolean stale = data == null || DIRTY.contains(k);
                if (stale && scans > 0 && uploads > 0) {
                    data = scan(sx, sy, sz);
                    CACHE.put(k, data);
                    DIRTY.remove(k);
                    scans--;
                    upload(sx, sy, sz, data);
                    uploads--;
                    SLOT[slot] = k;
                } else if (SLOT[slot] != k && uploads > 0) {
                    // Чужая секция в этом месте текстуры: своё (если уже есть) или пусто, пока не соберётся.
                    upload(sx, sy, sz, data == null ? EMPTY : data);
                    uploads--;
                    SLOT[slot] = k;
                }
            }
        }
    }

    private static byte[] scan(int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return EMPTY;
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return EMPTY;
        LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        if (sec == null || sec.hasOnlyAir()) return EMPTY;
        byte[] out = new byte[4096];
        boolean any = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState s = sec.getBlockState(x, y, z);
            if (s.isAir()) continue;
            int m = MASKS.getInt(s);
            if (m < 0) {
                p.set(x0 + x, y0 + y, z0 + z);
                m = mask(s, p);
                MASKS.put(s, m);
            }
            if (m != 0) {
                out[x + 16 * (y + 16 * z)] = (byte) m;
                any = true;
            }
        }
        return any ? out : EMPTY;
    }

    /** Какие половинки блока держат свет. */
    private static int mask(BlockState s, BlockPos pos) {
        try {
            Block b = s.getBlock();
            if (b instanceof AbstractGlassBlock || b instanceof IronBarsBlock || s.is(Tags.Blocks.GLASS) || s.is(Tags.Blocks.GLASS_PANES)) return 0;
            VoxelShape shape = s.getCollisionShape(level, pos);
            if (shape.isEmpty()) return 0;
            if (Block.isShapeFullBlock(shape)) return 0xFF;
            boolean exact = b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                    || b instanceof SlabBlock || b instanceof StairBlock;
            double need = 0.125 * (exact ? PLATE_FILL : MODEL_FILL);
            double[] vol = new double[8];
            for (AABB box : shape.toAabbs()) {
                for (int i = 0; i < 8; i++) {
                    double ax = (i & 1) * 0.5, ay = (i >> 1 & 1) * 0.5, az = (i >> 2 & 1) * 0.5;
                    double ix = Math.min(box.maxX, ax + 0.5) - Math.max(box.minX, ax);
                    double iy = Math.min(box.maxY, ay + 0.5) - Math.max(box.minY, ay);
                    double iz = Math.min(box.maxZ, az + 0.5) - Math.max(box.minZ, az);
                    if (ix > 0 && iy > 0 && iz > 0) vol[i] += ix * iy * iz;
                }
            }
            int m = 0;
            for (int i = 0; i < 8; i++) if (vol[i] >= need) m |= 1 << i;
            return m;
        } catch (RuntimeException e) {
            return s.canOcclude() ? 0xFF : 0;
        }
    }

    private static void ensureTexture() {
        if (tex != 0) return;
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        ByteBuffer zero = MemoryUtil.memCalloc(SIZE * SIZE * SIZE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE, SIZE, SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, zero);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        MemoryUtil.memFree(zero);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        buf = MemoryUtil.memAlloc(4096);
        FlRenderMod.LOGGER.info("FL Render: объём для фонаря {}³", SIZE);
    }

    private static void upload(int sx, int sy, int sz, byte[] data) {
        buf.clear();
        buf.put(data);
        buf.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SECTIONS) * 16, Math.floorMod(sy, SECTIONS) * 16,
                Math.floorMod(sz, SECTIONS) * 16, 16, 16, 16, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    static int texture() {
        return tex;
    }

    static boolean ready() {
        return tex != 0 && placed && level != null;
    }

    /** Нижний угол куба в блоках. */
    static int minX() {
        return minSx << 4;
    }

    static int minY() {
        return minSy << 4;
    }

    static int minZ() {
        return minSz << 4;
    }

    /** Занята ли половинка (координаты половинок мира: блок * 2 + 0/1). Вне собранного — свободно. */
    private static boolean solid(int hx, int hy, int hz) {
        int bx = hx >> 1, by = hy >> 1, bz = hz >> 1;
        byte[] d = CACHE.get(SectionPos.asLong(bx >> 4, by >> 4, bz >> 4));
        if (d == null || d == EMPTY) return false;
        int v = d[(bx & 15) + 16 * ((by & 15) + 16 * (bz & 15))] & 255;
        return (v >> ((hx & 1) | (hy & 1) << 1 | (hz & 1) << 2) & 1) != 0;
    }

    /**
     * Свободен ли путь от точки к лампе: шаг по половинкам блоков, как в шейдере. Свою начальную половинку не считает,
     * а если она занята (точка на грани неполного блока) — весь свой блок; {@code skipOwnBlock} — весь блок всегда
     * (блок-сущность стоит в своём блоке).
     */
    static boolean clear(double fx, double fy, double fz, double tx, double ty, double tz, boolean skipOwnBlock) {
        double ax = fx * 2, ay = fy * 2, az = fz * 2;
        double dx = tx * 2 - ax, dy = ty * 2 - ay, dz = tz * 2 - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0) return true;
        dx /= len;
        dy /= len;
        dz /= len;
        int cx = (int) Math.floor(ax), cy = (int) Math.floor(ay), cz = (int) Math.floor(az);
        int ownX = cx >> 1, ownY = cy >> 1, ownZ = cz >> 1;
        skipOwnBlock |= solid(cx, cy, cz);
        int stx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sty = dy > 0 ? 1 : dy < 0 ? -1 : 0, stz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double ix = 1.0 / Math.max(Math.abs(dx), 1.0e-5), iy = 1.0 / Math.max(Math.abs(dy), 1.0e-5), iz = 1.0 / Math.max(Math.abs(dz), 1.0e-5);
        double frx = ax - Math.floor(ax), fry = ay - Math.floor(ay), frz = az - Math.floor(az);
        double mx = (dx >= 0 ? 1.0 - frx : frx) * ix, my = (dy >= 0 ? 1.0 - fry : fry) * iy, mz = (dz >= 0 ? 1.0 - frz : frz) * iz;
        for (int i = 0; i < 200; i++) {
            double t;
            if (mx < my && mx < mz) {
                cx += stx;
                t = mx;
                mx += ix;
            } else if (my < mz) {
                cy += sty;
                t = my;
                my += iy;
            } else {
                cz += stz;
                t = mz;
                mz += iz;
            }
            if (t > len - 1.0) return true;
            if (skipOwnBlock && cx >> 1 == ownX && cy >> 1 == ownY && cz >> 1 == ownZ) continue;
            if (solid(cx, cy, cz)) return false;
        }
        return true;
    }
}
