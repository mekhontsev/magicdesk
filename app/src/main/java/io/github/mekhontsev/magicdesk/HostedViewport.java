package io.github.mekhontsev.magicdesk;

/** Aspect-fit content geometry in host pixels; content coordinates are normalized, not clipped. */
record HostedViewport(float left, float top, float width, float height) {
    static final HostedViewport EMPTY = new HostedViewport(0, 0, 0, 0);

    static HostedViewport fit(int hostWidth, int hostHeight, int contentWidth, int contentHeight) {
        if (hostWidth < 1 || hostHeight < 1 || contentWidth < 1 || contentHeight < 1) return EMPTY;
        float scale = Math.min(hostWidth / (float) contentWidth, hostHeight / (float) contentHeight);
        float width = contentWidth * scale, height = contentHeight * scale;
        return new HostedViewport((hostWidth - width) / 2, (hostHeight - height) / 2, width, height);
    }

    boolean available() { return width > 0 && height > 0; }
    float contentX(float hostX) { return available() ? (hostX - left) / width : 0; }
    float contentY(float hostY) { return available() ? (hostY - top) / height : 0; }
    float hostX(float contentX) { return left + contentX * width; }
    float hostY(float contentY) { return top + contentY * height; }
}
