package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class TaskStackParserTest {
    @Test
    public void parsesTaskMetadataAndBounds() {
        final List<TaskStackParser.Entry> tasks =
                TaskStackParser.parse(
                        "RootTask id=17 bounds=[0,0][1920,1080] displayId=9\n"
                                + "  configuration={mWindowingMode=freeform "
                                + "mActivityType=standard}\n"
                                + "  taskId=42: com.example/.Main "
                                + "topActivity=ComponentInfo{com.example/.Main} "
                                + "visible=true bounds=[100,120][900,720]\n");

        assertEquals(1, tasks.size());
        final TaskStackParser.Entry task = tasks.get(0);
        assertEquals(17, task.rootTaskId);
        assertEquals(42, task.taskId);
        assertEquals(9, task.displayId);
        assertEquals("com.example", task.packageName);
        assertEquals("freeform", task.windowingMode);
        assertEquals(100, task.bounds.left);
        assertEquals(720, task.bounds.bottom);
        assertTrue(task.visible);
        assertFalse(task.isHome());
    }

    @Test
    public void keepsHomeTypeAndSkipsUnsafePackage() {
        final List<TaskStackParser.Entry> tasks =
                TaskStackParser.parse(
                        "RootTask id=1 displayId=0\n"
                                + " configuration={mWindowingMode=fullscreen "
                                + "mActivityType=home}\n"
                                + " taskId=2: io.github.launcher/.Home "
                                + "topActivity=ComponentInfo{io.github.launcher/.Home} "
                                + "visible=true\n"
                                + " taskId=3: bad;name/.Main "
                                + "topActivity=ComponentInfo{bad;name/.Main} "
                                + "visible=true\n");

        assertEquals(1, tasks.size());
        assertTrue(tasks.get(0).isHome());
    }
}
