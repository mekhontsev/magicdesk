package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskObservationProviderTest {
    @Test
    public void publishesFullObservationAndTreatsUnavailableAsUnknown() throws Exception {
        verify("""
                    check(f.observedTaskSnapshot(4) == null, "unobserved desktop became empty");
                    TaskRepository.Snapshot full = new TaskRepository.Snapshot(List.of(41, 42), List.of(10), true);
                    f.publishTaskObservation(full);
                    check(f.observedTaskSnapshot(4) == full, "provider filtered or replaced full observation");
                    check(f.observedTaskSnapshot(0) == null, "observation escaped active display scope");
                    f.publishTaskObservation(new TaskRepository.Snapshot(List.of(), List.of(), false));
                    check(f.observedTaskSnapshot(4) == null, "unavailable observation became empty or retained old tasks");
                    f.publishTaskObservation(full); f.mTaskWatcherReady = false;
                    check(f.observedTaskSnapshot(4) == null, "shell loss retained usable observation");
                    f.publishTaskObservation(full); f.mTaskWatcherReady = true;
                    check(f.observedTaskSnapshot(4) == null, "disconnected publication survived reconnect");
                    f.publishTaskObservation(full); f.mRunning = false;
                    check(f.observedTaskSnapshot(4) == null, "stopped desktop retained usable observation");
                """);
    }

    @Test
    public void restoredIdenticalSnapshotWakesOncePerAvailabilityChange() throws Exception {
        verify("""
                TaskRepository.Snapshot full = new TaskRepository.Snapshot(List.of(41, 42), List.of(10), true);
                TaskRepository.Snapshot unavailable = new TaskRepository.Snapshot(List.of(), List.of(), false);
                f.publishTaskObservation(null); f.publishTaskObservation(unavailable);
                check(DesktopAutomationEventJournal.observed.isEmpty(), "initial unknown refresh emitted a wake");
                f.publishTaskObservation(full);
                check(DesktopAutomationEventJournal.observed.size() == 1, "first available observation did not wake");
                f.publishTaskObservation(full);
                f.publishTaskObservation(new TaskRepository.Snapshot(List.of(41, 42), List.of(10), true));
                check(DesktopAutomationEventJournal.observed.size() == 1, "unchanged refresh emitted availability noise");
                f.publishTaskObservation(unavailable);
                check(DesktopAutomationEventJournal.observed.size() == 2, "availability loss did not wake");
                f.publishTaskObservation(unavailable); f.publishTaskObservation(null);
                check(DesktopAutomationEventJournal.observed.size() == 2, "repeated invalidation emitted a wake");
                f.publishTaskObservation(full);
                check(DesktopAutomationEventJournal.observed.size() == 3, "restoring identical snapshot did not wake");
                check(DesktopAutomationEventJournal.observed.get(0) == full
                        && DesktopAutomationEventJournal.observed.get(1) == null
                        && DesktopAutomationEventJournal.observed.get(2) == full,
                        "journal signalled before provider published availability");
                f.publishTaskObservation(new TaskRepository.Snapshot(List.of(43), List.of(11), true));
                check(DesktopAutomationEventJournal.observed.size() == 3, "task delta emitted redundant availability event");
                check(f.observedTaskSnapshot(0) == null, "journal publication widened display scope");
                """);
    }

    @Test
    public void disconnectedAndInactivePublicationsInvalidateWithoutResurrection() throws Exception {
        verify("""
                TaskRepository.Snapshot full = new TaskRepository.Snapshot(List.of(41), List.of(10), true);
                f.publishTaskObservation(full);
                f.mTaskWatcherReady = false; f.publishTaskObservation(null);
                check(DesktopAutomationEventJournal.observed.size() == 2, "disconnect did not wake");
                f.publishTaskObservation(full);
                check(DesktopAutomationEventJournal.observed.size() == 2, "disconnected publication emitted a wake");
                f.mTaskWatcherReady = true;
                check(f.observedTaskSnapshot(4) == null, "reconnect resurrected stale observation");
                f.publishTaskObservation(full);
                f.mRunning = false; f.publishTaskObservation(null);
                check(DesktopAutomationEventJournal.observed.size() == 4, "stop did not wake");
                f.publishTaskObservation(full); f.publishTaskObservation(null);
                check(DesktopAutomationEventJournal.observed.size() == 4, "inactive refresh emitted a wake");
                check(f.observedTaskSnapshot(4) == null, "inactive provider returned tasks");
                """);
    }

    @Test
    public void allLifecycleInvalidationsUseThePublicationOwner() throws Exception {
        for (final String method : new String[] {
                "start", "stop", "setTaskWatcherEnabled", "onDisconnected"}) {
            final String body = RuntimeSourceFixture.methods("DesktopTaskController", method);
            assertTrue(method + " bypasses the availability wake owner",
                    body.contains("publishTaskObservation(null)"));
            assertFalse(method + " directly accesses provider state",
                    body.contains("mObservedTaskSnapshot"));
        }
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskRepository { record Snapshot(List<Integer> tasks, List<Integer> phoneTasks, boolean available) {} }
                boolean mRunning = true, mTaskWatcherReady = true;
                int mDisplayId = 4;
                volatile TaskRepository.Snapshot mObservedTaskSnapshot;
                static Fixture current;
                static class DesktopAutomationEventJournal {
                    static final List<TaskRepository.Snapshot> observed = new ArrayList<>();
                    static long record(String type, String operation, boolean success, String detail) {
                        observed.add(current.observedTaskSnapshot(4)); return observed.size();
                    }
                }
                public static void verify() {
                    Fixture f = new Fixture(); current = f;
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopTaskController",
                "observedTaskSnapshot", "publishTaskObservation"));
    }
}
