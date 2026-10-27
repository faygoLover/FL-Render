package faygolover.flrender.flashlight;

import com.mojang.blaze3d.platform.GlStateManager;
import faygolover.flrender.FlRenderMod;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Свет фонаря в шейдере чанков Embeddium ({@code sodium:blocks/block_layer_opaque}): исходник дописывается при загрузке
 * (миксин на {@code ShaderLoader.getShaderSource}), значения — при включении программы
 * ({@code ChunkShaderInterface.setupState}). Ванильная карта освещения у Embeddium умножается на цвет вершины
 * в вершинном шейдере; фрагментный шейдер добавляет текстура × цвет вершины × свет фонаря, сумма света — не выше 1
 * (как под факелом в упор). Перестраивать чанки не нужно. Не нашлось места для вставки — шейдер не меняется.
 */
public final class ChunkPatch {
    private ChunkPatch() {}

    private static final String VSH = "blocks/block_layer_opaque.vsh";
    private static final String FSH = "blocks/block_layer_opaque.fsh";
    private static final String MAIN = "void main() {";
    private static final String V_COLOR = "v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);";
    private static final String F_TEX = "vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);";
    private static final String F_OUT = "fragColor = _linearFog(";

    private static final String V_DECL = """
            // FL Render: flashlight light (see ChunkPatch).
            out vec3 v_FlPos;
            out vec4 v_FlBase;
            out vec3 v_FlLm;

            """;

    private static final String V_SET = """

                v_FlPos = position;
                v_FlBase = _vert_color;
                v_FlLm = _sample_lightmap(u_LightTex, _vert_tex_light_coord).rgb;""";

