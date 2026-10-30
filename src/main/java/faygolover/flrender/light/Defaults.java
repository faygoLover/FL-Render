package faygolover.flrender.light;

import com.google.gson.JsonObject;

/** Цвета по умолчанию — только основные и самые частые ванильные источники; остальное дописывается в файл. */
final class Defaults {
    private Defaults() {}

    private static final String TORCH = "#FF9A3C";
    private static final String SOUL = "#3CC8FF";
    private static final String REDSTONE = "#FF2A1A";
    private static final String LANTERN = "#FFB050";
    private static final String FIRE = "#FF7A20";
    private static final String LAVA = "#FF5A10";

    /** Таблица из галереи источников света (gallery/export.py) в jar; без неё — короткий список ниже. */
    static JsonObject json() {
        try (var in = Defaults.class.getResourceAsStream("/assets/flrender/default_colors.json")) {
            if (in != null) {
                JsonObject root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                root.addProperty("_help", HELP);
                return root;
            }
        } catch (Exception e) {
            faygolover.flrender.FlRenderMod.LOGGER.warn("FL Render: таблица цветов по умолчанию не прочитана", e);
        }
        return fallback();
    }

    private static final String HELP = "Coloured light sources. blocks/items: id -> {color: #RRGGBB or none, level: 1-15 (optional, default = the block's own light),"
            + " shaders: true/false (optional: also with a shader pack on; default false for minecraft:, true for other mods)}."
            + " The hue is the colour, its brightness is the strength (darker = weaker tint); none = no colour, white light."
            + " A block's item (torch in hand) takes the block's colour.";

    private static JsonObject fallback() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("_help", "Coloured light sources. blocks/items: id -> {color: #RRGGBB, level: 1-15 (optional, default = the block's own light),"
                + " shaders: true/false (optional: also with a shader pack on; default false for minecraft:, true for other mods)}."
                + " A block's item (torch in hand) takes the block's colour.");
        JsonObject b = new JsonObject();
        put(b, "minecraft:torch", TORCH);
        put(b, "minecraft:wall_torch", TORCH);
        put(b, "minecraft:soul_torch", SOUL);
        put(b, "minecraft:soul_wall_torch", SOUL);
        put(b, "minecraft:redstone_torch", REDSTONE);
        put(b, "minecraft:redstone_wall_torch", REDSTONE);
        put(b, "minecraft:redstone_wire", REDSTONE);
        put(b, "minecraft:lantern", LANTERN);
        put(b, "minecraft:soul_lantern", SOUL);
        put(b, "minecraft:lava", LAVA);
        put(b, "minecraft:fire", FIRE);
        put(b, "minecraft:soul_fire", SOUL);
        put(b, "minecraft:campfire", FIRE);
        put(b, "minecraft:soul_campfire", SOUL);
        put(b, "minecraft:glowstone", "#FFD080");
        root.add("blocks", b);
        JsonObject i = new JsonObject();
        JsonObject lava = new JsonObject();
        lava.addProperty("color", LAVA);
        lava.addProperty("level", 15);
        i.add("minecraft:lava_bucket", lava);
        root.add("items", i);
        return root;
    }

    private static void put(JsonObject o, String id, String color) {
        JsonObject e = new JsonObject();
        e.addProperty("color", color);
        o.add(id, e);
    }
}
