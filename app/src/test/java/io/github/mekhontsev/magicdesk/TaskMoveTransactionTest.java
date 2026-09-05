package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.graphics.Rect;
import android.os.Bundle;

import org.junit.Test;

import io.github.mekhontsev.magicdesk.FrameworkWindowingApiTest.FakeToken;
import io.github.mekhontsev.magicdesk.FrameworkWindowingApiTest.FakeTransaction;

public final class TaskMoveTransactionTest {
    @Test
    public void fullscreenMoveClearsSourceBoundsAndDensityInLaunchTransaction()
            throws Exception {
        final Rect sourceBounds = bounds(557, 214, 1757, 946);
        final FakeToken token = new FakeToken();
        final Bundle options = new Bundle();
        final MoveTransaction transaction = create(
                token, options, 1, sourceBounds, DesktopTaskDensity.INHERIT);

        assertSame(token, transaction.token);
        assertEquals(1, transaction.mode);
        assertNotSame(sourceBounds, transaction.bounds);
        assertEquals(0, transaction.bounds.left);
        assertEquals(0, transaction.bounds.top);
        assertEquals(0, transaction.bounds.right);
        assertEquals(0, transaction.bounds.bottom);
        assertEquals(DesktopTaskDensity.INHERIT, transaction.density);
        assertLaunch(transaction, options);
    }

    @Test
    public void freeformMoveKeepsDestinationBoundsAndDensityInSameTransaction()
            throws Exception {
        final Rect targetBounds = bounds(20, 30, 800, 900);
        final Bundle options = new Bundle();
        final MoveTransaction transaction = create(
                new FakeToken(), options, 5, targetBounds, 200);

        assertEquals(5, transaction.mode);
        assertSame(targetBounds, transaction.bounds);
        assertEquals(200, transaction.density);
        assertLaunch(transaction, options);
    }

    @Test
    public void unchangedDensityDoesNotWriteAnOverride() throws Exception {
        final MoveTransaction transaction = create(
                new FakeToken(), new Bundle(), 1, null,
                DesktopTaskDensity.UNCHANGED);

        assertEquals(Integer.MIN_VALUE, transaction.density);
    }

    @Test
    public void rejectsUnsupportedModeAndInvalidFreeformGeometry() {
        assertThrows(IllegalArgumentException.class,
                () -> create(new FakeToken(), new Bundle(), 6, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> create(new FakeToken(), new Bundle(), 5, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> create(new FakeToken(), new Bundle(), 5,
                        bounds(20, 30, 20, 900), 0));
        assertThrows(IllegalArgumentException.class,
                () -> create(new FakeToken(), new Bundle(), 1, null, -2));
    }

    private static MoveTransaction create(
            final FakeToken token,
            final Bundle options,
            final int mode,
            final Rect bounds,
            final int density) throws ReflectiveOperationException {
        return (MoveTransaction) TaskDisplayAreaLaunchCommand.createTaskMoveTransaction(
                FrameworkWindowingApi.inspect(FakeToken.class, MoveTransaction.class),
                token, 42, options, mode, bounds, density);
    }

    private static void assertLaunch(
            final MoveTransaction transaction, final Bundle options) {
        assertEquals(42, transaction.startedTask);
        assertSame(options, transaction.options);
        assertEquals(1, transaction.starts);
        assertFalse(transaction.hidden);
        assertFalse(transaction.translucent);
    }

    private static Rect bounds(
            final int left, final int top, final int right, final int bottom) {
        final Rect result = new Rect();
        result.left = left;
        result.top = top;
        result.right = right;
        result.bottom = bottom;
        return result;
    }

    public static final class MoveTransaction extends FakeTransaction {
        Rect bounds;
        int density = Integer.MIN_VALUE;
        boolean translucent = true;
        int startedTask = -1;
        int starts;
        Bundle options;

        public MoveTransaction() {
            hidden = true;
        }

        @Override
        public void setBounds(final FakeToken value, final Rect newBounds) {
            token = value;
            bounds = newBounds;
        }

        public void setDensityDpi(final FakeToken value, final int newDensity) {
            token = value;
            density = newDensity;
        }

        @Override
        public void setForceTranslucent(final FakeToken value, final boolean enabled) {
            token = value;
            translucent = enabled;
        }

        @Override
        public void startTask(final int taskId, final Bundle launchOptions) {
            assertFalse(hidden);
            assertFalse(translucent);
            startedTask = taskId;
            options = launchOptions;
            starts++;
        }
    }
}
