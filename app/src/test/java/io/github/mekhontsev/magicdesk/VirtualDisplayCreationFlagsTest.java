package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class VirtualDisplayCreationFlagsTest {
    @Test public void creationKeepsIndependentInputAndPowerWithoutSystemDecorations() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DisplayManager {
                    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
                    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
                    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
                    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;
                    public static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
                    public static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
                    public static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
                    public static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
                }
                public static void verify() throws Exception {
                    int flags = creationFlags();
                    int required = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
                    check((flags & required) == required, "lost display, input or power-group capability");
                    check((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) == 0,
                            "virtual display forces SystemUI navigation over the desktop taskbar");
                    check((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0,
                            "independent display mirrors the phone");
                }
                """ + RuntimeSourceFixture.methods("FrameworkVirtualDisplayApi", "creationFlags", "virtualFlag"));
    }
}
