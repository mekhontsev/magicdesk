package io.github.mekhontsev.magicdesk;

import java.util.Map;

/** Symbolic aliases and immutable bundle-relative resources; never arbitrary filesystem paths. */
public record ShellResources(Map<Icon, Icon> icons, String bundle, Map<Icon, String> iconAssets,
        String font, String wallpaper, ShaderWallpaper shader) {
    public enum Icon { START, DESKTOP, WINDOWS, NOTIFICATIONS, KEYBOARD, CONTROLS, FILES, TERMINAL, SETTINGS, SEARCH, CAMERA, VIDEO }
    public ShellResources {
        icons = Map.copyOf(icons); iconAssets = Map.copyOf(iconAssets);
        if (bundle == null || (!bundle.isEmpty() && !bundle.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("Invalid theme bundle digest");
        path(font); path(wallpaper); for (String path : iconAssets.values()) { path(path); if (path.isEmpty()) throw new IllegalArgumentException("Empty icon asset"); }
    }
    private static void path(String path) {
        if (path == null || (!path.isEmpty() && (path.length() > 160 || !path.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+")))) {
            throw new IllegalArgumentException("Invalid bundle-relative resource path");
        }
    }
    public Icon resolve(Icon role) { return icons.getOrDefault(role, role); }
    public boolean hasAssets() { return hasBundleAssets() || shader != null; }
    public boolean hasBundleAssets() { return !iconAssets.isEmpty() || !font.isEmpty() || !wallpaper.isEmpty()
            || shader != null && !shader.textures().isEmpty(); }
    public ShellResources withBundle(String digest) { return new ShellResources(icons, digest, iconAssets, font, wallpaper, shader); }
    public static ShellResources defaults() { return new ShellResources(Map.of(), "", Map.of(), "", "", null); }
}
