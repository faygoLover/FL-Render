#version 150

// FL Render: coloured light on the finished frame - the picture only. Per pixel, from the depth: where the
// surface is and which way it faces; the light is read for the block in front of the surface:
//   TintVol  - atlas of bricks (18^3: a 16^3 section and one block of its neighbours round it, for smooth filtering),
//              only for sections near coloured sources; IndexVol - per section in range: brick number + 1, or 0.
//              rgb: colour of the block light sources (torches, lanterns, glow sticks...), a: sky light;
//              pure colour x its share of the block light there: where it is the main light, the lit surface takes
//              its colour like under an LED (other channels go dark), weaker where the sun shines.
//   AddVol   - small cube round the camera, rgb: light of moving sources (effects, items in hand) x0.5; it adds light.
// NightVision (0..1): the scene is already bright - the added light is weakened by up to 80%, the tint is not boosted.

uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;
uniform sampler3D TintVol;
uniform sampler3D AddVol;
uniform usampler3D IndexVol;
uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform vec3 IndexOrigin;
uniform vec3 IndexSize;
uniform vec3 AtlasBricks;
uniform vec2 Range;
uniform float HasTint;
uniform vec3 AddOrigin;
uniform float AddSize;
uniform float HasAdd;
uniform float Tint;
uniform float Boost;
uniform float Day;
uniform float HandDepth;
uniform float NightVision;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth >= 0.99999 || depth < HandDepth) {
        discard;
    }
    vec4 view = InvProjMat * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 p = (InvViewMat * vec4(view.xyz / view.w, 1.0)).xyz;
    vec3 n = cross(dFdx(p), dFdy(p));
    float nl = length(n);
    n = nl > 1.0e-6 ? n / nl : vec3(0.0, 1.0, 0.0);
    if (dot(n, -p) < 0.0) n = -n;
    vec3 front = p + n * 0.5;

    vec3 scene = texture(SceneSampler, texCoord).rgb;
    vec3 col = scene;
    bool changed = false;
    if (HasTint > 0.5) {
        vec3 rel = front - IndexOrigin;
        vec3 sec = floor(rel / 16.0);
        if (all(greaterThanEqual(sec, vec3(0.0))) && all(lessThan(sec, IndexSize))) {
            int id = int(texelFetch(IndexVol, ivec3(sec), 0).r) - 1;
            if (id >= 0) {
                int ax = int(AtlasBricks.x), ay = int(AtlasBricks.y);
                vec3 b = vec3(float(id % ax), float((id / ax) % ay), float(id / (ax * ay)));
                vec3 uvw = (b * 18.0 + 1.0 + (rel - sec * 16.0)) / (AtlasBricks * 18.0);
                vec4 t = texture(TintVol, uvw);
                // Soft end at the range (render distance or the player's limit): over the last chunk sideways,
                // 8 blocks up and down.
                float edge = clamp((Range.x - length(p.xz)) / 16.0, 0.0, 1.0) * clamp((Range.y - abs(p.y)) / 8.0, 0.0, 1.0);
                edge = edge * edge * (3.0 - 2.0 * edge);
                float top = max(t.r, max(t.g, t.b));
                float amount = clamp(top * Tint * edge * (1.0 - t.a * Day * 0.85), 0.0, 1.0);
                if (amount > 0.004) {
                    vec3 hue = t.rgb / max(top, 0.001);
                    col = mix(col, scene * hue * mix(Boost, 1.0, NightVision), amount);
                    changed = true;
                }
            }
        }
    }
    if (HasAdd > 0.5) {
        // The small cube of moving lights round the camera.
        vec3 qa = (front - AddOrigin) / AddSize;
        vec3 ea = clamp(min(qa, vec3(1.0) - qa) * AddSize / 4.0, 0.0, 1.0);
        float addEdge = ea.x * ea.y * ea.z;
        vec3 a = texture(AddVol, clamp(qa, 0.0, 1.0)).rgb * 2.0 * addEdge * (1.0 - 0.8 * NightVision);
        if (a.r + a.g + a.b > 0.003) {
            col += a * (scene * 1.2 + 0.2);
            changed = true;
        }
    }
    if (!changed) {
        discard;
    }
    fragColor = vec4(col, 1.0);
}
