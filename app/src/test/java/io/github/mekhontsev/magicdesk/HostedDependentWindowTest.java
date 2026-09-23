package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedDependentWindowTest {
    @Test public void closeReleasesChildrenBeforeParentAndIsReentrant() throws Exception {
        verify("""
            var view = new Fixture();
            view.ready.whenComplete((unused, error) -> view.close());
            view.ended.whenComplete((unused, error) -> view.close());
            view.close(); view.close();
            check(releases.equals(List.of("host", "package", "parent")), "release order or repetition");
            check(view.ready.isCompletedExceptionally() && view.ended.isCompletedExceptionally(), "unfinished receipts");
            check(view.host == null && view.surfacePackage == null && view.parent == null, "retained resources");
            check(callbacksRemoved == 3 && view.closed, "callbacks retained");
            """);
    }

    @Test public void cleanupFailureDoesNotLeakSiblingResourcesOrReceipts() throws Exception {
        verify("""
            var view = new Fixture();
            view.host.reject = view.surfacePackage.reject = view.parent.reject = true;
            var failure = new IOException("lost anchor");
            view.fail(failure);
            check(releases.equals(List.of("host", "package", "parent")), "cleanup stopped early");
            check(failure.getSuppressed().length == 3, "cleanup failures lost");
            check(view.ended.isCompletedExceptionally() && view.ready.isCompletedExceptionally(), "receipt stranded");
            """);
    }

    @Test public void parentLossCannotUndoAnAlreadyReportedAttachment() throws Exception {
        verify("""
            var view = new Fixture();
            view.ready.complete(null);
            view.fail(new IOException("Desktop closed"));
            check(!view.ready.isCompletedExceptionally() && view.ended.isCompletedExceptionally(), "lifetime conflated with admission");
            check(releases.size() == 3, "active child retained after Desktop close");
            """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify("""
            static final List<String> releases = new ArrayList<>();
            static int callbacksRemoved;
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
            boolean closed;
            static void checkThread() { }
            public static void verify() throws Exception {
            """ + body + "}\n" + RuntimeSourceFixture.methods("HostedDependentWindow", "fail", "close"));
    }
}
