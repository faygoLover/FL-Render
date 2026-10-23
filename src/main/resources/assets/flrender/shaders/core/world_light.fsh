#version 150

// FL Render: coloured light that effects cast on the world round them - for the picture only (the world's
// light levels don't change). Per pixel, from the depth: where the surface is (relative to the camera) and
// which way it faces. Up to 12 lights, 12 floats each:
//   [0..2] position relative to the camera (a rectangle: its bottom centre), [3] reach;
//   [4..6] colour, [7] strength;
//   [8..10] a rectangle's face normal scaled by its height (all 0 = a point light),
//   [11] the rectangle's width (negative = it lights both sides alike).
// Out: the lit pixel (the frame's colour, brightened in the light's colour - mostly by its own colour, a
// little on its own so dark surfaces at night still catch it); untouched pixels are discarded.

uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;
uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform float LightData[144];
uniform int LightCount;
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
    vec3 light = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        if (i >= LightCount) break;
        int k = i * 12;
        vec3 lp = vec3(LightData[k], LightData[k + 1], LightData[k + 2]);
        float reach = max(LightData[k + 3], 0.5);
        vec3 face = vec3(LightData[k + 8], LightData[k + 9], LightData[k + 10]);
        float h = length(face);
        float side = 1.0;
        if (h > 1.0e-4) {
            // A rectangle standing upright: the nearest point of it lights the pixel.
            vec3 fn = face / h;
            vec3 r = vec3(fn.z, 0.0, -fn.x);
            float w = abs(LightData[k + 11]);
            vec3 d = p - lp;
            vec2 q = vec2(clamp(dot(d, r), -w * 0.5, w * 0.5), clamp(d.y, 0.0, h));
            vec3 nearest = lp + r * q.x + vec3(0.0, q.y, 0.0);
            if (dot(d, fn) < 0.0 && LightData[k + 11] >= 0.0) side = 0.15;
            lp = nearest;
        }
        vec3 d = lp - p;
        float dist = length(d);
        if (dist >= reach) continue;
        float fall = 1.0 - dist / reach;
        fall *= fall;
        float facing = 0.3 + 0.7 * max(dot(n, d / max(dist, 1.0e-4)), 0.0);
        light += vec3(LightData[k + 4], LightData[k + 5], LightData[k + 6]) * LightData[k + 7] * fall * facing * side;
    }
    if (light.r + light.g + light.b < 0.002) {
        discard;
    }
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    fragColor = vec4(scene + light * (scene * 0.9 + 0.12), 1.0);
}
