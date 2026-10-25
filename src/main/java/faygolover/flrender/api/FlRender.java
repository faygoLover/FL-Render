package faygolover.flrender.api;

import faygolover.flrender.client.Oculus;
import faygolover.flrender.config.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Точка входа для модов. Только для клиентского кода.
 * <ul>
 *   <li>{@link #technique} — зарегистрировать свою технику (статически или в клиентской настройке мода);</li>
 *   <li>{@link #quality()} — общий пресет качества;</li>
 *   <li>{@link #shaderPack()} / {@link #shadowPass()} — Oculus;</li>
 *   <li>{@link #flashlightLight()} — светит ли сейчас настоящий свет фонаря.</li>
 * </ul>
 * Проходы — в {@code faygolover.flrender.render}: LatePass, SceneDepth, WorldLight, PostFx, ScreenPass, CameraPose.
 */
public final class FlRender {
    private FlRender() {}

    public static final String MODID = "flrender";

    private static final Map<String, Technique> TECHNIQUES = new LinkedHashMap<>();

    /** Техника мода {@code modid}; повторный вызов с тем же ключом вернёт ту же. */
    public static synchronized Technique technique(String modid, String key, Quality from) {
        return TECHNIQUES.computeIfAbsent(modid + "." + key, k -> new Technique(modid, key, from));
    }

    /** Все зарегистрированные техники в порядке регистрации. */
    public static synchronized List<Technique> techniques() {
        return Collections.unmodifiableList(new ArrayList<>(TECHNIQUES.values()));
    }

    public static Quality quality() {
        return Settings.quality();
    }

    /** Шейдерпак Oculus сейчас рисует мир. */
    public static boolean shaderPack() {
        return Oculus.shaderPack();
    }

    /**
     * Настоящий свет фонаря Survivor's Arsenal сейчас включён: блоки освещаются при рисовании, сущности в луче — как
     * у факела (техника {@code flrender.flashlight}, без шейдерпака, шейдер чанков Embeddium дописан). Экранному кругу
     * фонаря тогда не нужно вытягивать цвет из темноты — только подчеркнуть пятно.
     */
    public static boolean flashlightLight() {
        return faygolover.flrender.flashlight.Flashlight.enabled() && faygolover.flrender.flashlight.ChunkPatch.patched();
    }

    /** Сейчас идёт проход теней шейдерпака. */
    public static boolean shadowPass() {
        return Oculus.shadowPass();
    }
}
