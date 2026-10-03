package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedDependentWindowTest {
    @Test public void orderingUsesAnAboveContentRootChildOnlyAfterItsCommit() throws Exception {
        verifyOrdering("""
            var view = new Fixture();
            var desktopRoot = new AttachedSurfaceControl();
            view.prepareOrder(desktopRoot);
            check(built == 1 && rootUsed == view.anchor.root, "not attached to the application root");
            check(layer == 1 && visible && target == view.orderingAnchor, "not above application content");
            check(applied == 1 && transactionsClosed == 1 && orders == 0, "ordering raced anchor commit");
            commit.run();
            check(orders == 1 && orderedRoot == desktopRoot, "lost independent structural parent");
            """);
        String order = RuntimeSourceFixture.methods("HostedDependentWindow", "order");
        org.junit.Assert.assertTrue(order.contains("retain(orderingAnchor)"));
        org.junit.Assert.assertFalse(order.contains("anchor.getSurfaceControl()"));
    }

    @Test public void lateAnchorCommitCannotReopenAClosedDependentWindow() throws Exception {
        verifyOrdering("""
            var view = new Fixture();
            view.prepareOrder(new AttachedSurfaceControl());
            view.closed = true;
            commit.run();
            check(orders == 0, "late commit revived closed presentation");
            """);
    }

    @Test public void unavailableRootOrRejectedAttachmentNeverOrdersTheChild() throws Exception {
        verifyOrdering("""
            var missing = new Fixture();
            missing.anchor.root = null;
            missing.prepareOrder(new AttachedSurfaceControl());
            check(missing.failure != null && built == 0 && orders == 0, "missing root admitted");
            var lost = new Fixture();
            lost.anchor.root.available = false;
            lost.prepareOrder(new AttachedSurfaceControl());
            check(lost.failure != null && applied == 0 && orders == 0, "lost root admitted");
            var rejected = new Fixture();
            reject = true;
            rejected.prepareOrder(new AttachedSurfaceControl());
            check(rejected.failure != null && transactionsClosed == 1 && orders == 0, "rejection leaked or ordered");
            """);
    }

    @Test public void closeReleasesChildrenBeforeParentAndIsReentrant() throws Exception {
        verify("""
            var view = new Fixture();
            view.ready.whenComplete((unused, error) -> view.close());
            view.ended.whenComplete((unused, error) -> view.close());
            view.close(); view.close();
            check(releases.equals(List.of("host", "package", "ordering", "parent")), "release order or repetition");
            check(view.ready.isCompletedExceptionally() && view.ended.isCompletedExceptionally(), "unfinished receipts");
            check(view.host == null && view.surfacePackage == null && view.parent == null && view.orderingAnchor == null, "retained resources");
            check(detaches == 1, "ordering surface retained in application root");
            check(callbacksRemoved == 3 && view.closed, "callbacks retained");
            """);
    }

    @Test public void cleanupFailureDoesNotLeakSiblingResourcesOrReceipts() throws Exception {
        verify("""
            var view = new Fixture();
            view.host.reject = view.surfacePackage.reject = view.parent.reject = true;
            rejectDetach = true;
            var failure = new IOException("lost anchor");
            view.fail(failure);
            check(releases.equals(List.of("host", "package", "ordering", "parent")), "cleanup stopped early");
            check(failure.getSuppressed().length == 4, "cleanup failures lost");
            check(view.ended.isCompletedExceptionally() && view.ready.isCompletedExceptionally(), "receipt stranded");
            """);
    }

    @Test public void parentLossCannotUndoAnAlreadyReportedAttachment() throws Exception {
        verify("""
            var view = new Fixture();
            view.ready.complete(null);
            view.fail(new IOException("Desktop closed"));
            check(!view.ready.isCompletedExceptionally() && view.ended.isCompletedExceptionally(), "lifetime conflated with admission");
            check(releases.size() == 4, "active child retained after Desktop close");
            """);
    }

    private static void verifyOrdering(String body) throws Exception {
        RuntimeSourceFixture.verify("""
            static int built, applied, transactionsClosed, orders, layer;
            static boolean visible, reject;
            static Runnable commit;
            static SurfaceControl target;
            static AttachedSurfaceControl rootUsed, orderedRoot;
            static class SurfaceControl {
                static class Builder {
                    Builder setName(String name) { return this; }
                    SurfaceControl build() { built++; return new SurfaceControl(); }
                }
                static class Transaction implements AutoCloseable {
                    Transaction setLayer(SurfaceControl surface, int z) {
                        if (reject) throw new IllegalStateException("rejected");
                        target = surface; layer = z; return this;
                    }
                    Transaction setVisibility(SurfaceControl surface, boolean value) { visible = value; return this; }
                    void addTransactionCommittedListener(java.util.concurrent.Executor executor, Runnable callback) {
                        commit = () -> executor.execute(callback);
                    }
                    void apply() { applied++; }
                    public void close() { transactionsClosed++; }
                }
            }
            static class AttachedSurfaceControl {
                boolean available = true;
                SurfaceControl.Transaction buildReparentTransaction(SurfaceControl surface) {
                    rootUsed = this;
                    return available ? new SurfaceControl.Transaction() : null;
                }
            }
            static class Anchor {
                AttachedSurfaceControl root = new AttachedSurfaceControl();
                AttachedSurfaceControl getRootSurfaceControl() { return root; }
            }
            static class Handler { void post(Runnable action) { action.run(); } }
            final Handler main = new Handler();
            final Anchor anchor = new Anchor();
            SurfaceControl orderingAnchor;
            boolean closed;
            Throwable failure;
            void fail(Throwable error) { failure = error; closed = true; }
            void order(AttachedSurfaceControl root) { orders++; orderedRoot = root; }
            public static void verify() throws Exception {
            """ + body + "}\n" + RuntimeSourceFixture.methods("HostedDependentWindow", "prepareOrder"));
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify("""
            static final List<String> releases = new ArrayList<>();
            static int callbacksRemoved, detaches;
            static boolean rejectDetach;
            static class SurfaceControl {
                static class Transaction implements AutoCloseable {
                    Transaction reparent(Resource surface, Object parent) {
                        check(surface.name.equals("ordering") && parent == null, "wrong surface detached");
                        detaches++;
                        if (rejectDetach) throw new IllegalStateException("detach failed");
                        return this;
                    }
                    void apply() { }
                    public void close() { }
                }
            }
            static class Resource {
                final String name;
                boolean reject;
                Resource(String name) { this.name = name; }
                void release() {
                    releases.add(name);
                    if (reject) throw new IllegalStateException(name);
                }
                void close() { release(); }
            }
            static class Handler { void removeCallbacks(Object task) { callbacksRemoved++; } }
            static class Anchor {
                Anchor getHolder() { return this; }
                Anchor getViewTreeObserver() { return this; }
                void removeCallback(Object listener) { callbacksRemoved++; }
                void removeOnPreDrawListener(Object listener) { callbacksRemoved++; }
            }
            final Handler main = new Handler();
            final Anchor anchor = new Anchor();
            final Object timeout = new Object(), lifecycle = new Object(), drawing = new Object();
            final CompletableFuture<Void> ready = new CompletableFuture<>(), ended = new CompletableFuture<>();
            Resource host = new Resource("host"), surfacePackage = new Resource("package"), parent = new Resource("parent");
            Resource orderingAnchor = new Resource("ordering");
            boolean closed;
            static void checkThread() { }
            public static void verify() throws Exception {
            """ + body + "}\n" + RuntimeSourceFixture.methods("HostedDependentWindow", "fail", "close", "releaseOrderingAnchor"));
    }
}
