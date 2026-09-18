package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;
import org.junit.Test;

public final class X11InspectionLifecycleTest {
    @Test public void boundedRepliesExpireAndCannotOutliveTheirConnection() throws Exception {
        Path runtime = Path.of("../x11-runtime/src/main/java/io/github/mekhontsev/magicdesk/x11").toAbsolutePath();
        String methods = RuntimeSourceFixture.methods(runtime.resolve("X11Session.java").toString(),
                "inspectWindow", "onNativeInspectionNode", "onNativeInspectionDone", "cancelInspections");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk.x11", """
            static class Handler {
                final ArrayDeque<Runnable> posted = new ArrayDeque<>();
                final Set<Runnable> deadlines = new LinkedHashSet<>();
                void post(Runnable task) { posted.add(task); }
                void postDelayed(Runnable task, long delay) { check(delay > 0, "bounded deadline"); deadlines.add(task); }
                void removeCallbacks(Runnable task) { deadlines.remove(task); }
                void flush() { while (!posted.isEmpty()) posted.remove().run(); }
                void expire() {
                    var tasks = List.copyOf(deadlines); deadlines.clear(); tasks.forEach(Runnable::run); flush();
                }
            }
            static class Session {
                final Handler handler = new Handler();
                boolean connected = true;
                long nativeHandle;
                int nextInspection, sent;
                private final Map<Integer, Inspection> inspections = new LinkedHashMap<>();
                private record Inspection(long window, int limit, ArrayList<X11WindowInspection.Node> nodes,
                        CompletableFuture<X11WindowInspection> result, Runnable timeout) { }
                <T> T call(Callable<T> operation) {
                    try { return operation.call(); } catch (RuntimeException e) { throw e; }
                    catch (Exception e) { throw new AssertionError(e); }
                }
                void nativeInspectWindow(long handle, int serial, int window, int limit) { sent++; }
            """ + methods + """
            }
            static void failed(CompletableFuture<?> result) {
                check(result.isCompletedExceptionally(), "request must fail explicitly");
            }
            static void invalid(Runnable operation) {
                try { operation.run(); throw new AssertionError("request should be rejected"); }
                catch (IllegalArgumentException | IllegalStateException expected) { }
            }
            public static void verify() {
                var session = new Session();
                var node = new X11WindowInspection.Node(0xffffffffL, 1, 0, 0, "main", X11WindowInspection.Type.NORMAL,
                        new X11WindowInspection.Bounds(0, 0, 10, 10), true, true, false, false, false);
                var first = session.inspectWindow(node.id(), 2);
                session.onNativeInspectionNode(1, node);
                check(!first.isDone(), "partial reply is not a completed snapshot");
                session.onNativeInspectionDone(1, -1, 3, 2, 800, 600, 1, true, false);
                check(first.join().windowId() == node.id() && first.join().focus().windowId() == 3, "unsigned ID and exact focus");
                session.handler.flush();
                check(session.inspections.isEmpty() && session.handler.deadlines.isEmpty(), "completion releases deadline");

                var expired = session.inspectWindow(2, 1);
                session.handler.expire(); failed(expired);
                var next = session.inspectWindow(3, 1);
                session.onNativeInspectionNode(2, node);
                session.onNativeInspectionDone(2, 2, 0, 0, 800, 600, 1, true, false);
                check(!next.isDone() && session.inspections.size() == 1, "late replies cannot satisfy a newer request");
                session.cancelInspections(); session.handler.flush(); failed(next);
                check(session.handler.deadlines.isEmpty() && session.inspections.isEmpty(), "connection closure releases pending reads");

                var cancelled = session.inspectWindow(4, 1); cancelled.cancel(false); session.handler.flush();
                check(session.inspections.isEmpty() && session.handler.deadlines.isEmpty(), "caller cancellation releases request");
                var overflow = session.inspectWindow(5, 1);
                session.onNativeInspectionNode(5, node); session.onNativeInspectionNode(5, node); failed(overflow);
                var mismatch = session.inspectWindow(6, 1);
                session.onNativeInspectionDone(6, 7, 0, 0, 800, 600, 0, false, false); failed(mismatch);
                var incomplete = session.inspectWindow(7, 1);
                session.onNativeInspectionDone(7, 7, 0, 0, 800, 600, 1, true, false); failed(incomplete);
                session.handler.flush();
                check(session.inspections.isEmpty() && session.handler.deadlines.isEmpty(), "invalid replies release state");
                for (int i = 0; i < 4; i++) session.inspectWindow(8 + i, 1);
                invalid(() -> session.inspectWindow(12, 1));
                session.cancelInspections(); session.handler.flush();
                invalid(() -> session.inspectWindow(0, 1));
                invalid(() -> session.inspectWindow(1, 257));
                session.connected = false; invalid(() -> session.inspectWindow(1, 1));
            }
            """, runtime.resolve("X11WindowInspection.java").toString());
    }
}
