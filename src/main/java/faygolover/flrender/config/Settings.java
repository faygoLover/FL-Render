package faygolover.flrender.config;

import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Toggle;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * {@code config/flrender.properties} — свой файл, не ForgeConfigSpec: техники регистрируют другие моды, и список
 * заранее не известен. Пишется при изменении из вкладки настроек и после автоопределения.
 */
public final class Settings {
    private Settings() {}

    private static final String QUALITY = "quality";
    /** Пресет выбран автоопределением (игрок его не трогал) — проверка FPS ещё может его понизить. */
    private static final String AUTO = "quality_auto";
    private static final String FPS_CHECKED = "fps_checked";
    private static final String DETECTED = "detected";
    private static final String TECHNIQUE = "technique.";

    private static Properties props;
    /** Значения, прочитанные на каждом кадре, — без синхронизации и разбора строк. */
    private static volatile Quality quality;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("flrender.properties");
    }

    private static synchronized Properties props() {
        if (props == null) {
            props = new Properties();
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    props.load(r);
                } catch (IOException | IllegalArgumentException e) {
                    FlRenderMod.LOGGER.warn("FL Render: не прочитать {}, настройки по умолчанию", f, e);
                }
            }
        }
        return props;
    }

    public static synchronized void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                props().store(w, "FL Render: graphics quality (LOW, MEDIUM, HIGH, ULTRA) and techniques (AUTO, ON, OFF)."
                        + " In game: Options > Video Settings > FL Render.");
            }
        } catch (IOException e) {
            FlRenderMod.LOGGER.warn("FL Render: не записать {}", f, e);
        }
    }

    /** Пресет ещё не выбран ни игроком, ни автоопределением (первый запуск). */
    public static boolean qualityUnset() {
        return !props().containsKey(QUALITY);
    }

    public static Quality quality() {
        Quality q = quality;
        if (q == null) quality = q = parse(Quality.class, props().getProperty(QUALITY), Quality.HIGH);
        return q;
    }

    /** Пресет, выбранный игроком: автоопределение и проверка FPS его больше не трогают. */
    public static synchronized void setQuality(Quality q) {
        props().setProperty(QUALITY, q.name());
        props().setProperty(AUTO, "false");
        quality = q;
    }

    /** Пресет от автоопределения. */
    public static synchronized void setAutoQuality(Quality q, String detected) {
        props().setProperty(QUALITY, q.name());
        props().setProperty(AUTO, "true");
        props().setProperty(DETECTED, detected);
        quality = q;
    }

    public static boolean qualityAuto() {
        return Boolean.parseBoolean(props().getProperty(AUTO, "false"));
    }

    public static String detected() {
        return props().getProperty(DETECTED, "");
    }

    public static boolean fpsChecked() {
        return Boolean.parseBoolean(props().getProperty(FPS_CHECKED, "false"));
    }

    public static synchronized void setFpsChecked() {
        props().setProperty(FPS_CHECKED, "true");
    }

    public static Toggle toggle(String id) {
        return parse(Toggle.class, props().getProperty(TECHNIQUE + id), Toggle.AUTO);
    }

    public static synchronized void setToggle(String id, Toggle t) {
        if (t == Toggle.AUTO) props().remove(TECHNIQUE + id);
        else props().setProperty(TECHNIQUE + id, t.name());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
