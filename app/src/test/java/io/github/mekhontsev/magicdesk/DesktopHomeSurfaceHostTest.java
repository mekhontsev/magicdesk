package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class DesktopHomeSurfaceHostTest {
    @Test public void rolesRouteToHomeLayersWithoutGrantingKeyboardOrStartingAnything() throws Exception {
        verify("""
            var background = new FrameLayout(); var bottom = new FrameLayout();
            var owner = new DesktopHomeSurfaceHost(background, bottom);
            var out = new HostedShellOutput(); var host = owner.host(state -> out);
            var first = host.open(state(ShellSurface.Layer.BACKGROUND, ShellSurface.Keyboard.NONE));
            var second = host.open(state(ShellSurface.Layer.BOTTOM, ShellSurface.Keyboard.NONE));
            check(background.views.size()==1 && bottom.views.size()==1, "layer routing");
            for (var layer : List.of(ShellSurface.Layer.TOP, ShellSurface.Layer.OVERLAY)) {
                try { host.validate(state(layer, ShellSurface.Keyboard.NONE)); throw new AssertionError("accepted role"); }
                catch (UnsupportedOperationException expected) {}
            }
            try { host.validate(state(ShellSurface.Layer.BOTTOM, ShellSurface.Keyboard.ON_DEMAND));
                throw new AssertionError("accepted keyboard"); } catch (UnsupportedOperationException expected) {}
            owner.close();
            check(first.ended().isDone() && second.ended().isDone(), "host loss not published");
            check(background.views.isEmpty() && bottom.views.isEmpty(), "borrowed views retained");
            """);
    }

    @Test public void geometryUsesDisplayCoordinatesAndRevokesInputBeforeMoving() throws Exception {
        verify("""
            var background = new FrameLayout(); background.x=5; background.y=9;
            var owner = new DesktopHomeSurfaceHost(background, new FrameLayout());
            var host = owner.host(state -> new HostedShellOutput());
            var window = host.open(state(ShellSurface.Layer.BACKGROUND, ShellSurface.Keyboard.NONE));
            var view = background.views.get(0);
            check(view.params.leftMargin==5 && view.params.topMargin==11, "origin ignored");
            order.clear();
            var receipt = window.present(new ShellBounds(-10,30,90,70), new HostedShellFrame());
            check(order.equals(List.of("revoke","place")), "input retained during move");
            check(view.params.leftMargin == -15 && view.params.topMargin == 21 && view.params.width == 100,
                "negative paint origin changed");
            window.close(); window.close();
            check(receipt.isCompletedExceptionally() && view.output.closes==1, "lease cleanup not exact");
            """);
    }

    @Test public void attachmentFailureAndRendererLossCannotLeakOutputsOrReviveTheHost() throws Exception {
        verify("""
            var parent = new FrameLayout(); var owner = new DesktopHomeSurfaceHost(parent, new FrameLayout());
            var output = new HostedShellOutput(); var host = owner.host(state -> output);
            parent.fail=true;
            try { host.open(state(ShellSurface.Layer.BACKGROUND, ShellSurface.Keyboard.NONE));
                throw new AssertionError("attach succeeded"); } catch (IllegalStateException expected) {}
            check(output.closes==1 && owner.windows.isEmpty(), "failed attachment leaked");
            parent.fail=false;
            var window = host.open(state(ShellSurface.Layer.BACKGROUND, ShellSurface.Keyboard.NONE));
            parent.views.get(0).unavailable.accept(new IOException("renderer"));
            check(window.ended().isCompletedExceptionally() && parent.views.isEmpty(), "renderer loss retained lease");
            owner.close();
            try { host.open(state(ShellSurface.Layer.BOTTOM, ShellSurface.Keyboard.NONE));
                throw new AssertionError("closed host revived"); } catch (IllegalStateException expected) {}
            """);
    }

    private static void verify(String body) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "DesktopHomeSurfaceHost.java"))
                .replace("Function<", "java.util.function.Function<");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                "static " + source.substring(source.indexOf("final class DesktopHomeSurfaceHost")) + STUBS
                + "public static void verify() throws Exception { " + body + " }", "ShellBounds");
    }

    private static final String STUBS = """
        static final List<String> order = new ArrayList<>();
        static class Looper { static Object myLooper() { return null; } static Object getMainLooper() { return null; } }
        static class Gravity { static final int TOP=1, LEFT=2; }
        static class ShellSurface {
            enum Layer { BACKGROUND, BOTTOM, TOP, OVERLAY } enum Keyboard { NONE, ON_DEMAND }
        }
        static class HostedShellFrame { }
        static class HostedShellOutput { int closes; void close() { closes++; } }
        static class HostedShellWindows {
            record Surface(ShellSurface.Layer layer, ShellSurface.Keyboard keyboard, ShellBounds bounds) {}
            interface Host { void validate(Surface state); Window open(Surface state); }
            interface Window {
                CompletableFuture<Void> ready(); CompletableFuture<Void> ended();
                CompletableFuture<Void> present(ShellBounds bounds, HostedShellFrame frame); void close();
            }
        }
        static HostedShellWindows.Surface state(ShellSurface.Layer layer, ShellSurface.Keyboard keyboard) {
            return new HostedShellWindows.Surface(layer, keyboard, new ShellBounds(10,20,90,60));
        }
        static class FrameLayout {
            int x,y; boolean fail;
            final List<HostedShellTextureView> views = new ArrayList<>();
            static class LayoutParams {
                int width,height,leftMargin,topMargin;
                LayoutParams(int w,int h,int gravity) { width=w; height=h; }
            }
            boolean isAttachedToWindow() { return true; }
            void getLocationOnScreen(int[] origin) { origin[0]=x; origin[1]=y; }
            Object getContext() { return null; }
            void addView(HostedShellTextureView view) {
                if (fail) throw new IllegalStateException("attach"); views.add(view);
            }
            void removeView(HostedShellTextureView view) { if (views.remove(view)) view.unavailable.accept(null); }
        }
        static class HostedShellTextureView {
            final HostedShellOutput output; final java.util.function.Consumer<Throwable> unavailable;
            FrameLayout.LayoutParams params; CompletableFuture<Void> pending; boolean closed;
            HostedShellTextureView(Object context, HostedShellOutput out, java.util.function.Consumer<Throwable> gone) {
                output=out; unavailable=gone;
            }
            CompletableFuture<Void> present(HostedShellFrame frame) {
                order.add("revoke"); pending = new CompletableFuture<>(); return pending;
            }
            void setLayoutParams(FrameLayout.LayoutParams value) { order.add("place"); params=value; }
            void close() {
                if (closed) return; closed=true; output.close();
                if (pending!=null) pending.completeExceptionally(new IOException("closed"));
            }
        }
        """;
}
