package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HardwareKeyboardLayoutsTest {
    @Test public void snapshotPreservesChoiceIdentityLabelsAndSelection() throws Exception {
        final var snapshot = new HardwareKeyboardLayouts(2, List.of(
                new HardwareKeyboardLayouts.Choice("provider/layout:en", "English (US)", false),
                new HardwareKeyboardLayouts.Choice("provider/layout:ru", "Russian \"PC\"", true)));
        assertEquals(snapshot, HardwareKeyboardLayouts.fromJson(snapshot.toJson()));
    }

    @Test public void noKeyboardIsAnEmptySuccessfulSnapshot() throws Exception {
        final var snapshot = HardwareKeyboardLayouts.fromJson(new HardwareKeyboardLayouts(0, List.of()).toJson());
        assertEquals(0, snapshot.physicalDevices());
        assertTrue(snapshot.choices().isEmpty());
    }

    @Test public void choicesAreCapturedRatherThanRetainingAMutableList() {
        final List<HardwareKeyboardLayouts.Choice> choices = new ArrayList<>();
        final var snapshot = new HardwareKeyboardLayouts(1, choices);
        choices.add(new HardwareKeyboardLayouts.Choice("layout", "Layout", true));
        assertTrue(snapshot.choices().isEmpty());
    }
}
