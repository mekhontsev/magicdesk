package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

public final class TaskCaptionSurfaceCommandTest {
    @Test
    public void findsVisibleAndHiddenCaptionLayers() {
        final String dump = ""
                + "  Layer [42] Caption of Task=101#42\n"
                + "    invisible reason=nothing to draw\n"
                + "  Layer [43] Caption of Task=102#43\n"
                + "    visible reason=buffer=1 frame=2\n"
                + "  Layer [44 mirrored from 9,] Caption of Task=101#44\n"
                + "    visible reason=buffer=2 frame=3\n"
                + "  Layer [45] Caption of Task=104#45\n"
                + "    invisible reason=nothing to draw\n";

        final Map<Integer, TaskCaptionSurfaceCommand.State> states =
                TaskCaptionSurfaceCommand.inspect(dump, 101, 102, 103, 104);

        assertEquals(TaskCaptionSurfaceCommand.State.VISIBLE, states.get(101));
        assertEquals(TaskCaptionSurfaceCommand.State.VISIBLE, states.get(102));
        assertEquals(TaskCaptionSurfaceCommand.State.ABSENT, states.get(103));
        assertEquals(TaskCaptionSurfaceCommand.State.HIDDEN, states.get(104));
    }

    @Test
    public void parsesCommandResult() throws Exception {
        final Map<Integer, TaskCaptionSurfaceCommand.State> states =
                TaskCaptionSurfaceCommand.parseResult(
                        "caption-surface=hidden task=7\n"
                                + "caption-surface=absent task=8\n",
                        7, 8);

        assertEquals(TaskCaptionSurfaceCommand.State.HIDDEN, states.get(7));
        assertEquals(TaskCaptionSurfaceCommand.State.ABSENT, states.get(8));
    }
}
