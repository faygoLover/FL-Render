package faygolover.flrender.client;

import com.mojang.blaze3d.platform.GlUtil;
import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.Quality;
import faygolover.flrender.config.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определялка на первом запуске: картошка, нормальный ПК или «NASA-PC».
 * <ol>
 *   <li>При загрузке игры, если пресет ещё не выбран: видеокарта (имя из драйвера, видеопамять, если драйвер её
 *       называет), ядра процессора и память Java → пресет.</li>
 *   <li>В первой игре: через ~10 с в мире 10 с замера FPS; если меньше 25 — пресет на ступень ниже. Один раз и только
 *       если пресет выбрала определялка, а не игрок.</li>
 * </ol>
 * Что выбрано — сообщение в чат при входе в мир.
 */
public final class AutoDetect {
    private AutoDetect() {}

    private static final int WARMUP_TICKS = 200;
    private static final int SAMPLE_TICKS = 200;
    private static final int LOW_FPS = 25;

    private static final int GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FC;

    private static final Pattern NV_RTX = Pattern.compile("rtx\\s*(\\d{4})");
    private static final Pattern NV_GTX = Pattern.compile("gtx\\s*(\\d{3,4})");
    private static final Pattern AMD_RX = Pattern.compile("rx\\s*(\\d{3,4})");

    /** Только что выбран пресет — сказать игроку при входе в мир. */
    private static boolean announce;
    private static int ticks;
    private static long frames;
    private static int lastFps;

    /** На потоке отрисовки при загрузке игры. */
    static void onStartup() {
        if (!Settings.qualityUnset()) return;
        String renderer = safe(GlUtil.getRenderer());
        long vramMb = vramMb();
        int cores = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        Quality byGpu = byName(renderer.toLowerCase(Locale.ROOT));
        Quality q = byGpu;
        if (vramMb > 0) q = Quality.min(q, byVram(vramMb));
        if (cores <= 2 || heapMb < 3000) q = Quality.LOW;
        else if (cores <= 4 || heapMb < 5000) q = Quality.min(q, Quality.MEDIUM);
        String detected = renderer + ", VRAM " + (vramMb > 0 ? vramMb + " MB" : "?") + ", " + cores + " cores, heap " + heapMb + " MB";
        Settings.setAutoQuality(q, detected);
        Settings.save();
        announce = true;
        FlRenderMod.LOGGER.info("FL Render: первый запуск — качество {} ({})", q, detected);
    }

    static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ticks = 0;
            frames = 0;
            return;
        }
        if (announce && ticks == 40) {
            announce = false;
            mc.player.displayClientMessage(Component.translatable("flrender.detect.chosen", name(Settings.quality())), false);
        }
        if (!Settings.qualityAuto() || Settings.fpsChecked()) {
            ticks++;
            return;
        }
        // Пауза или окно свёрнуто — замер не идёт.
        if (mc.isPaused() || !mc.isWindowActive()) return;
        ticks++;
        if (ticks <= WARMUP_TICKS) return;
        if (ticks % 20 == 0) {
            frames += mc.getFps();
            lastFps = mc.getFps();
        }
        if (ticks < WARMUP_TICKS + SAMPLE_TICKS) return;
        long avg = frames / (SAMPLE_TICKS / 20);
        Settings.setFpsChecked();
        Quality q = Settings.quality();
        if (avg < LOW_FPS && q != Quality.LOW) {
            Settings.setAutoQuality(q.lower(), Settings.detected() + ", fps " + avg);
            mc.player.displayClientMessage(Component.translatable("flrender.detect.lowered", avg, name(q.lower())), false);
            FlRenderMod.LOGGER.info("FL Render: в среднем {} FPS — качество {} → {}", avg, q, q.lower());
        } else {
            FlRenderMod.LOGGER.info("FL Render: в среднем {} FPS (последний {}), качество {} остаётся", avg, lastFps, q);
        }
        Settings.save();
    }

    public static Component name(Quality q) {
        return Component.translatable("flrender.quality." + q.name().toLowerCase(Locale.ROOT));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Видеопамять, МБ (0 — драйвер не говорит). NVIDIA — вся, AMD — свободная под текстуры (нижняя оценка). */
    private static long vramMb() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps.GL_NVX_gpu_memory_info) return GL11.glGetInteger(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX) / 1024L;
            if (caps.GL_ATI_meminfo) {
                int[] v = new int[4];
                GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, v);
                return v[0] / 1024L;
            }
        } catch (RuntimeException e) {
            FlRenderMod.LOGGER.debug("FL Render: видеопамять не узнать", e);
        }
        return 0;
    }

    private static Quality byVram(long mb) {
        if (mb < 1500) return Quality.LOW;
        if (mb < 3500) return Quality.MEDIUM;
        if (mb < 7500) return Quality.HIGH;
        return Quality.ULTRA;
    }

    /** По имени видеокарты. Неизвестная — средне. */
    static Quality byName(String r) {
        if (r.contains("llvmpipe") || r.contains("software") || r.contains("swiftshader") || r.contains("basic render")
                || r.contains("gdi generic")) return Quality.LOW;
        Matcher m = NV_RTX.matcher(r);
        if (m.find()) {
            int cls = Integer.parseInt(m.group(1)) % 100;
            return cls >= 70 ? Quality.ULTRA : Quality.HIGH;
        }
        m = NV_GTX.matcher(r);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            int cls = n % 100;
            if (n >= 1000) return cls >= 60 ? Quality.HIGH : Quality.MEDIUM;
            return cls >= 60 ? Quality.MEDIUM : Quality.LOW;
        }
        if (r.contains("geforce") && (r.contains(" mx") || r.contains(" gt "))) return Quality.LOW;
        if (r.contains("quadro") || r.contains("rtx a")) return Quality.HIGH;
        m = AMD_RX.matcher(r);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1000) return n >= 570 ? Quality.MEDIUM : Quality.LOW;
            int gen = n / 1000, cls = n % 1000 / 100;
            if (gen >= 6 && cls >= 8) return Quality.ULTRA;
            if (gen >= 5 && cls >= 6) return Quality.HIGH;
            return Quality.MEDIUM;
        }
        // Встроенная графика AMD («Radeon Graphics», Vega) и Intel (HD, UHD, Iris).
        if (r.contains("radeon") || r.contains("vega")) return Quality.LOW;
        if (r.contains("intel")) {
            if (r.contains("arc")) return r.contains("a7") || r.contains("b5") ? Quality.HIGH : Quality.MEDIUM;
            return Quality.LOW;
        }
        return Quality.MEDIUM;
    }
}
