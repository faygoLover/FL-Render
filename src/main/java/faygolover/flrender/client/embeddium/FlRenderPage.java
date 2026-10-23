package faygolover.flrender.client.embeddium;

import com.google.common.collect.ImmutableList;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Technique;
import faygolover.flrender.api.Toggle;
import faygolover.flrender.client.AutoDetect;
import faygolover.flrender.config.Settings;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Вкладка «FL Render»: общий пресет качества и по группе на каждый мод с его техниками
 * («Авто» — как велит пресет, «Вкл», «Выкл»).
 */
final class FlRenderPage {
    private FlRenderPage() {}

    private static final OptionStorage<Object> STORAGE = new OptionStorage<>() {
        private final Object data = new Object();

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public void save() {
            Settings.save();
        }
    };

    static OptionPage create() {
        List<OptionGroup> groups = new ArrayList<>();
        Component[] qualityNames = new Component[Quality.values().length];
        for (Quality q : Quality.values()) qualityNames[q.ordinal()] = AutoDetect.name(q);
        Component detected = Settings.detected().isEmpty() ? Component.empty()
                : Component.literal("\n\n").append(Component.translatable("flrender.option.detected", Settings.detected()));
        groups.add(OptionGroup.createBuilder()
                .setId(new ResourceLocation(FlRender.MODID, "general"))
                .add(OptionImpl.createBuilder(Quality.class, STORAGE)
                        .setId(new ResourceLocation(FlRender.MODID, "quality"))
                        .setName(Component.translatable("flrender.option.quality"))
                        .setTooltip(Component.translatable("flrender.option.quality.tooltip").append(detected))
                        .setControl(o -> new CyclingControl<>(o, Quality.class, qualityNames))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((s, v) -> Settings.setQuality(v), s -> Settings.quality())
                        .build())
                .build());

        Component[] toggleNames = new Component[Toggle.values().length];
        for (Toggle t : Toggle.values()) {
            toggleNames[t.ordinal()] = Component.translatable("flrender.toggle." + t.name().toLowerCase(Locale.ROOT));
        }
        Map<String, List<Technique>> byMod = new LinkedHashMap<>();
        for (Technique t : FlRender.techniques()) byMod.computeIfAbsent(t.modid(), k -> new ArrayList<>()).add(t);
        for (Map.Entry<String, List<Technique>> e : byMod.entrySet()) {
            OptionGroup.Builder g = OptionGroup.createBuilder().setId(new ResourceLocation(FlRender.MODID, "mod_" + safe(e.getKey())));
            for (Technique t : e.getValue()) {
                Component tip = Component.translatable(t.tooltipKey()).append("\n\n")
                        .append(Component.translatable("flrender.option.auto_from", AutoDetect.name(t.from()), modName(t.modid())));
                g.add(OptionImpl.createBuilder(Toggle.class, STORAGE)
                        .setId(new ResourceLocation(FlRender.MODID, safe(t.id())))
                        .setName(Component.translatable(t.nameKey()))
                        .setTooltip(tip)
                        .setControl(o -> new CyclingControl<>(o, Toggle.class, toggleNames))
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((s, v) -> t.setToggle(v), s -> t.toggle())
                        .build());
            }
            groups.add(g.build());
        }
        return new OptionPage(Component.literal("FL Render"), ImmutableList.copyOf(groups));
    }

    private static String modName(String modid) {
        return ModList.get().getModContainerById(modid).map(c -> c.getModInfo().getDisplayName()).orElse(modid);
    }

    /** Путь ResourceLocation: только [a-z0-9_.-]. */
    private static String safe(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
