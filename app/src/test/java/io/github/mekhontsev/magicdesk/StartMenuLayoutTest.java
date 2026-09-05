package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StartMenuLayoutTest {
    @Test
    public void narrowPhoneAndDesktopHaveIndependentGridCapacity() {
        assertEquals(3, StartMenuLayout.columns(332));
        assertEquals(4, StartMenuLayout.columns(532));
        assertEquals(3, StartMenuLayout.columns(332));
    }

    @Test
    public void keyboardResizeKeepsOneScrollableRow() {
        assertEquals(3, StartMenuLayout.rows(430));
        assertEquals(1, StartMenuLayout.rows(180));
        assertEquals(1, StartMenuLayout.rows(40));
    }

    @Test
    public void largeViewportHasBoundedCapacity() {
        assertEquals(4, StartMenuLayout.columns(2000));
        assertEquals(6, StartMenuLayout.rows(2000));
    }

    @Test
    public void unmeasuredViewportRemainsValid() {
        assertEquals(1, StartMenuLayout.columns(0));
        assertEquals(1, StartMenuLayout.rows(0));
    }

}
