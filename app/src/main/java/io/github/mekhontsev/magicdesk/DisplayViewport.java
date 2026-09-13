package io.github.mekhontsev.magicdesk;

/** One aspect-fit transform for both display presentation and pointer coordinates. */
final class DisplayViewport {
    final int sourceWidth;
    final int sourceHeight;
    final int left;
    final int top;
    final int width;
    final int height;

    private DisplayViewport(int sourceWidth, int sourceHeight, int left, int top,
            int width, int height) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    static DisplayViewport fit(int sourceWidth, int sourceHeight, int width, int height) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("positive source and viewport dimensions required");
        }
        final double scale = Math.min((double) width / sourceWidth, (double) height / sourceHeight);
        final int w = Math.max(1, Math.min(width, (int) Math.round(sourceWidth * scale)));
        final int h = Math.max(1, Math.min(height, (int) Math.round(sourceHeight * scale)));
        return new DisplayViewport(sourceWidth, sourceHeight, (width - w) / 2, (height - h) / 2, w, h);
    }

    boolean contains(float x, float y) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    float sourceScaleX() { return (float) sourceWidth / width; }
    float sourceScaleY() { return (float) sourceHeight / height; }
    float sourceX(float x) { return (x - left) * sourceScaleX(); }
    float sourceY(float y) { return (y - top) * sourceScaleY(); }
}
