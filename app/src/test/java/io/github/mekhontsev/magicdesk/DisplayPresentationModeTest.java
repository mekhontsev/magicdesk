package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class DisplayPresentationModeTest {
    @Test public void onlyOwnedVirtualSourcesTransferTheirRenderTarget() {
        for (String type : new String[]{"phone", "internal", "wired", "wireless", "overlay", "virtual"}) {
            for (boolean owned : new boolean[]{false, true}) {
                DesktopDisplayInfo source = new DesktopDisplayInfo(7, "id", "display", type,
                        100, 200, 160, false, owned);
                assertEquals(owned && type.equals("virtual") ? DisplayPresentationMode.DIRECT
                        : DisplayPresentationMode.MIRROR, DisplayPresentationMode.forSource(source));
            }
        }
    }
}
