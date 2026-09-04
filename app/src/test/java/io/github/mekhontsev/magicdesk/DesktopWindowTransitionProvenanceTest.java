package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DesktopWindowTransitionProvenanceTest {
    @Before
    public void setUp() {
        DesktopWindowTransitionProvenance.resetForTests();
    }

    @After
    public void tearDown() {
        DesktopWindowTransitionProvenance.resetForTests();
    }

    @Test
    public void magicDeskCommandCarriesSemanticCallerPath() {
        DesktopWindowTransitionProvenance.noteMagicDeskCommand(
                DesktopWindowTransitionRequest.restoreFreeform(
                        3,
                        42,
                        new Rect(1, 2, 300, 400),
                        DesktopTaskDensity.INHERIT,
                        "native-window-restore-shortcut"));

        final DesktopWindowTransitionProvenance.Observation observation =
                DesktopWindowTransitionProvenance.classify(
                        42, "fullscreen", "freeform");

        assertEquals(
                DesktopWindowTransitionProvenance.Source.MAGICDESK_COMMAND,
                observation.source);
        assertEquals(
                "restore-freeform/native-window-restore-shortcut",
                observation.detail);
    }

    @Test
    public void handoffCorrectionOverridesEarlierApplicationRequest() {
        DesktopWindowTransitionProvenance.noteApplicationRequest(7, true);
        DesktopWindowTransitionProvenance.noteActivityHandoff(
                7, "fullscreen", "example.Activity");

        assertEquals(
                DesktopWindowTransitionProvenance.Source.ACTIVITY_HANDOFF_GUARD,
                DesktopWindowTransitionProvenance.classify(
                        7, "freeform", "fullscreen").source);
    }

    @Test
    public void uncorrelatedKnownModeChangeIsFrameworkExternal() {
        assertEquals(
                DesktopWindowTransitionProvenance.Source.FRAMEWORK_EXTERNAL,
                DesktopWindowTransitionProvenance.classify(
                        9, "freeform", "fullscreen").source);
    }
}
