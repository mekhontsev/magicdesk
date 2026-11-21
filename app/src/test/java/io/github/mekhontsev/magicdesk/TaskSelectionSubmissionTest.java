package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class TaskSelectionSubmissionTest {
    private static final String SUPPORT = """
            static final int WINDOWING_MODE_FREEFORM = 5;
            static final int WINDOWING_MODE_FULLSCREEN = 1;
            static int targetMode = 5, selections, atomics, missing = -1;
            static boolean failSelection;
            static Tx submitted;
            static final List<String> calls = new ArrayList<>();
            static class Tx { final List<String> operations = new ArrayList<>(); }
            static class HiddenTaskApi {
                static Object getService() { return new Object(); }
                static Object requireTask(Object service, int display, int task) {
                    check(display == 4, "display changed");
                    if (task == missing) throw new IllegalStateException("task missing");
                    return task;
                }
                static Object requireTaskToken(Object service, int display, int task) {
                    return requireTask(service, display, task);
                }
                static int getTaskWindowingMode(Object task) { return targetMode; }
            }
            static class FrameworkRuntime {
                static FrameworkRuntime current() { return new FrameworkRuntime(); }
                FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
            }
            static class FrameworkWindowingApi {
                Class<?> transactionClass() { return Tx.class; }
                Object newTransaction() { return new Tx(); }
                void reorder(Object tx, Object token, boolean top, boolean parents) {
                    ((Tx)tx).operations.add("order:" + token + ":" + top + ":" + parents);
                }
                void reorder(Object tx, Object token, boolean top) { reorder(tx, token, top, false); }
                void setFocusable(Object tx, Object token, boolean focusable) {
                    ((Tx)tx).operations.add("focusable:" + token + ":" + focusable);
                }
            }
            static class ShellWindowTransitionExecutor {
                static void applySelection(Object service, int display, Class<?> type, Object tx, int mode)
                        throws ReflectiveOperationException {
                    check(display == 4 && mode == targetMode, "wrong selection target");
                    selections++;
                    calls.add("selection:" + mode);
                    if (failSelection) throw new ReflectiveOperationException("selection failed");
                    submitted = (Tx)tx;
                    if (mode != 5) applyAtomic(service, type, tx);
                }
                static void applyAtomic(Object service, Class<?> type, Object tx) {
                    atomics++; submitted = (Tx)tx; calls.add("atomic");
                }
            }
            static int parseInt(String value, String label) { return Integer.parseInt(value); }
            static void assertFreeform() {
                check(selections == 1 && atomics == 0,
                        "freeform selection bypassed native surface assignment: " + calls);
                check(submitted.operations.equals(List.of("order:17:true:true", "order:42:true:true")),
                        "selection changed order or geometry: " + submitted.operations);
            }
            """;

    private static void verifyFocus(String body) throws Exception {
        RuntimeSourceFixture.verify(SUPPORT
                + RuntimeSourceFixture.methods("TaskWindowingCommand", "focus", "focusTasks",
                        "addFocusTasks", "focusTasksWithinCurrentParent", "addFocusTasksWithinCurrentParent",
                        "applyFocusTransaction", "addFocusOperations")
                + "public static void verify() throws Exception {" + body + "}");
    }

    @Test public void recoveryAndOrdinaryFocusUseTheSameSurfaceCommit() throws Exception {
        verifyFocus("""
                focusTasks(new Object(), 4, new int[]{17, 42});
                assertFreeform();
                """);
    }

    @Test public void commandLineFocusUsesTheSameSurfaceCommit() throws Exception {
        verifyFocus("""
                focus(4, new String[]{"focus", "4", "17", "42"});
                assertFreeform();
                """);
    }

    @Test public void suppliedTransactionIsCommittedOnceWithoutLosingOperations() throws Exception {
        verifyFocus("""
                Tx tx = new Tx(); tx.operations.add("existing");
                focusTasks(new Object(), 4, new int[]{17, 42}, Tx.class, tx);
                check(submitted == tx && selections == 1 && atomics == 0,
                        "supplied transaction bypassed selection");
                check(tx.operations.equals(List.of("existing", "order:17:true:true", "order:42:true:true")),
                        "transaction operations were replaced");
                """);
    }

    @Test public void fullscreenAndPlaneChildSelectionRetainTheirAtomicPath() throws Exception {
        verifyFocus("""
                targetMode = 1;
                focusTasks(new Object(), 4, new int[]{42});
                check(atomics == 1 && submitted.operations.equals(List.of("order:42:true:true")),
                        "fullscreen selection changed");
                calls.clear(); selections = 0; atomics = 0;
                focusTasksWithinCurrentParent(new Object(), 4, new int[]{42});
                check(selections == 0 && atomics == 1
                        && submitted.operations.equals(List.of("order:42:true:false")),
                        "plane child repair changed parent order or added a system transition");
                """);
    }

    @Test public void invalidPlansDoNotSubmitPartialSelections() throws Exception {
        verifyFocus("""
                for (int[] ids : new int[][]{null, new int[0], new int[]{17, 42}}) {
                    missing = 42;
                    try {
                        focusTasks(new Object(), 4, ids);
                        throw new AssertionError("invalid plan submitted");
                    } catch (IllegalArgumentException | IllegalStateException expected) { }
                    check(selections == 0 && atomics == 0, "partial plan submitted");
                }
                """);
    }

    @Test public void failedSelectionIsNotReplayedThroughAtomicFallback() throws Exception {
        verifyFocus("""
                failSelection = true;
                try {
                    focusTasks(new Object(), 4, new int[]{42});
                    throw new AssertionError("selection failure lost");
                } catch (ReflectiveOperationException expected) {
                    check(selections == 1 && atomics == 0, "failed selection replayed");
                }
                """);
    }

    private static void verifyPlaneExit(String body) throws Exception {
        RuntimeSourceFixture.verify(SUPPORT + """
                static class TaskDisplayAreaHandle {
                    final String name;
                    TaskDisplayAreaHandle(String name) { this.name = name; }
                    Object token() { return name; }
                }
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                void requirePlaneAnchor(Object service, int display, TaskDisplayAreaHandle plane) { }
                """ + RuntimeSourceFixture.methods("ShellFullscreenTaskPlanes",
                        "applyPlaneExitFocus", "addPlaneExitFocusOperations")
                + "public static void verify() throws Exception {"
                + "Fixture f = new Fixture(); TaskDisplayAreaHandle closing = new TaskDisplayAreaHandle(\"closing\");"
                + body + "}");
    }

    @Test public void freeformSuccessorUsesSurfaceCommitWhenLeavingFullscreen() throws Exception {
        verifyPlaneExit("""
                f.applyPlaneExitFocus(new Object(), 4, closing, 42);
                check(selections == 1 && atomics == 0, "freeform successor bypassed surface commit");
                check(submitted.operations.equals(List.of("focusable:closing:false",
                        "order:closing:false:false", "order:42:true:true")),
                        "fullscreen exit split or changed its transaction");
                """);
    }

    @Test public void fullscreenSuccessorAndEmptyExitRemainAtomic() throws Exception {
        verifyPlaneExit("""
                targetMode = 1;
                f.mPlanes.put(42, new TaskDisplayAreaHandle("survivor"));
                f.mPlaneOrder.addAll(List.of(42, 17));
                f.applyPlaneExitFocus(new Object(), 4, closing, 42);
                check(atomics == 1 && f.mPlaneOrder.equals(List.of(17, 42)), "plane successor changed");
                check(submitted.operations.equals(List.of("focusable:closing:false", "order:closing:false:false",
                        "focusable:survivor:true", "order:survivor:true:false", "order:42:true:true")),
                        "plane selection lost ownership operations");
                selections = 0; atomics = 0;
                f.applyPlaneExitFocus(new Object(), 4, closing, -1);
                check(selections == 0 && atomics == 1 && submitted.operations.size() == 2,
                        "empty exit looked up a successor or started a transition");
                """);
    }

    @Test public void failedExitSelectionDoesNotAdvanceCommittedPlaneOrder() throws Exception {
        verifyPlaneExit("""
                targetMode = 1; failSelection = true;
                f.mPlanes.put(42, new TaskDisplayAreaHandle("survivor"));
                f.mPlaneOrder.addAll(List.of(42, 17));
                try {
                    f.applyPlaneExitFocus(new Object(), 4, closing, 42);
                    throw new AssertionError("failed exit acknowledged");
                } catch (ReflectiveOperationException expected) {
                    check(selections == 1 && atomics == 0 && f.mPlaneOrder.equals(List.of(42, 17)),
                            "failed exit was replayed or committed locally");
                }
                """);
    }
}
