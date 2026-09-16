package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class DisplaySwitchHistoryTest {
    @Test public void listsNewDisplaysAndRetainsPreviousNativeOutput() {
        final var history = new DisplaySwitchHistory();
        final var all = List.of("phone", "hdmi", "a", "b");
        assertEquals(List.of("hdmi", "phone", "a", "b"), history.order("hdmi", "hdmi", all));
        history.committed("hdmi", "hdmi", "a");
        assertEquals(List.of("a", "hdmi", "phone", "b"), history.order("hdmi", "a", all));
        history.committed("hdmi", "a", "b");
        assertEquals(List.of("b", "a", "hdmi", "phone"), history.order("hdmi", "b", all));
        history.committed("hdmi", "b", "a");
        assertEquals(List.of("a", "b", "hdmi", "phone"), history.order("hdmi", "a", all));
    }

    @Test public void disappearedIdentitiesArePrunedAndOutputsHaveSeparateMru() {
        final var history = new DisplaySwitchHistory();
        history.committed("hdmi", "phone", "old:7");
        history.committed("phone", "phone", "b");
        final var all = List.of("phone", "hdmi", "new:7", "b");
        history.retainDisplays(all);
        assertEquals(List.of("hdmi", "phone", "new:7", "b"), history.order("hdmi", "hdmi", all));
        assertEquals(List.of("phone", "b", "hdmi", "new:7"), history.order("phone", "phone", all));
    }

    @Test public void temporarilyIneligibleSourceDoesNotEraseOtherOutputsHistory() {
        final var history = new DisplaySwitchHistory();
        history.committed("hdmi", "phone", "a");
        history.committed("phone", "phone", "b");
        history.order("phone", "phone", List.of("phone", "b"));
        assertEquals(List.of("hdmi", "a", "phone", "b"),
                history.order("hdmi", "hdmi", List.of("phone", "hdmi", "a", "b")));
    }
}
