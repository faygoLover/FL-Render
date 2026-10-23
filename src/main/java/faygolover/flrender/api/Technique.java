package faygolover.flrender.api;

import faygolover.flrender.config.Settings;

/**
 * Одна графическая техника мода (например, «объёмный дым» аномалий). Включена, если игрок включил её сам или
 * ({@link Toggle#AUTO}) качество не ниже {@link #from()}. Подписи во вкладке настроек — из lang мода:
 * {@code flrender.technique.<modid>.<key>} и {@code ...tooltip}.
 */
public final class Technique {
    private final String modid;
    private final String key;
    private final Quality from;

    Technique(String modid, String key, Quality from) {
        this.modid = modid;
        this.key = key;
        this.from = from;
    }

    public String modid() {
        return modid;
    }

    public String key() {
        return key;
    }

    /** С какого качества включена сама. */
    public Quality from() {
        return from;
    }

    /** Ключ в файле настроек и основа ключей перевода. */
    public String id() {
        return modid + "." + key;
    }

    public String nameKey() {
        return "flrender.technique." + id();
    }

    public String tooltipKey() {
        return nameKey() + ".tooltip";
    }

    public Toggle toggle() {
        return Settings.toggle(id());
    }

    public void setToggle(Toggle t) {
        Settings.setToggle(id(), t);
    }

    /** Рисовать ли этой техникой сейчас. */
    public boolean on() {
        Toggle t = toggle();
        return t == Toggle.ON || (t == Toggle.AUTO && Settings.quality().atLeast(from));
    }
}
