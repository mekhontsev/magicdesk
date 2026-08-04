package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TaskLocalInsetsSourceParserTest {
    @Test
    public void findsCaptionSourceOwnedByRequestedTask() {
        assertEquals(
                0x986a0002,
                TaskLocalInsetsSourceParser.findCaptionSourceId(
                        "* Task{aaa #41 type=standard mode=freeform}\n"
                                + "  2 LocalInsetsSources\n"
                                + "    InsetsSource id=11110002 type=captionBar "
                                + "frame=[0,0][10,10]\n"
                                + "* Task{bbb #42 type=standard mode=freeform}\n"
                                + "  FreeformWrTaskInfo{ taskId=42 }\n"
                                + "  2 LocalInsetsSources\n"
                                + "    InsetsSource id=986a0002 type=captionBar "
                                + "frame=[240,80][1680,120]\n"
                                + "    InsetsSource id=986a0005 "
                                + "type=mandatorySystemGestures\n",
                        42));
    }

    @Test
    public void continuesAcrossRepeatedTaskSections() {
        assertEquals(
                0x03070002,
                TaskLocalInsetsSourceParser.findCaptionSourceId(
                        "* Task{aaa #9 type=standard mode=freeform}\n"
                                + "  bounds=[0,0][100,100]\n"
                                + "* Task{bbb #10 type=standard mode=freeform}\n"
                                + "  InsetsSource id=11110002 type=captionBar\n"
                                + "* Task{aaa #9 type=standard mode=freeform}\n"
                                + "  InsetsSource id=03070002 type=captionBar\n",
                        9));
    }

    @Test
    public void ignoresSourcesOutsideRequestedTask() {
        assertEquals(
                TaskLocalInsetsSourceParser.NO_SOURCE_ID,
                TaskLocalInsetsSourceParser.findCaptionSourceId(
                        "* Task{aaa #7 type=standard mode=fullscreen}\n"
                                + "  bounds=[0,0][1920,1080]\n"
                                + "* Task{bbb #8 type=standard mode=freeform}\n"
                                + "  InsetsSource id=12340002 type=captionBar\n",
                        7));
    }

    @Test
    public void rejectsMalformedInput() {
        assertEquals(
                TaskLocalInsetsSourceParser.NO_SOURCE_ID,
                TaskLocalInsetsSourceParser.findCaptionSourceId(
                        "* Task{aaa #7 type=standard mode=freeform}\n"
                                + "  InsetsSource id=not-hex type=captionBar\n",
                        7));
        assertEquals(
                TaskLocalInsetsSourceParser.NO_SOURCE_ID,
                TaskLocalInsetsSourceParser.findCaptionSourceId(null, 7));
    }
}
