package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TaskCaptionStructureCommandTest {
    private static final TaskLocalInsetsSourceParser.Frame WINDOW =
            new TaskLocalInsetsSourceParser.Frame(160, 120, 960, 720);

    @Test
    public void acceptsDisplayAndTaskLocalCoordinates() {
        assertEquals("display", TaskCaptionStructureCommand.validate(
                source(160, 120, 960, 160), WINDOW));
        assertEquals("task-local", TaskCaptionStructureCommand.validate(
                source(0, 0, 800, 40), WINDOW));
    }

    @Test
    public void rejectsMissingEmptyAndMisalignedCaption() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskCaptionStructureCommand.validate(null, WINDOW));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCaptionStructureCommand.validate(
                        source(160, 120, 160, 160), WINDOW));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCaptionStructureCommand.validate(
                        source(170, 120, 960, 160), WINDOW));
    }

    private static TaskLocalInsetsSourceParser.CaptionSource source(
            final int left, final int top, final int right, final int bottom) {
        return new TaskLocalInsetsSourceParser.CaptionSource(
                0x12340002,
                new TaskLocalInsetsSourceParser.Frame(
                        left, top, right, bottom));
    }
}
