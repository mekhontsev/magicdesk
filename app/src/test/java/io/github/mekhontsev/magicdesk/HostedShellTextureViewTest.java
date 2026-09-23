package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class HostedShellTextureViewTest {
    @Test public void inputJoinsMatchingPixelsAndTheHomeFrameWithoutClaimingKeyboard() throws Exception {
        verify("""
            var output = new HostedShellOutput();
            var view = new HostedShellTextureView(new Context(), output, error -> {});
            view.onSurfaceTextureAvailable(new SurfaceTexture(), 80, 40);
            var receipt = view.present(frame());
            view.beforeDraw();
            check(!receipt.isDone() && !view.onTouchEvent(event(0, 10)), "input before pixels");
            output.submissions.get(0).complete(null); drain();
            check(!receipt.isDone() && view.tree.commits.size() == 1, "input before HOME commit");
            view.tree.commit(); drain();
            check(receipt.isDone() && view.onTouchEvent(event(0, 10)), "ready input missing");
            check(!view.onTouchEvent(event(0, 30)), "input hole swallowed");
            check(!view.focusable && !view.opaque, "keyboard or alpha policy changed");
            """);
    }

    @Test public void replacementAndSurfaceLossInvalidateOldReceipts() throws Exception {
        verify("""
            var output = new HostedShellOutput();
            var view = new HostedShellTextureView(new Context(), output, error -> {});
            view.onSurfaceTextureAvailable(new SurfaceTexture(), 80, 40);
            var first = view.present(frame()); view.beforeDraw();
            output.submissions.get(0).complete(null); drain();
            var next = view.present(frame()); view.beforeDraw();
            view.tree.commit(); drain();
            check(first.isCompletedExceptionally() && !next.isDone(), "old HOME receipt admitted replacement");
            output.submissions.get(1).complete(null); drain();
            view.onSurfaceTextureDestroyed(new SurfaceTexture());
            view.tree.commit(); drain();
            check(!next.isDone() && !view.admission.ready() && output.detaches == 1, "lost Surface retained input");
            view.onSurfaceTextureAvailable(new SurfaceTexture(), 80, 40); view.beforeDraw();
            output.submissions.get(2).complete(null); drain(); view.tree.commit(); drain();
            check(next.isDone() && view.admission.ready(), "Surface reattachment did not present");
            """);
    }

    @Test public void gestureEndsOutsideTheRegionButNewContactsAndHoverRespectHoles() throws Exception {
        verify("""
            var view = new HostedShellTextureView(new Context(), new HostedShellOutput(), error -> {});
            long g = view.admission.begin(); view.admission.pixels(g); view.admission.layout(g); view.admission.region(g);
            view.input = frame().inputPixels(80,40);
            check(view.onTouchEvent(event(0,10)), "contact rejected");
            check(view.onTouchEvent(event(1,30)), "release in hole dropped");
            check(!view.onGenericMotionEvent(event(7,30)), "hover captured a hole");
            check(view.onGenericMotionEvent(event(10,30)), "hover exit lost");
            check(!view.onGenericMotionEvent(event(11,10)), "generic button stole native sibling contact");
            check(view.onTouchEvent(event(0,10)), "next contact rejected");
            check(view.onGenericMotionEvent(event(12,30)), "button release outside lost");
            view.invalidatePresentation();
            check(!view.onTouchEvent(event(1,10)), "revoked input was delivered");
            """);
    }

    @Test public void failureAndCloseReleaseBorrowedOutputOnceAndRejectLatePixels() throws Exception {
        verify("""
            var output = new HostedShellOutput(); int[] detached = {0};
            var view = new HostedShellTextureView(new Context(), output, error -> detached[0]++);
            view.onSurfaceTextureAvailable(new SurfaceTexture(), 80, 40);
            var receipt = view.present(frame()); view.beforeDraw();
            output.submissions.get(0).completeExceptionally(new IOException("renderer")); drain();
            check(receipt.isCompletedExceptionally() && !view.admission.ready(), "failure admitted input");
            var replacement = view.present(frame()); view.beforeDraw();
            view.close(); view.onDetachedFromWindow();
            output.submissions.get(1).complete(null); drain(); view.tree.commit(); drain();
            check(replacement.isCompletedExceptionally() && output.closes == 1 && detached[0] == 2,
                "close leaked or revived output");
            check(view.surface == null && view.tree.commits.isEmpty(), "stale pixels scheduled HOME frame");
            """);
    }

    private static void verify(String body) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "HostedShellTextureView.java"));
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                "static " + source.substring(source.indexOf("final class HostedShellTextureView")) + STUBS
                + "public static void verify() throws Exception { " + body + " }",
                "HostedShellFrame", "ShellBounds", "HostedViewport", "ShellFrameAdmission");
    }

    private static final String STUBS = """
        static class Context { }
        static class SurfaceTexture { }
        static class Surface {
            Surface(SurfaceTexture texture) { }
            boolean isValid() { return true; }
            void release() { }
        }
        static class Looper { static Object getMainLooper() { return null; } }
        static final ArrayDeque<Runnable> events = new ArrayDeque<>();
        static class Handler { Handler(Object looper) { } void post(Runnable task) { events.add(task); } }
        static void drain() { while (!events.isEmpty()) events.remove().run(); }
        static class ViewTreeObserver {
            interface OnPreDrawListener { boolean onPreDraw(); }
            final List<Runnable> commits = new ArrayList<>();
            void addOnPreDrawListener(OnPreDrawListener listener) { }
            void removeOnPreDrawListener(OnPreDrawListener listener) { }
            void registerFrameCommitCallback(Runnable callback) { commits.add(callback); }
            void commit() { var batch = List.copyOf(commits); commits.clear(); batch.forEach(Runnable::run); }
        }
        static class TextureView {
            boolean opaque = true, focusable = true;
            final ViewTreeObserver tree = new ViewTreeObserver();
            TextureView(Context context) { }
            interface SurfaceTextureListener {
                void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h);
                void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h);
                boolean onSurfaceTextureDestroyed(SurfaceTexture texture);
                void onSurfaceTextureUpdated(SurfaceTexture texture);
            }
            void setSurfaceTextureListener(SurfaceTextureListener listener) { }
            void setOpaque(boolean value) { opaque = value; }
            void setFocusable(boolean value) { focusable = value; }
            protected void onAttachedToWindow() { }
            protected void onDetachedFromWindow() { }
            public boolean onTouchEvent(MotionEvent event) { return false; }
            public boolean onGenericMotionEvent(MotionEvent event) { return false; }
            public boolean performClick() { return false; }
            public void onWindowFocusChanged(boolean value) { }
            boolean isHardwareAccelerated() { return true; }
            int getWidth() { return 80; } int getHeight() { return 40; }
            void invalidate() { }
            ViewTreeObserver getViewTreeObserver() { return tree; }
        }
        static class MotionEvent {
            static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_CANCEL=3, ACTION_HOVER_EXIT=10,
                ACTION_BUTTON_PRESS=11, ACTION_BUTTON_RELEASE=12;
            int action; float x;
            int getActionMasked() { return action; } float getX() { return x; } float getY() { return 10; }
        }
        static MotionEvent event(int action, float x) { var e = new MotionEvent(); e.action=action; e.x=x; return e; }
        static class HostedPointerInput {
            boolean dragging;
            HostedPointerInput(TextureView view) { }
            void bind(HostedShellOutput output) { }
            void release() { dragging=false; }
            void viewport(HostedViewport viewport) { }
            boolean event(MotionEvent event) { return true; }
            boolean dragging() { return dragging; }
        }
        static class HostedShellOutput {
            int closes, detaches;
            final List<CompletableFuture<Void>> submissions = new ArrayList<>();
            CompletableFuture<Void> present(Surface surface, ShellBounds viewport) {
                var result = new CompletableFuture<Void>(); submissions.add(result); return result;
            }
            void setSurface(Surface surface, int w, int h) { detaches++; }
            void close() { closes++; }
        }
        static HostedShellFrame frame() { return new HostedShellFrame(new ShellBounds(0,0,80,40), true,
            List.of(new ShellBounds(0,0,16,24), new ShellBounds(48,0,64,24))); }
        """;
}
