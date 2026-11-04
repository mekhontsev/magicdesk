package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AutomationInputSequenceTest {
    private static final class Sink implements AutomationInputSequence.Sink {
        final List<String> events = new ArrayList<>();
        boolean failDown, failUp;
        int meta;
        public void pointer(int action, float x, float y) { }
        public void key(int action, int key, int meta) {
            events.add(action + ":" + key); this.meta = meta;
            if (key == KeyEvent.KEYCODE_A && (failDown && action == 0 || failUp && action == 1))
                throw new IllegalStateException("dispatch failure");
        }
    }
    @Test public void reverseReleaseAndModifierCleanup() {
        var sink = new Sink();
        AutomationInputSequence.keys(sink, new int[]{KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A});
        assertEquals(List.of("0:113", "0:29", "1:29", "1:113"), sink.events);
        assertEquals(0, sink.meta);
    }
    @Test public void failedPressStillReleasesEveryKey() {
        var sink = new Sink(); sink.failDown = true;
        assertThrows(IllegalStateException.class, () -> AutomationInputSequence.keys(sink, new int[]{113, 29}));
        assertEquals(List.of("0:113", "0:29", "1:29", "1:113"), sink.events);
    }
    @Test public void failedReleaseDoesNotLeaveModifierPressed() {
        var sink = new Sink(); sink.failUp = true;
        assertThrows(IllegalStateException.class, () -> AutomationInputSequence.keys(sink, new int[]{113, 29}));
        assertEquals(List.of("0:113", "0:29", "1:29", "1:113"), sink.events);
    }
}
