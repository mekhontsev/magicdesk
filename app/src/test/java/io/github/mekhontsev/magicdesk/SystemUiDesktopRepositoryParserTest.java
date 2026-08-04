package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

public final class SystemUiDesktopRepositoryParserTest {
    @Test
    public void findsCurrentUserPhoneDesktopTasks() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        Integer.valueOf(3516),
                        Integer.valueOf(3520),
                        Integer.valueOf(3521))),
                SystemUiDesktopRepositoryParser.parsePhoneTaskIds(
                        "DesktopUserRepositories:\n"
                                + "  currentUserId=0\n"
                                + "  DesktopRepository\n"
                                + "    userId=0\n"
                                + "    Display #-1:\n"
                                + "      activeTasks=[99]\n"
                                + "    Display #0:\n"
                                + "      activeTasks=[3516, 3520]\n"
                                + "      visibleTasks=[3520]\n"
                                + "      freeformTasksInZOrder=[3516, 3521]\n"
                                + "      minimizedTasks=[]\n"
                                + "    Display #17:\n"
                                + "      activeTasks=[77]\n"));
    }

    @Test
    public void ignoresAnotherUserAndUnrelatedTaskLists() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(Integer.valueOf(42))),
                SystemUiDesktopRepositoryParser.parsePhoneTaskIds(
                        "activeTasks=[7]\n"
                                + "DesktopUserRepositories:\n"
                                + "  currentUserId=10\n"
                                + "  DesktopRepository\n"
                                + "    userId=0\n"
                                + "    Display #0:\n"
                                + "      activeTasks=[8]\n"
                                + "  DesktopRepository\n"
                                + "    userId=10\n"
                                + "    Display #0:\n"
                                + "      activeTasks=[42, invalid, -1]\n"));
    }
}
