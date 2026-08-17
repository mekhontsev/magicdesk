package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TaskbarOverflowPolicyTest {
    @Test
    public void keepsAllItemsWhenTheyFit() {
        assertEquals(4, TaskbarOverflowPolicy.visibleItemCount(4, 192, 48));
    }

    @Test
    public void reservesOneSlotWhenItemsOverflow() {
        assertEquals(3, TaskbarOverflowPolicy.visibleItemCount(8, 192, 48));
    }

    @Test
    public void showsOnlyOverflowInOneSlot() {
        assertEquals(0, TaskbarOverflowPolicy.visibleItemCount(3, 32, 48));
    }

    @Test
    public void defersClippingUntilTheTaskbarIsMeasured() {
        assertEquals(5, TaskbarOverflowPolicy.visibleItemCount(5, 0, 48));
    }
}
