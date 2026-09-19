package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class SystemUiDesktopRepositoryParserTest {
    private static final String CURRENT = "DesktopUserRepositories:\n"
            + "  currentUserId=0\n"
            + "  DesktopRepository\n"
            + "    userId=0\n"
            + "    Display #0:\n"
            + "      activeTasks=[42]\n";

    @Test public void android17NestedDesksRetainDisplayOwnership() {
        assertEquals(Map.of(0, Set.of(42, 43, 44), 7, Set.of(45)),
                SystemUiDesktopRepositoryParser.parseTaskIdsByDisplay("""
                        DesktopUserRepositories:
                          currentUserId=0
                          DesktopRepository
                            userId=0
                            Display #0:
                              numOfDesks=2
                              activeDesk=3
                              desks:
                                Desk #3:
                                  activeTasks=[42]
                                  visibleTasks=[42]
                                  freeformTasksInZOrder=[42, 43]
                                  minimizedTasks=[43]
                                Desk #4:
                                  activeTasks=[44]
                                  minimizedTasks=[44]
                            Display #7:
                              numOfDesks=1
                              activeDesk=5
                              desks:
                                Desk #5:
                                  activeTasks=[45]
                                  visibleTasks=[45]
                          DesktopRepository
                            userId=10
                            Display #0:
                              desks:
                                Desk #6:
                                  activeTasks=[999]
                        """));
    }

    @Test
    public void followingControllerCannotContributeTaskLists() {
        assertEquals(Set.of(42), SystemUiDesktopRepositoryParser.parseTaskIds(
                CURRENT + "OtherController:\n  activeTasks=[999]\n", 0));
    }

    @Test
    public void repeatedRepositorySectionDoesNotInheritTheCurrentUser() {
        assertEquals(Set.of(42), SystemUiDesktopRepositoryParser.parseTaskIds(
                CURRENT + "DesktopUserRepositories:\n"
                        + "  DesktopRepository\n"
                        + "    userId=0\n"
                        + "    Display #0:\n"
                        + "      activeTasks=[999]\n", 0));
    }

    @Test
    public void surroundingDumpIndentationDoesNotChangeSectionOwnership() {
        final String nested = "  " + CURRENT.replace("\n", "\n  ").stripTrailing() + "\n";
        assertEquals(Set.of(42), SystemUiDesktopRepositoryParser.parseTaskIds(
                "Shell:\n" + nested + "  OtherController:\n    activeTasks=[999]\n", 0));
    }

    @Test
    public void displaySiblingDoesNotReuseThePreviousDisplay() {
        assertEquals(Set.of(42), SystemUiDesktopRepositoryParser.parseTaskIds(
                CURRENT + "    OtherState:\n      activeTasks=[999]\n", 0));
    }

    @Test
    public void findsCurrentUserPhoneDesktopTasks() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        Integer.valueOf(3516),
                        Integer.valueOf(3520),
                        Integer.valueOf(3521))),
                SystemUiDesktopRepositoryParser.parseTaskIds(
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
                                + "      activeTasks=[77]\n",
                        0));
    }

    @Test
    public void ignoresAnotherUserAndUnrelatedTaskLists() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(Integer.valueOf(42))),
                SystemUiDesktopRepositoryParser.parseTaskIds(
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
                                + "      activeTasks=[42, invalid, -1]\n",
                        0));
    }

    @Test
    public void readsRemovedDisplayWithoutMixingPhoneTasks() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        Integer.valueOf(77), Integer.valueOf(78))),
                SystemUiDesktopRepositoryParser.parseTaskIds(
                        "DesktopUserRepositories:\n"
                                + "  currentUserId=0\n"
                                + "  DesktopRepository\n"
                                + "    userId=0\n"
                                + "    Display #0:\n"
                                + "      activeTasks=[42]\n"
                                + "    Display #95:\n"
                                + "      activeTasks=[77]\n"
                                + "      freeformTasksInZOrder=[78]\n",
                        95));
    }

    @Test
    public void indexesEveryDisplayForTheCurrentUser() {
        final Map<Integer, Set<Integer>> expected = new LinkedHashMap<>();
        expected.put(Integer.valueOf(-1), new LinkedHashSet<>());
        expected.put(Integer.valueOf(0), new LinkedHashSet<>(
                Arrays.asList(Integer.valueOf(42))));
        expected.put(Integer.valueOf(95), new LinkedHashSet<>(
                Arrays.asList(Integer.valueOf(77), Integer.valueOf(78))));

        assertEquals(expected,
                SystemUiDesktopRepositoryParser.parseTaskIdsByDisplay(
                        "DesktopUserRepositories:\n"
                                + "  currentUserId=0\n"
                                + "  DesktopRepository\n"
                                + "    userId=0\n"
                                + "    Display #-1:\n"
                                + "      activeTasks=[]\n"
                                + "    Display #0:\n"
                                + "      activeTasks=[42]\n"
                                + "    Display #95:\n"
                                + "      activeTasks=[77]\n"
                                + "      minimizedTasks=[78]\n"));
    }
}
