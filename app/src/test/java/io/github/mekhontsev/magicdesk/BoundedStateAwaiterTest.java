package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedStateAwaiterTest {
    @Test
    public void diagnosticReasonIsNotLocalized() {
        final Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            BoundedStateAwaiter.pause(BoundedStateAwaiter.Reason.INPUT_DEVICE, 0L);
            assertTrue(BoundedStateAwaiter.diagnostics().endsWith("lastReason=input_device"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void samplesUntilConditionOrDeadline() {
        final AtomicInteger samples = new AtomicInteger();

        final Integer value = BoundedStateAwaiter.awaitUnchecked(
                BoundedStateAwaiter.Reason.TASK_APPEARANCE,
                100L,
                1L,
                () -> Integer.valueOf(samples.incrementAndGet()),
                sampled -> sampled.intValue() >= 3);

        assertEquals(3, value.intValue());
        assertEquals(3, samples.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnclassifiedWait() {
        BoundedStateAwaiter.awaitUnchecked(
                null,
                1L,
                1L,
                () -> Boolean.TRUE,
                Boolean::booleanValue);
    }
}
