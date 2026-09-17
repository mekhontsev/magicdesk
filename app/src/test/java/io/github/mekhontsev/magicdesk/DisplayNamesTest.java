package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayNamesTest {
    @Test public void productLabelsFallBackToSystemNamesWithoutRequiringMetadata() throws Exception {
        RuntimeSourceFixture.verify("""
                record DeviceProductInfo(String getName) {}
                record Display(String getName, DeviceProductInfo getDeviceProductInfo) {}
                """ + RuntimeSourceFixture.methods("DisplayNames", "name") + """
                public static void verify() {
                    check(name(new Display("HDMI Screen", new DeviceProductInfo("VITURE Beast")))
                            .equals("VITURE Beast"), "product name ignored");
                    check(name(new Display("HDMI Screen", new DeviceProductInfo("  Monitor 27  ")))
                            .equals("Monitor 27"), "product padding retained");
                    for (String system : new String[] {"Built-in screen", "Living room TV", "MagicDesk"}) {
                        check(name(new Display(system, null)).equals(system), "missing product changed name");
                        for (String product : new String[] {null, "", "  ", "\\n\\t"}) {
                            check(name(new Display(system, new DeviceProductInfo(product))).equals(system),
                                    "empty product did not fall back");
                        }
                    }
                    Display display = new Display("HDMI Screen", new DeviceProductInfo("Monitor"));
                    name(display);
                    check(display.getName().equals("HDMI Screen"), "system identity changed");
                }
                """);
    }
}
