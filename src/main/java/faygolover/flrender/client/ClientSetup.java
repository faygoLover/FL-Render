package faygolover.flrender.client;

import faygolover.flrender.FlRenderMod;
import faygolover.flrender.flashlight.Flashlight;
import faygolover.flrender.render.FlShaders;
import faygolover.flrender.render.LatePass;
import faygolover.flrender.render.WorldLight;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Клиент: шейдеры, проходы, определялка, вкладка в настройках графики Embeddium. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(FlShaders::onRegisterShaders);
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(AutoDetect::onStartup));
        MinecraftForge.EVENT_BUS.addListener(AutoDetect::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LatePass::onRenderLevelStage);
        WorldLight.init();
        Flashlight.init();
        MinecraftForge.EVENT_BUS.addListener(Flashlight::onRenderLevelStage);
        if (ModList.get().isLoaded("embeddium")) {
            try {
                faygolover.flrender.client.embeddium.EmbeddiumCompat.register();
            } catch (LinkageError e) {
                FlRenderMod.LOGGER.warn("FL Render: вкладка в настройках Embeddium не встала (другая версия?)", e);
            }
        }
    }
}
