package faygolover.flrender.client.embeddium;

import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;

/** Своя вкладка в «Настройках графики» Embeddium (как у True Darkness, Sodium Extras). Грузится, только если Embeddium есть. */
public final class EmbeddiumCompat {
    private EmbeddiumCompat() {}

    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(e -> e.addPage(FlRenderPage.create()));
    }
}
