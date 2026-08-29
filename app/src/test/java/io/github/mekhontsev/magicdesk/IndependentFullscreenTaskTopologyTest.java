package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class IndependentFullscreenTaskTopologyTest {
    @Test
    public void insertsDesktopHostBeforeVisibleApplicationStack() {
        assertArrayEquals(
                new int[]{10, 21, 22},
                IndependentFullscreenTaskTopology.withDesktopHostBoundary(
                        new int[]{21, 22}, 10));
    }

    @Test
    public void preservesExplicitConcealedAndVisiblePartition() {
        assertArrayEquals(
                new int[]{21, 10, 22},
                IndependentFullscreenTaskTopology.withDesktopHostBoundary(
                        new int[]{21, 10, 22}, 10));
    }
}
