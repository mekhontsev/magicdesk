#version 450
layout(set = 0, binding = 0) uniform sampler2D image;
layout(push_constant) uniform Draw {
    vec4 destination;
    vec4 source;
    vec4 color;
    vec4 options;
} draw;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
void main() {
    color = draw.options.x != 0.0 ? draw.color : texture(image, uv);
    if (draw.options.y != 0.0) color = color.bgra;
    if (draw.options.w != 0.0) color.a = 1.0;
    if (draw.options.x == 0.0) color *= draw.color.a;
}