    private static final String F_DECL = """
            // FL Render: flashlight light while drawing blocks - texture x (world light + flashlight), see ChunkPatch.
            in vec3 v_FlPos;
            in vec4 v_FlBase;
            in vec3 v_FlLm;

            uniform int u_FlBeamCount;
            uniform vec4 u_FlBeamPos[4];   // xyz lamp (camera relative), w range
            uniform vec4 u_FlBeamDir[4];   // xyz direction, w intensity
            uniform vec4 u_FlBeamCone[4];  // cos core inner, core outer, spill inner, spill outer
            uniform vec4 u_FlBeamWash[4];  // cos wash inner, wash outer, spill weight, wash weight
            uniform vec3 u_FlBeamColor;
            uniform vec4 u_FlParams;       // x gain, y falloff, z fade start
            uniform sampler3D u_FlOcc;     // half-block occupancy round the camera, 8 bits per block
            uniform ivec3 u_FlCamBlock;
            uniform vec3 u_FlCamFrac;
            uniform ivec3 u_FlOccMin;
            uniform ivec3 u_FlOccShift;

            const int FL_OCC = 96;

            bool fl_solid(ivec3 h) {
                ivec3 local = u_FlCamBlock + (h >> 1) - u_FlOccMin;
                if (any(lessThan(local, ivec3(0))) || any(greaterThanEqual(local, ivec3(FL_OCC)))) {
                    return false;
                }
                ivec3 tc = local + u_FlOccShift;
                tc -= FL_OCC * ivec3(greaterThanEqual(tc, ivec3(FL_OCC)));
                int v = int(texelFetch(u_FlOcc, tc, 0).r * 255.0 + 0.5);
                int bit = (h.x & 1) | ((h.y & 1) << 1) | ((h.z & 1) << 2);
                return ((v >> bit) & 1) != 0;
            }

            // Walk the half-block grid from the surface to the lamp; its own starting half is not counted, and when that
            // half is occupied (a face of a partial block: door, slab...) the whole own block is not counted.
            float fl_visible(vec3 from, vec3 lamp) {
                vec3 a = (u_FlCamFrac + from) * 2.0;
                vec3 d = (u_FlCamFrac + lamp) * 2.0 - a;
                float len = length(d);
                if (len < 1.0) {
                    return 1.0;
                }
                vec3 dir = d / len;
                ivec3 cell = ivec3(floor(a));
                bool ownSolid = fl_solid(cell);
                ivec3 own = cell >> 1;
                ivec3 st = ivec3(sign(dir));
                vec3 inv = 1.0 / max(abs(dir), vec3(1.0e-5));
                vec3 fr = a - floor(a);
                vec3 tMax = mix(fr, vec3(1.0) - fr, step(vec3(0.0), dir)) * inv;
                for (int i = 0; i < 180; i++) {
                    float t;
                    if (tMax.x < tMax.y && tMax.x < tMax.z) {
                        cell.x += st.x;
                        t = tMax.x;
                        tMax.x += inv.x;
                    } else if (tMax.y < tMax.z) {
                        cell.y += st.y;
                        t = tMax.y;
                        tMax.y += inv.y;
                    } else {
                        cell.z += st.z;
                        t = tMax.z;
                        tMax.z += inv.z;
                    }
                    if (t > len - 1.0) {
                        return 1.0;
                    }
                    if (ownSolid && all(equal(cell >> 1, own))) {
                        continue;
                    }
                    if (fl_solid(cell)) {
                        return 0.0;
                    }
                }
                return 1.0;
            }

            vec3 fl_flash(vec3 tex, vec3 n) {
                if (u_FlBeamCount <= 0) {
                    return vec3(0.0);
                }
                float nl = length(n);
                if (nl < 1.0e-10) {
                    return vec3(0.0);
                }
                n /= nl;
                vec3 p = v_FlPos;
                if (dot(n, p) > 0.0) {
                    n = -n;
                }
                float light = 0.0;
                for (int i = 0; i < 4; i++) {
                    if (i >= u_FlBeamCount) {
                        break;
                    }
                    vec4 o = u_FlBeamPos[i];
                    vec4 dr = u_FlBeamDir[i];
                    vec4 cone = u_FlBeamCone[i];
                    vec4 wash = u_FlBeamWash[i];
                    vec3 toF = p - o.xyz;
                    float dist = length(toF);
                    if (dist < 1.0e-4 || dist >= o.w) {
                        continue;
                    }
                    vec3 L = toF / dist;
                    float c = dot(L, dr.xyz);
                    if (c <= wash.y) {
                        continue;
                    }
                    float ndl = dot(n, -L);
                    if (ndl <= 0.0) {
                        continue;
                    }
                    float shape = (smoothstep(cone.y, cone.x, c) + smoothstep(cone.w, cone.z, c) * wash.z
                                   + smoothstep(wash.y, wash.x, c) * wash.w) / (1.0 + wash.z + wash.w);
                    float att = 1.0 / (1.0 + u_FlParams.y * dist * dist)
                                * (1.0 - smoothstep(o.w * u_FlParams.z, o.w, dist)) * smoothstep(0.05, 0.5, dist);
                    float amount = shape * att * dr.w * u_FlParams.x * clamp((ndl + 0.1) / 1.1, 0.0, 1.0);
                    if (amount < 0.004) {
                        continue;
                    }
                    light += amount * fl_visible(p + n * 0.02, o.xyz);
                }
                if (light <= 0.0) {
                    return vec3(0.0);
                }
            #ifdef USE_VANILLA_COLOR_FORMAT
                vec3 base = v_FlBase.rgb;
            #else
                vec3 base = v_FlBase.rgb * v_FlBase.a;
            #endif
                vec3 add = min(light * u_FlBeamColor, max(vec3(1.0) - v_FlLm, vec3(0.0)));
                return tex * base * add;
            }

            """;

    private static final String F_TEX_ADD = """

                vec3 fl_tex = diffuseColor.rgb;
                vec3 fl_n = cross(dFdx(v_FlPos), dFdy(v_FlPos));""";

    private static final String F_OUT_ADD = "diffuseColor.rgb += fl_flash(fl_tex, fl_n);\n    ";

    private static Boolean vshOk;
    private static boolean patched;

    /** Дописать исходник шейдера чанков (остальные — как есть). */
    public static String patch(ResourceLocation name, String src) {
        if (!"sodium".equals(name.getNamespace())) return src;
        String path = name.getPath();
        try {
            if (VSH.equals(path)) {
                if (!vshPatchable(src)) return src;
                return insertBefore(src, MAIN, V_DECL).replace(V_COLOR, V_COLOR + V_SET);
            }
            if (FSH.equals(path)) {
                if (!src.contains(MAIN) || !src.contains(F_TEX) || !src.contains(F_OUT) || !vshPatchable(null)) {
                    FlRenderMod.LOGGER.warn("FL Render: шейдер чанков Embeddium не тот, что ожидался, — свет фонаря на блоках выключен");
                    return src;
                }
                String out = insertBefore(src, MAIN, F_DECL);
                out = out.replace(F_TEX, F_TEX + F_TEX_ADD);
                out = out.replace(F_OUT, F_OUT_ADD + F_OUT);
                patched = true;
                return out;
            }
        } catch (RuntimeException e) {
            FlRenderMod.LOGGER.error("FL Render: шейдер чанков не дописался", e);
        }
        return src;
    }

