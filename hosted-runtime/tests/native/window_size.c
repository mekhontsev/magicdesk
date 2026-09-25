#include "hosted_window_size.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>

static void check(int w, int h, int minw, int minh, int maxw, int maxh, int ew, int eh) {
    hosted_window_size(minw, minh, maxw, maxh, &w, &h);
    assert(w == ew && h == eh);
}

int main(void) {
    check(1216, 2688, 1, 1, 16384, 16384, 1216, 2688);
    check(1216, 2688, 1854, 1860, 16384, 16384, 1854, 4098);
    check(2688, 1216, 1854, 1860, 16384, 16384, 4112, 1860);
    check(1216, 1300, 1854, 1860, 16384, 16384, 1854, 1982);
    check(1216, 2688, 500, 300, 500, 300, 500, 300);
    check(1200, 2400, 1800, 600, 2000, 2400, 1800, 2400);
    check(100, 200, 1, 1, 50, 500, 50, 100);
    check(1, INT_MAX, 1, 1, 16384, 16384, 1, 16384);
    check(0, 0, 1, 1, 4096, 4096, 1, 1);
    // Different protocol units give the same effective viewport/DPI fit.
    int xw = 800, xh = 1200, ww = 400, wh = 600;
    hosted_window_size(1200, 200, 8192, 8192, &xw, &xh);
    hosted_window_size(600, 100, 4096, 4096, &ww, &wh);
    assert(xw == ww * 2 && xh == wh * 2);
    double scale = hosted_buffer_scale(1200, 300, 4, 4096);
    assert(round(1200 * scale) == 4096 && round(300 * scale) == 1024);
    assert(hosted_buffer_scale(800, 600, 1.25, 4096) == 1.25);
    for (int w = 1; w <= 37; w += 3) for (int h = 1; h <= 47; h += 5)
        for (int minw = 1; minw <= 23; minw += 4) for (int minh = 1; minh <= 29; minh += 6) {
            int rw = w, rh = h;
            hosted_window_size(minw, minh, 31, 41, &rw, &rh);
            assert(rw >= minw && rh >= minh && rw <= 31 && rh <= 41);
            double lower = fmax((double)minw / w, (double)minh / h);
            double upper = fmin(31.0 / w, 41.0 / h);
            if (lower <= upper) assert(fabs((double)rw * h - (double)rh * w) <= (w + h) * .5);
        }
    puts("hosted window size, constraints, aspect and density bounds passed");
}
