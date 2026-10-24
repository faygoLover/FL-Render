package faygolover.flrender.client;

import faygolover.flrender.FlRenderMod;
import faygolover.flrender.config.Settings;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Oculus / Iris через их API, без жёсткой зависимости: включён ли шейдерпак и идёт ли проход теней. Имя пака — из
 * {@code config/oculus.properties} (в API его нет): паки со своим цветным светом ({@link #coloredPack()}) сами красят
 * ванильные источники и свет в руках, но не знают блоков модов.
 */
public final class Oculus {
    private Oculus() {}

    private static boolean probed;
    private static Object api;
    private static Method inUse;
    private static Method shadowPass;

    private static void probe() {
        if (probed) return;
        probed = true;
        for (String cls : new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> c = Class.forName(cls);
                api = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
                shadowPass = c.getMethod("isRenderingShadowPass");
                return;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Нет Oculus или другой пакет API.
            }
        }
    }

    public static boolean shaderPack() {
        return ask(true);
    }

    public static boolean shadowPass() {
        return ask(false);
    }

    private static boolean ask(boolean pack) {
        probe();
        Method m = pack ? inUse : shadowPass;
        if (m == null) return false;
        try {
            return (Boolean) m.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            FlRenderMod.LOGGER.warn("FL Render: Oculus не отвечает, считаю что шейдерпака нет", e);
            inUse = shadowPass = null;
            return false;
        }
    }

    private static String packName = "";
    private static long packCheckedAt;
    private static long packFileTime = -1;

    /** Включён пак со своим цветным светом (имя содержит строку из {@code colored_light_packs} в flrender.properties). */
    public static boolean coloredPack() {
        if (!shaderPack()) return false;
        String name = packName().toLowerCase(Locale.ROOT);
        for (String part : Settings.coloredLightPacks()) {
            if (!part.isEmpty() && name.contains(part)) return true;
        }
        return false;
    }

    /** Имя включённого шейдерпака (файл), перечитывается не чаще раза в 2 секунды. */
    public static String packName() {
        long now = System.currentTimeMillis();
        if (now - packCheckedAt < 2000) return packName;
        packCheckedAt = now;
        Path f = FMLPaths.CONFIGDIR.get().resolve("oculus.properties");
        if (!Files.exists(f)) f = FMLPaths.CONFIGDIR.get().resolve("iris.properties");
        try {
            long t = Files.getLastModifiedTime(f).toMillis();
            if (t != packFileTime) {
                packFileTime = t;
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                packName = p.getProperty("shaderPack", "");
            }
        } catch (Exception e) {
            packName = "";
        }
        return packName;
    }
}
