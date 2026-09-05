package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ShellWindowTransitionExecutorTest {
    @Test
    public void surfaceTransactionsJoinTheWindowManagerQueue()
            throws ReflectiveOperationException {
        final FakeOrganizer organizer = new FakeOrganizer(true);
        ShellWindowTransitionExecutor.shareSurfaceTransactionQueue(organizer);
        assertEquals(1, organizer.calls);
    }

    @Test
    public void missingWindowManagerQueueCannotSilentlyUseIndependentOrder() {
        final FakeOrganizer organizer = new FakeOrganizer(false);
        assertThrows(IllegalStateException.class, () ->
                ShellWindowTransitionExecutor.shareSurfaceTransactionQueue(organizer));
        assertEquals(1, organizer.calls);
    }

    @Test
    public void missingQueueApiIsNotReportedAsShared() {
        assertThrows(NoSuchMethodException.class, () ->
                ShellWindowTransitionExecutor.shareSurfaceTransactionQueue(new Object()));
    }

    @Test
    public void freeformSelectionUsesNativeLayerAssignmentRegardlessOfVisibility() {
        assertTrue(ShellWindowTransitionExecutor.selectionRequiresSystemTransition(
                FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM));
    }

    @Test
    public void fullscreenPlanesKeepTheirAtomicSelection() {
        assertFalse(ShellWindowTransitionExecutor.selectionRequiresSystemTransition(
                FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void freeformSubmitsTheUnmodifiedTransactionOnceToTheSystem() throws Exception {
        final FakeOrganizer organizer = new FakeOrganizer(true);
        final FakeService service = new FakeService();
        final Object transaction = new Object();
        selectWithOrganizer(organizer, service, transaction,
                FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM);
        assertEquals(1, organizer.transitions);
        assertEquals(3, organizer.transitionType);
        assertSame(transaction, organizer.transaction);
        assertEquals(0, service.atomicCommits);
        assertEquals(Arrays.asList("transition", "barrier"), service.steps);
        assertTrue(service.waitedForAnimations);
    }

    @Test
    public void fullscreenSubmitsOnlyTheAtomicTransaction() throws Exception {
        final FakeOrganizer organizer = new FakeOrganizer(true);
        final FakeService service = new FakeService();
        final Object transaction = new Object();
        selectWithOrganizer(organizer, service, transaction,
                FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN);
        assertEquals(0, organizer.transitions);
        assertEquals(1, service.atomicCommits);
        assertSame(transaction, service.transaction);
        assertEquals(Arrays.asList("atomic"), service.steps);
    }

    @Test
    public void rejectedSystemSelectionDoesNotFallBackToAnotherCommit() throws Exception {
        final FakeOrganizer organizer = new FakeOrganizer(true);
        organizer.token = null;
        final FakeService service = new FakeService();
        assertThrows(IllegalStateException.class, () ->
                selectWithOrganizer(organizer, service, new Object(),
                        FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM));
        assertEquals(1, organizer.transitions);
        assertEquals(0, service.atomicCommits);
        assertEquals(Arrays.asList("transition"), service.steps);
    }

    @Test
    public void failedBarrierCannotCompleteSelectionOrSubmitAnotherWct() throws Exception {
        final FakeOrganizer organizer = new FakeOrganizer(true);
        final FakeService service = new FakeService();
        service.failBarrier = true;
        assertThrows(ReflectiveOperationException.class, () ->
                selectWithOrganizer(organizer, service, new Object(),
                        FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM));
        assertEquals(Arrays.asList("transition", "barrier"), service.steps);
        assertEquals(0, service.atomicCommits);
    }

    private static void selectWithOrganizer(final FakeOrganizer organizer,
            final FakeService service, final Object transaction, final int mode)
            throws Exception {
        final Field cached = ShellWindowTransitionExecutor.class
                .getDeclaredField("sWindowOrganizer");
        cached.setAccessible(true);
        final Object previous = cached.get(null);
        final Field windowManager = FrameworkWindowCommitBarrier.class
                .getDeclaredField("sWindowManager");
        windowManager.setAccessible(true);
        final Object previousWindowManager = windowManager.get(null);
        try {
            organizer.steps = service.steps;
            cached.set(null, organizer);
            windowManager.set(null, service);
            ShellWindowTransitionExecutor.applySelection(
                    service, 49, Object.class, transaction, mode);
        } finally {
            cached.set(null, previous);
            windowManager.set(null, previousWindowManager);
        }
    }

    public static final class FakeService {
        int atomicCommits;
        Object transaction;
        final List<String> steps = new ArrayList<>();
        boolean waitedForAnimations;
        boolean failBarrier;

        public FakeService getWindowOrganizerController() {
            return this;
        }

        public void applyTransaction(final Object value) {
            steps.add("atomic");
            atomicCommits++;
            transaction = value;
        }

        public void syncInputTransactions(final boolean waitForAnimations) {
            steps.add("barrier");
            waitedForAnimations = waitForAnimations;
            if (failBarrier) {
                throw new IllegalStateException("window barrier failed");
            }
        }
    }

    public static final class FakeOrganizer {
        private final boolean available;
        int calls;
        int transitions;
        int transitionType;
        Object transaction;
        List<String> steps;
        IBinder token = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> null);

        FakeOrganizer(final boolean queueAvailable) {
            available = queueAvailable;
        }

        public boolean shareTransactionQueue() {
            calls++;
            return available;
        }

        public IBinder startNewTransition(final int type, final Object value) {
            if (steps != null) {
                steps.add("transition");
            }
            transitions++;
            transitionType = type;
            transaction = value;
            return token;
        }
    }
}
