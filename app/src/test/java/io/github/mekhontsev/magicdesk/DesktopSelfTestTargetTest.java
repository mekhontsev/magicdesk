package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class DesktopSelfTestTargetTest {
    @Test
    public void distinguishesDisplayByIdentityAndTransport() {
        assertTrue(DesktopSelfTestTarget.PHONE.matchesDisplay(
                0, DesktopDisplayTarget.phone()));
        assertFalse(DesktopSelfTestTarget.PHONE.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));

        assertTrue(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                195, DesktopDisplayTarget.simulated(195)));
        assertFalse(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));

        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));
        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                4, DesktopDisplayTarget.wireless(4)));
        assertFalse(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                195, DesktopDisplayTarget.simulated(195)));
        assertFalse(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                3, DesktopDisplayTarget.wireless(4)));
    }

    @Test
    public void platformDesktopBlocksSimulatedTestWithoutLocalActivity() {
        assertEquals(4, DesktopSelfTestController
                .findBlockingDesktopDisplay(-1, 4));
        assertEquals(3, DesktopSelfTestController
                .findBlockingDesktopDisplay(3, 4));
        assertEquals(-1, DesktopSelfTestController
                .findBlockingDesktopDisplay(-1, -1));
    }

    @Test
    public void distinguishesLiveTasksFromUnavailableFirmwareEntries() {
        final Map<Integer, Set<Integer>> repository = new LinkedHashMap<>();
        repository.put(Integer.valueOf(0),
                Collections.singleton(Integer.valueOf(7)));
        repository.put(Integer.valueOf(79),
                Collections.singleton(Integer.valueOf(10996)));
        repository.put(Integer.valueOf(80),
                new LinkedHashSet<>(Arrays.asList(
                        Integer.valueOf(11001), Integer.valueOf(11002))));
        final Set<Integer> liveTasks = Collections.singleton(
                Integer.valueOf(11002));

        final Map<Integer, Set<Integer>> live = DesktopSelfTestController
                .selectExternalRepositoryTasks(
                        repository, liveTasks, true);
        final Map<Integer, Set<Integer>> unavailable =
                DesktopSelfTestController.selectExternalRepositoryTasks(
                        repository, liveTasks, false);

        assertEquals("{80=[11002]}", live.toString());
        assertEquals("{79=[10996], 80=[11001]}",
                unavailable.toString());
    }
}
