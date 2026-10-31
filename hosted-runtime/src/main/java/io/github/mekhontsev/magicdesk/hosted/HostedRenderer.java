package io.github.mekhontsev.magicdesk.hosted;

/** Immutable renderer policy captured by a graphical session, including its Android outputs. */
public enum HostedRenderer {
    AUTOMATIC("automatic"), SOFTWARE("software");

    public static final String ENVIRONMENT = "MAGICDESK_GRAPHICS_RENDERER";
    public final String id;

    HostedRenderer(String id) { this.id = id; }

    public boolean softwareOnly() { return this == SOFTWARE; }

    public static HostedRenderer fromId(String id) {
        for (var renderer : values()) if (renderer.id.equals(id)) return renderer;
        throw new IllegalArgumentException("Unknown graphics renderer: " + id);
    }
}
