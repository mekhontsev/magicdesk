#version 450
layout(push_constant) uniform Draw {
    vec4 destination;
    vec4 source;
    vec4 color;
    vec4 options;
} draw;
layout(location = 0) out vec2 uv;
void main() {
    vec2 p = vec2(gl_VertexIndex & 1, gl_VertexIndex >> 1);
    gl_Position = vec4(draw.destination.xy + p * draw.destination.zw, 0.0, 1.0);
    vec2 q = p;
    int transform = int(draw.options.z);
    if ((transform & 4) != 0) q.x = 1.0 - q.x;
    for (int i = 0; i < (transform & 3); ++i) q = vec2(q.y, 1.0 - q.x);
    uv = draw.source.xy + q * draw.source.zw;
}
