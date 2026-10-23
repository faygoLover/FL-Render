package faygolover.flrender;

import com.mojang.logging.LogUtils;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.client.ClientSetup;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/** FL Render — общая клиентская графика модов семейства. На сервере ничего не делает. */
@Mod(FlRender.MODID)
public final class FlRenderMod {
    public static final Logger LOGGER = LogUtils.getLogger();

    public FlRenderMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) ClientSetup.init(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
