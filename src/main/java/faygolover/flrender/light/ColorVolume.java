package faygolover.flrender.light;

import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Объём цветного света вокруг камеры: куб из секций (средне 48, высоко 64, ультра 96 блоков), две 3D-текстуры —
 * подкраска от блоков-источников (+ небесный свет) и добавка от подвижных источников. Каждый кадр (поток отрисовки):
 * дособрать изменившиеся секции (понемногу), запустить пересчёт в фоне, когда что-то поменялось, и загрузить
 * готовое в видеокарту. Сам проход по кадру — {@link faygolover.flrender.render.WorldLight}.
 */
public final class ColorVolume {
    private ColorVolume() {}

    public static final int TINT_UNIT = 5;
    public static final int ADD_UNIT = 6;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FL Render colour light");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private record StaticResult(LightJobs.World world, byte[] tint) {
    }

    private record DynResult(int ox, int oy, int oz, int size, byte[] add) {
    }

    private static final AtomicReference<StaticResult> STATIC_DONE = new AtomicReference<>();
    private static final AtomicReference<DynResult> DYN_DONE = new AtomicReference<>();
    private static volatile boolean staticRunning, dynRunning;

    private static ClientLevel level;
    private static int ox = Integer.MIN_VALUE, oy, oz, size;
    /** Что посчитано (или считается) для статического объёма. */
    private static int jobGeneration = -1, jobOx, jobOy, jobOz, jobSize;
    private static boolean jobSkipVanilla, jobBlocks;
    private static long jobDynHash;
    private static long lastDynNanos;
    /** Последний мир статического расчёта — для подвижных источников. */
    private static LightJobs.World world;

    private static int tintTex, addTex, texSize;
    private static int tintOx, tintOy, tintOz, addOx, addOy, addOz;
    private static boolean tintReady, addReady;
    private static ByteBuffer upload;

    /** Блок изменился (клиент). */
    public static void blockChanged(BlockPos pos) {
        SectionCache.dirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Секция перерисовывается (свет, загрузка чанка). */
    public static void sectionChanged(int sx, int sy, int sz) {
        SectionCache.dirty(sx, sy, sz);
    }

    private static int sizeFor(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 48;
            case HIGH -> 64;
            case ULTRA -> 96;
        };
    }

    private static int sectionsPerFrame(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 6;
            case HIGH -> 10;
            case ULTRA -> 16;
        };
    }

    private static long dynIntervalNanos(Quality q) {
        return switch (q) {
            case LOW, MEDIUM -> 200_000_000L;
            case HIGH -> 100_000_000L;
            case ULTRA -> 33_000_000L;
        };
    }

    /**
     * Раз за кадр: обновить объём. {@code blocks} — светят ли блоки-источники, {@code dyn} — подвижные источники этого
     * кадра. Возвращает false, если рисовать нечего.
     */
    public static boolean frame(Vec3 cam, boolean blocks, List<LightJobs.Dyn> dyn) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            SectionCache.clear();
            world = null;
            tintReady = addReady = false;
            jobGeneration = -1;
            STATIC_DONE.set(null);
            DYN_DONE.set(null);
        }
        if (level == null) return false;
        Quality q = FlRender.quality();
        int s = sizeFor(q);
        int nx = Math.floorDiv((int) Math.floor(cam.x) - s / 2, 16) * 16;
        int ny = Math.floorDiv((int) Math.floor(cam.y) - s / 2, 16) * 16;
        int nz = Math.floorDiv((int) Math.floor(cam.z) - s / 2, 16) * 16;
        if (nx != ox || ny != oy || nz != oz || s != size) {
            ox = nx;
            oy = ny;
            oz = nz;
            size = s;
            SectionCache.trim((ox >> 4) - 2, (oy >> 4) - 2, (oz >> 4) - 2, ((ox + s) >> 4) + 1, ((oy + s) >> 4) + 1, ((oz + s) >> 4) + 1);
        }
        uploadDone();

        // Секции объёма: готовые — в снимок, недостающие — дособрать понемногу.
        int n = size >> 4;
        SectionCache.Data[] sections = new SectionCache.Data[n * n * n];
        int budget = sectionsPerFrame(q), missing = 0;
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            int sx = (ox >> 4) + cx, sy = (oy >> 4) + cy, sz = (oz >> 4) + cz;
            SectionCache.Data d = SectionCache.ready(sx, sy, sz);
            if (d == null && budget > 0) {
                d = SectionCache.build(level, sx, sy, sz);
                budget--;
            }
            if (d == null) missing++;
            sections[cx + n * (cy + n * cz)] = d;
        }
        // Пак со своим цветным светом (Just Colored, Rethinking Voxels...): ванильные источники он красит сам, а наши
        // блоки (ХИС, блоки цвета) не знает и не освещает — их свет добавляем сами. Пак на ванильном свете (Insanity)
        // и без пака — подкраска освещённого, как обычно.
        boolean skipVanilla = faygolover.flrender.client.Oculus.coloredPack();
        if (skipVanilla && blocks) packSources(sections, n, dyn);
        boolean staticStale = jobGeneration != SectionCache.generation() || jobOx != ox || jobOy != oy || jobOz != oz
                || jobSize != size || jobSkipVanilla != skipVanilla || jobBlocks != blocks;
        if (missing == 0 && staticStale && !staticRunning) {
            jobGeneration = SectionCache.generation();
            jobOx = ox;
            jobOy = oy;
            jobOz = oz;
            jobSize = size;
            jobSkipVanilla = skipVanilla;
            jobBlocks = blocks;
            int fox = ox, foy = oy, foz = oz, fs = size;
            staticRunning = true;
            WORKER.execute(() -> {
                try {
                    LightJobs.World w = LightJobs.world(fox, foy, foz, fs, sections);
                    byte[] tint = LightJobs.statics(w, sections, !blocks || skipVanilla);
                    STATIC_DONE.set(new StaticResult(w, tint));
                } catch (Throwable t) {
                    FlRenderMod.LOGGER.error("FL Render: расчёт цветного света упал", t);
                } finally {
                    staticRunning = false;
                }
            });
        }

        // Подвижные источники — на последнем готовом мире, не чаще, чем велит качество.
        LightJobs.World w = world;
        if (w != null && w.ox() == ox && w.oy() == oy && w.oz() == oz && w.size() == size && !dynRunning) {
            long hash = hash(dyn) * 31 + System.identityHashCode(w);
            long now = System.nanoTime();
            if (hash != jobDynHash && now - lastDynNanos >= dynIntervalNanos(q)) {
                jobDynHash = hash;
                lastDynNanos = now;
                List<LightJobs.Dyn> copy = new ArrayList<>(dyn);
                dynRunning = true;
                WORKER.execute(() -> {
                    try {
                        byte[] add = copy.isEmpty() ? null : LightJobs.dynamics(w, copy);
                        DYN_DONE.set(new DynResult(w.ox(), w.oy(), w.oz(), w.size(), add));
                    } catch (Throwable t) {
                        FlRenderMod.LOGGER.error("FL Render: расчёт подвижного света упал", t);
                    } finally {
                        dynRunning = false;
                    }
                });
            }
        }
        // Пока новый объём считается, рисуем прежний (на его месте) — свет не мигает при ходьбе.
        return tintReady;
    }

    /** Сила света наших блоков-источников поверх пака со своим цветным светом. */
    private static final float PACK_BLOCK_STRENGTH = 1.6f;

    /** Блоки-источники модов (не ванильные) — как подвижные источники: свет добавляется сам. */
    private static void packSources(SectionCache.Data[] sections, int n, List<LightJobs.Dyn> dyn) {
        for (int cz = 0; cz < n; cz++) for (int cy = 0; cy < n; cy++) for (int cx = 0; cx < n; cx++) {
            SectionCache.Data d = sections[cx + n * (cy + n * cz)];
            if (d == null) continue;
            for (int k = 0; k < d.srcPacked().length; k++) {
                int p = d.srcPacked()[k];
                if ((p >> 16 & 1) != 0) continue;
                int local = p & 4095, level = p >> 12 & 15, rgb = d.srcRgb()[k];
                double x = ox + (cx << 4) + (local & 15) + 0.5, y = oy + (cy << 4) + (local >> 8) + 0.5, z = oz + (cz << 4) + (local >> 4 & 15) + 0.5;
                float top = Math.max(1, Math.max(rgb >> 16 & 255, Math.max(rgb >> 8 & 255, rgb & 255)));
                dyn.add(new LightJobs.Dyn(x, y, z, level, (rgb >> 16 & 255) / top, (rgb >> 8 & 255) / top, (rgb & 255) / top,
                        PACK_BLOCK_STRENGTH, 0, 0, 0, 0, false));
            }
        }
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

    private static void uploadDone() {
        StaticResult sr = STATIC_DONE.getAndSet(null);
        if (sr != null) {
            LightJobs.World w = sr.world();
            ensureTextures(w.size());
            upload(tintTex, w.size(), sr.tint());
            tintOx = w.ox();
            tintOy = w.oy();
            tintOz = w.oz();
            tintReady = true;
            world = w;
            jobDynHash = 0;
        }
        DynResult dr = DYN_DONE.getAndSet(null);
        if (dr != null) {
            if (dr.add() == null) {
                addReady = false;
            } else if (dr.size() == texSize) {
                upload(addTex, dr.size(), dr.add());
                addOx = dr.ox();
                addOy = dr.oy();
                addOz = dr.oz();
                addReady = true;
            }
        }
    }

    private static void ensureTextures(int s) {
        if (tintTex != 0 && texSize == s) return;
        if (tintTex == 0) {
            tintTex = GL11.glGenTextures();
            addTex = GL11.glGenTextures();
        }
        for (int tex : new int[]{tintTex, addTex}) {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, s, s, s, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        texSize = s;
        addReady = false;
        if (upload != null) MemoryUtil.memFree(upload);
        upload = MemoryUtil.memAlloc(s * s * s * 4);
    }

    private static void upload(int tex, int s, byte[] data) {
        upload.clear();
        upload.put(data, 0, Math.min(data.length, upload.capacity()));
        upload.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, s, s, s, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    /** Есть ли добавка подвижных источников под текущий объём. */
    public static boolean hasAdd() {
        return addReady && addOx == tintOx && addOy == tintOy && addOz == tintOz;
    }

    /** Угол объёма относительно камеры. */
    public static Vec3 originRelative(Vec3 cam) {
        return new Vec3(tintOx - cam.x, tintOy - cam.y, tintOz - cam.z);
    }

    public static int size() {
        return texSize;
    }

    /** Привязать 3D-текстуры к своим блокам (до рисования шейдером). */
    public static void bind() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TINT_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tintTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + ADD_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, hasAdd() ? addTex : tintTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    public static void unbind() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TINT_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + ADD_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }
}
