package faygolover.flrender.client;

import faygolover.flrender.FlRenderMod;

import java.lang.reflect.Method;

/** Oculus / Iris через их API, без жёсткой зависимости: включён ли шейдерпак и идёт ли проход теней. */
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
}
