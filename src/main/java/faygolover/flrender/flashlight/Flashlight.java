package faygolover.flrender.flashlight;

import faygolover.flrender.FlRenderMod;
import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Technique;
import faygolover.flrender.client.Oculus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Настоящий свет фонаря (без шейдерпака): конус от лампы освещает блоки при рисовании — текстура × (свет мира + свет
 * фонаря), как от факела, — а сущности и блоки-сущности в луче получают уровень света, как будто рядом факел.
 * Сквозь стены не проходит: путь от поверхности к лампе проверяется по половинкам блоков ({@link Occupancy}).
 * Экранный круг фонаря SA остаётся поверх (RP Fixes с этим светом снижает его усиление — {@link FlRender#flashlightLight}).
 * <p>
 * Лучи — у фонарей Survivor's Arsenal ({@link SaBeams}): до 4, свои и чужие. Блоки — патч шейдера чанков Embeddium
 * ({@link ChunkPatch}). С шейдерпаком выключено: там только экранный фонарь SA, как раньше.
 * Техника {@code flrender.flashlight} — со «Среднего».
 */
public final class Flashlight {
    private Flashlight() {}

    public static final int MAX_BEAMS = 4;
    public static final Technique TECHNIQUE = FlRender.technique(FlRender.MODID, "flashlight", Quality.MEDIUM);
    /** Цвет лампы (как у SA). */
    static final float R = 1.0f, G = 0.96f, B = 0.88f;
    /** Слабее этого свет сущность не меняет. */
    private static final float ENTITY_MIN = 0.02f;
    /**
     * Тот, у кого фонарь в руке: отсвет от лампы — рука и предмет от первого лица, сам игрок от третьего (иначе в полной
     * тьме они чёрные, хотя рядом горит фонарь).
     */
    private static final int HOLDER_LEVEL = 7;
    /** Самое большее для сущности — как факел в соседнем блоке. */
    private static final int ENTITY_MAX_LEVEL = 14;

    private static final boolean SA = ModList.get().isLoaded("sa_combat");
    private static boolean saBroken;

    private static final List<Beam> BEAMS = new ArrayList<>();
    /** Свет этого кадра включён (техника, без пака, есть лучи). */
    private static boolean active;
    private static double camX, camY, camZ;

    public static void init() {
        // Техника регистрируется при загрузке класса — до того, как строится вкладка настроек.
    }

    /** Включён ли настоящий свет фонаря сейчас (техника включена и шейдерпака нет). */
    public static boolean enabled() {
        return SA && !saBroken && TECHNIQUE.on() && !Oculus.shaderPack();
    }

    /** До рисования мира (после неба): лучи этого кадра и объём стен. */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        BEAMS.clear();
        active = false;
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        try {
            SaBeams.collect(mc, event.getPartialTick(), cam, BEAMS);
        } catch (LinkageError | RuntimeException e) {
            saBroken = true;
            FlRenderMod.LOGGER.error("FL Render: лучи фонаря Survivor's Arsenal не читаются (другая версия?), свет фонаря выключен", e);
            BEAMS.clear();
            return;
        }
        // Объём стен нужен только при лучах: без фонаря рядом он ничего не стоит, ближние секции соберутся за несколько кадров.
        if (BEAMS.isEmpty()) return;
        try {
            Occupancy.frame(cam);
        } catch (RuntimeException e) {
            FlRenderMod.LOGGER.error("FL Render: объём для фонаря не обновился", e);
            BEAMS.clear();
            return;
        }
        active = !BEAMS.isEmpty() && Occupancy.ready();
    }

    static boolean active() {
        return active;
    }

    static List<Beam> beams() {
        return BEAMS;
    }

    static double camX() {
        return camX;
    }

    static double camY() {
        return camY;
    }

    static double camZ() {
        return camZ;
    }

    /** Уровень света блока для сущности от фонарей (0–14) или -1, если фонари её не освещают. */
    public static int entityLight(Entity e, float partial) {
        if (!active) return -1;
        Vec3 p = e.getPosition(partial);
        int level = lightAt(p.x, p.y + e.getBbHeight() * 0.5, p.z, false);
        for (Beam b : BEAMS) {
            if (b.owner() == e.getId()) return Math.max(level, HOLDER_LEVEL);
        }
        return level;
    }

    /** То же для блока-сущности (сундук, табличка): середина блока, свой блок свет не держит. */
    public static int blockEntityLight(BlockPos pos) {
        if (!active) return -1;
        return lightAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, true);
    }

    private static int lightAt(double x, double y, double z, boolean ownBlock) {
        float sum = 0.0f;
        for (Beam b : BEAMS) {
            float a = b.amount(x, y, z);
            if (a < ENTITY_MIN) continue;
            if (!Occupancy.clear(x, y, z, b.x(), b.y(), b.z(), ownBlock)) continue;
            sum += a;
        }
        if (sum < ENTITY_MIN) return -1;
        // Свет фонаря добавляется к карте освещения почти линейно, уровень блока в ней — нет: корень ближе к глазу.
        return Math.min(ENTITY_MAX_LEVEL, Math.round(15.0f * (float) Math.sqrt(Math.min(sum, 1.0f))));
    }
}