    /** Подходит ли вершинный шейдер (тот, что дал Embeddium; {@code null} — прочитать самому). */
    private static boolean vshPatchable(String src) {
        if (vshOk != null) return vshOk;
        if (src == null) {
            try (var in = ChunkPatch.class.getClassLoader().getResourceAsStream("assets/sodium/shaders/" + VSH)) {
                src = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                src = "";
            }
        }
        vshOk = src.contains(MAIN) && src.contains(V_COLOR);
        return vshOk;
    }

    private static String insertBefore(String src, String marker, String text) {
        int i = src.indexOf(marker);
        return i < 0 ? src : src.substring(0, i) + text + src.substring(i);
    }

    /** Шейдер чанков дописан. */
    public static boolean patched() {
        return patched;
    }

    private static final String[] NAMES = {"u_FlBeamCount", "u_FlBeamPos", "u_FlBeamDir", "u_FlBeamCone", "u_FlBeamWash",
            "u_FlBeamColor", "u_FlParams", "u_FlOcc", "u_FlCamBlock", "u_FlCamFrac", "u_FlOccMin", "u_FlOccShift"};

    /** Места значений в программе; null — программа без света фонаря. */
    public static int[] locate(int program) {
        int[] loc = new int[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) loc[i] = GL20.glGetUniformLocation(program, NAMES[i]);
        return loc[0] < 0 || loc[7] < 0 ? null : loc;
    }

    /** Программа включена: значения этого кадра. */
    public static void upload(int[] loc) {
        if (loc == null) return;
        // Сэмплер — всегда на своём блоке: на блоке 0 лежит 2D-текстура, разные типы на одном блоке — ошибка рисования.
        GL20.glUniform1i(loc[7], Occupancy.UNIT);
        int prevUnit = GlStateManager._getActiveTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + Occupancy.UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, Occupancy.texture());
        GL13.glActiveTexture(prevUnit);
        if (!Flashlight.active()) {
            GL20.glUniform1i(loc[0], 0);
            return;
        }
        List<Beam> beams = Flashlight.beams();
        int n = Math.min(beams.size(), Flashlight.MAX_BEAMS);
        double cx = Flashlight.camX(), cy = Flashlight.camY(), cz = Flashlight.camZ();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pos = stack.mallocFloat(16), dir = stack.mallocFloat(16), cone = stack.mallocFloat(16), wash = stack.mallocFloat(16);
            for (int i = 0; i < Flashlight.MAX_BEAMS; i++) {
                Beam b = i < n ? beams.get(i) : null;
                if (b == null) {
                    for (int k = 0; k < 4; k++) {
                        pos.put(0.0f);
                        dir.put(0.0f);
                        cone.put(0.0f);
                        wash.put(0.0f);
                    }
                    continue;
                }
                pos.put((float) (b.x() - cx)).put((float) (b.y() - cy)).put((float) (b.z() - cz)).put(b.range());
                dir.put((float) b.dx()).put((float) b.dy()).put((float) b.dz()).put(b.intensity());
                cone.put(b.coreIn()).put(b.coreOut()).put(b.spillIn()).put(b.spillOut());
                wash.put(b.washIn()).put(b.washOut()).put(b.spillWeight()).put(b.washWeight());
            }
            pos.flip();
            dir.flip();
            cone.flip();
            wash.flip();
            GL20.glUniform1i(loc[0], n);
            GL20.glUniform4fv(loc[1], pos);
            GL20.glUniform4fv(loc[2], dir);
            GL20.glUniform4fv(loc[3], cone);
            GL20.glUniform4fv(loc[4], wash);
        }
        GL20.glUniform3f(loc[5], Flashlight.R, Flashlight.G, Flashlight.B);
        GL20.glUniform4f(loc[6], Beam.GAIN, Beam.FALLOFF, Beam.FADE_START, 0.0f);
        int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
        GL20.glUniform3i(loc[8], bx, by, bz);
        GL20.glUniform3f(loc[9], (float) (cx - bx), (float) (cy - by), (float) (cz - bz));
        GL20.glUniform3i(loc[10], Occupancy.minX(), Occupancy.minY(), Occupancy.minZ());
        GL20.glUniform3i(loc[11], Math.floorMod(Occupancy.minX(), Occupancy.SIZE), Math.floorMod(Occupancy.minY(), Occupancy.SIZE),
                Math.floorMod(Occupancy.minZ(), Occupancy.SIZE));
    }
}
