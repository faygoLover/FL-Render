package faygolover.flrender.light;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import faygolover.flrender.FlRenderMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Цвета источников света: блоки (светят, если у состояния есть свой ванильный свет) и предметы (в руках, брошенные,
 * летящие). Файл {@code config/flrender_colors.json} — ваниль по умолчанию, сюда же дописываются блоки и предметы
 * других модов (id, цвет {@code #RRGGBB}, уровень 1–15 — по умолчанию ванильный свет блока). Моды могут добавить свои
 * из кода ({@link #block}, {@link #item}). Предмет-блок без своей строки берёт цвет блока.
 * <p>
 * {@code "shaders": false} — с включённым шейдерпаком не светить цветом (пак сам красит ванильные источники);
 * по умолчанию так у всего из {@code minecraft:}, у остальных — светить.
 */
public final class ColorTable {
    private ColorTable() {}

    /** Источник: цвет 0–1, уровень (0 — как ванильный свет состояния), светить ли поверх шейдерпака. */
    public record Entry(float r, float g, float b, int level, boolean withShaders) {
    }

    /** 2 (30.10): таблица по умолчанию из галереи источников света ({@code assets/flrender/default_colors.json}). */
    private static final int VERSION = 2;
    /** Цвета по умолчанию версии 1 — такие строки в файле игрока считаются не правленными и обновляются. */
    private static final Map<String, String> V1 = Map.ofEntries(
            Map.entry("minecraft:torch", "#FF9A3C"), Map.entry("minecraft:wall_torch", "#FF9A3C"),
            Map.entry("minecraft:soul_torch", "#3CC8FF"), Map.entry("minecraft:soul_wall_torch", "#3CC8FF"),
            Map.entry("minecraft:redstone_torch", "#FF2A1A"), Map.entry("minecraft:redstone_wall_torch", "#FF2A1A"),
            Map.entry("minecraft:redstone_wire", "#FF2A1A"), Map.entry("minecraft:lantern", "#FFB050"),
            Map.entry("minecraft:soul_lantern", "#3CC8FF"), Map.entry("minecraft:lava", "#FF5A10"),
            Map.entry("minecraft:fire", "#FF7A20"), Map.entry("minecraft:soul_fire", "#3CC8FF"),
            Map.entry("minecraft:campfire", "#FF7A20"), Map.entry("minecraft:soul_campfire", "#3CC8FF"),
            Map.entry("minecraft:glowstone", "#FFD080"));
    /** Явно без цвета ({@code "color": "none"}) — снимает и цвет, заданный модом из кода. */
    private static final java.util.Set<String> NONE_BLOCKS = new java.util.HashSet<>(), NONE_ITEMS = new java.util.HashSet<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<String, Entry> BLOCK_IDS = new LinkedHashMap<>();
    private static final Map<String, Entry> ITEM_IDS = new LinkedHashMap<>();
    private static final Map<Block, Entry> CODE_BLOCKS = new IdentityHashMap<>();
    private static final Map<Item, Entry> CODE_ITEMS = new IdentityHashMap<>();
    private static volatile Map<Block, Entry> blocks;
    private static volatile Map<Item, Entry> items;
    private static boolean loaded;

    /** Из кода мода (клиент, при настройке): блок светит цветом {@code rgb}; {@code level} 0 — ванильный свет. */
    public static synchronized void block(Block block, int rgb, int level, boolean withShaders) {
        CODE_BLOCKS.put(block, entry(rgb, level, withShaders));
        blocks = null;
    }

    /** Из кода мода: предмет светит цветом {@code rgb} с уровнем {@code level} (в руках, брошенный). */
    public static synchronized void item(Item item, int rgb, int level, boolean withShaders) {
        CODE_ITEMS.put(item, entry(rgb, level, withShaders));
        items = null;
    }

    private static Entry entry(int rgb, int level, boolean withShaders) {
        return new Entry((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, Math.max(0, Math.min(15, level)), withShaders);
    }

    /** Источник для состояния блока, если оно светит: [r, g, b] и уровень. Null — не цветной источник. */
    @Nullable
    /** Почти белый цвет (насыщенность меньше 15 %): подкраска едва заметна. */
    public static boolean subtle(Entry e) {
        float mx = Math.max(e.r(), Math.max(e.g(), e.b())), mn = Math.min(e.r(), Math.min(e.g(), e.b()));
        return mx <= 0 || (mx - mn) / mx < 0.15f;
    }

    public static Entry of(BlockState state) {
        return blocks().get(state.getBlock());
    }

    /** Уровень света состояния для этого источника (0 — сейчас не светит). */
    public static int level(BlockState state, Entry e) {
        if (state.getBlock() instanceof RedStoneWireBlock) {
            int power = state.getValue(RedStoneWireBlock.POWER);
            return power <= 0 ? 0 : Math.max(1, Math.round(power * (e.level() > 0 ? e.level() : 6) / 15f));
        }
        int emission = state.getLightEmission();
        if (emission <= 0) return 0;
        return e.level() > 0 ? Math.min(e.level(), 15) : Math.min(emission, 15);
    }

    /** Источник для предмета (в руке, на земле, летящий) или null. Уровень уже посчитан (>0). */
    @Nullable
    public static Entry of(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        Entry e = items().get(item);
        // Предмет без уровня в строке — средний свет, как у факела в руке.
        if (e != null) return e.level() > 0 ? e : new Entry(e.r(), e.g(), e.b(), 12, e.withShaders());
        if (item instanceof BlockItem bi) {
            Entry b = blocks().get(bi.getBlock());
            if (b == null) return null;
            int level = b.level() > 0 ? b.level() : bi.getBlock().defaultBlockState().getLightEmission();
            if (bi.getBlock() instanceof RedStoneWireBlock) level = 0;
            return level <= 0 ? null : new Entry(b.r(), b.g(), b.b(), Math.min(level, 15), b.withShaders());
        }
        return null;
    }

    private static Map<Block, Entry> blocks() {
        Map<Block, Entry> m = blocks;
        if (m == null) {
            synchronized (ColorTable.class) {
                load();
                m = new IdentityHashMap<>(CODE_BLOCKS);
                for (Map.Entry<String, Entry> e : BLOCK_IDS.entrySet()) {
                    Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(e.getKey()));
                    if (b != null && b != Blocks.AIR) m.put(b, e.getValue());
                }
                for (String id : NONE_BLOCKS) {
                    Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
                    if (b != null) m.remove(b);
                }
                blocks = m;
            }
        }
        return m;
    }

    private static Map<Item, Entry> items() {
        Map<Item, Entry> m = items;
        if (m == null) {
            synchronized (ColorTable.class) {
                load();
                m = new IdentityHashMap<>(CODE_ITEMS);
                for (Map.Entry<String, Entry> e : ITEM_IDS.entrySet()) {
                    Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                    if (i != null && i != net.minecraft.world.item.Items.AIR) m.put(i, e.getValue());
                }
                for (String id : NONE_ITEMS) {
                    Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
                    if (i != null) m.remove(i);
                }
                items = m;
            }
        }
        return m;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = FMLPaths.CONFIGDIR.get().resolve("flrender_colors.json");
        JsonObject root = null;
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(r, JsonObject.class);
            } catch (IOException | RuntimeException e) {
                FlRenderMod.LOGGER.warn("FL Render: не прочитать {}, беру цвета по умолчанию", f, e);
            }
        }
        JsonObject defaults = Defaults.json();
        boolean write = root == null;
        if (root == null) {
            root = defaults;
        } else if (!root.has("version") || root.get("version").getAsInt() < VERSION) {
            // Новые строки по умолчанию дописываются, прежние строки по умолчанию обновляются, свои правки игрока остаются.
            for (String section : new String[]{"blocks", "items"}) {
                if (!root.has(section)) root.add(section, new JsonObject());
                JsonObject mine = root.getAsJsonObject(section);
                for (Map.Entry<String, JsonElement> e : defaults.getAsJsonObject(section).entrySet()) {
                    JsonElement old = mine.get(e.getKey());
                    boolean stock = old != null && old.isJsonObject() && old.getAsJsonObject().size() == 1 && old.getAsJsonObject().has("color")
                            && old.getAsJsonObject().get("color").getAsString().equalsIgnoreCase(V1.getOrDefault(e.getKey(), "-"));
                    if (old == null || stock) mine.add(e.getKey(), e.getValue());
                }
            }
            root.addProperty("version", VERSION);
            write = true;
        }
        read(root.getAsJsonObject("blocks"), BLOCK_IDS, NONE_BLOCKS);
        read(root.getAsJsonObject("items"), ITEM_IDS, NONE_ITEMS);
        if (write) {
            try {
                Files.createDirectories(f.getParent());
                try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, w);
                }
            } catch (IOException e) {
                FlRenderMod.LOGGER.warn("FL Render: не записать {}", f, e);
            }
        }
    }

    private static void read(@Nullable JsonObject section, Map<String, Entry> out, java.util.Set<String> none) {
        if (section == null) return;
        for (Map.Entry<String, JsonElement> e : section.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            try {
                String hex = o.get("color").getAsString().trim();
                if (hex.equalsIgnoreCase("none")) {
                    none.add(e.getKey().toLowerCase(Locale.ROOT));
                    continue;
                }
                if (hex.startsWith("#")) hex = hex.substring(1);
                int rgb = Integer.parseInt(hex, 16);
                int level = o.has("level") ? o.get("level").getAsInt() : 0;
                String id = e.getKey().toLowerCase(Locale.ROOT);
                boolean shaders = o.has("shaders") ? o.get("shaders").getAsBoolean() : !id.startsWith("minecraft:");
                out.put(id, entry(rgb, level, shaders));
            } catch (RuntimeException ex) {
                FlRenderMod.LOGGER.warn("FL Render: строка цвета {} не разобрана: {}", e.getKey(), o);
            }
        }
    }
}
