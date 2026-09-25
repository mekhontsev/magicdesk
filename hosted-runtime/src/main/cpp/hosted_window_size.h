#pragma once
#include <math.h>

// All dimensions use the same client coordinate space. Adapters validate limits
// and retain the original host offer; a previously fitted size is not a new offer.
static inline int hosted_size_axis(double value, int minimum, int maximum) {
    if (value <= minimum) return minimum;
    if (value >= maximum) return maximum;
    return (int)round(value);
}

static inline void hosted_window_size(int min_width, int min_height,
        int max_width, int max_height, int *width, int *height) {
    double w = *width > 0 ? *width : 1, h = *height > 0 ? *height : 1;
    double lower = fmax(min_width / w, min_height / h);
    double upper = fmin(max_width / w, max_height / h);
    double scale = fmax(lower, fmin(1, upper));
    // Incompatible aspect/limits (including fixed-size dialogs) retain the
    // client limits. The presenter uniformly fits the remaining letterboxing.
    *width = hosted_size_axis(w * scale, min_width, max_width);
    *height = hosted_size_axis(h * scale, min_height, max_height);
}

// Bound allocation without independently clipping either axis of the client.
static inline double hosted_buffer_scale(int width, int height, double scale, int limit) {
    return fmin(scale, fmin((double)limit / width, (double)limit / height));
}
