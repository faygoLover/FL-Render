#version 150

// FL Render: coloured light on the finished frame - the picture only. Per pixel, from the depth: where the
// surface is and which way it faces; the light is read from two 3D textures round the camera (one texel per
// block, the block in front of the surface):
//   TintVol - rgb: colour of the block light sources (torches, lanterns, glow sticks...), a: sky light;
//             pure colour x its share of the block light there: where it is the main light, the lit surface takes
//             its colour like under an LED (other channels go dark), weaker where the sun shines.
//   AddVol  - rgb: light of moving sources (effects, items in hand) x0.5; it adds light.

uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;
uniform sampler3D TintVol;
uniform sampler3D AddVol;
uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform vec3 VolOrigin;
uniform float VolSize;
uniform float HasAdd;
uniform float Tint;
uniform float Boost;
uniform float Day;
uniform float HandDepth;

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
    vec3 q = (p - VolOrigin + n * 0.5) / VolSize;
    if (any(lessThan(q, vec3(0.0))) || any(greaterThan(q, vec3(1.0)))) {
        discard;
    }
    // Fade out near the edges of the volume.
    vec3 e = min(q, vec3(1.0) - q) * VolSize;
    float edge = clamp(min(e.x, min(e.y, e.z)) / 4.0, 0.0, 1.0);

    vec3 scene = texture(SceneSampler, texCoord).rgb;
    vec3 col = scene;
    bool changed = false;
    vec4 t = texture(TintVol, q);
    float top = max(t.r, max(t.g, t.b));
    float amount = clamp(top * Tint * edge * (1.0 - t.a * Day * 0.85), 0.0, 1.0);
    if (amount > 0.004) {
        vec3 hue = t.rgb / max(top, 0.001);
        col = mix(col, scene * hue * Boost, amount);
        changed = true;
    }
    if (HasAdd > 0.5) {
        vec3 a = texture(AddVol, q).rgb * 2.0 * edge;
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
