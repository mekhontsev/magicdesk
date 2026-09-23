package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedShellWindowsTest {
    @Test public void admissionUsesLatestGeometryAndEqualUpdatesDoNotRedraw() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        var window = fixture.windows.get(0);
        assertEquals(0, window.frames.size());
        var stale = fixture.owner.presented(1);
        fixture.owner.update(List.of(surface(1, 30)));
        assertTrue(stale.isCompletedExceptionally());
        window.ready.complete(null);
        assertEquals(List.of(new ShellBounds(30, 0, 94, 24)), window.bounds);
        fixture.owner.update(List.of(surface(1, 30)));
        assertEquals(1, window.frames.size());
        window.frames.get(0).complete(null);
        assertTrue(fixture.owner.presented(1).isDone());
    }

    @Test public void staleReceiptsCannotFailReplacementOrReviveRemovedWindow() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        var window = fixture.windows.get(0);
        window.ready.complete(null);
        fixture.owner.update(List.of(surface(1, 20)));
        window.frames.get(0).completeExceptionally(new IllegalStateException("obsolete"));
        assertTrue(fixture.errors.isEmpty());
        fixture.owner.update(List.of());
        window.frames.get(1).complete(null);
        assertTrue(fixture.errors.isEmpty());
        assertEquals(1, window.closes);
        assertTrue(fixture.owner.presented(1).isCompletedExceptionally());
        fixture.owner.update(List.of(surface(1, 20)));
        assertEquals(2, fixture.windows.size());
    }

    @Test public void hostLossClosesAllBorrowedWindowsAndFailsOnce() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0), surface(2, 80)));
        fixture.windows.get(0).ended.complete(null);
        assertEquals(1, fixture.errors.size());
        assertEquals(List.of(1, 1), fixture.windows.stream().map(window -> window.closes).toList());
        fixture.windows.get(1).ready.completeExceptionally(new IllegalStateException("late"));
        fixture.owner.update(List.of(surface(3, 0)));
        assertEquals(2, fixture.windows.size());
        assertEquals(1, fixture.errors.size());
    }

    @Test public void policyConcealmentRetainsCatalogButClosesInputAndReborrowsOnReveal() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        fixture.presentation.update(Set.of());
        fixture.windows.get(0).ready.complete(null);
        assertTrue(fixture.windows.get(0).frames.isEmpty());
        fixture.owner.update(List.of(surface(1, 45)));
        assertEquals(1, fixture.windows.size());
        fixture.presentation.update(Set.of(ShellSurface.Layer.values()));
        fixture.windows.get(1).ready.complete(null);
        assertEquals(new ShellBounds(45, 0, 109, 24), fixture.windows.get(1).bounds.get(0));
        fixture.owner.close();
        fixture.presentation.update(Set.of(ShellSurface.Layer.values()));
        assertEquals(2, fixture.windows.size());
        assertTrue(fixture.errors.isEmpty());
    }

    @Test public void unpaintedSurfaceIsValidatedButDoesNotBorrowAnOutput() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(new HostedShellWindows.Surface(1, ShellSurface.Layer.TOP,
                ShellSurface.Keyboard.NONE, null, null)));
        assertTrue(fixture.windows.isEmpty());
        fixture.owner.update(List.of(new HostedShellWindows.Surface(1, ShellSurface.Layer.TOP,
                ShellSurface.Keyboard.EXCLUSIVE, null, null)));
        assertEquals(1, fixture.errors.size());
    }

    @Test public void layerPolicyPreservesHomeLeaseAndRevealsLatestTopGeometry() {
        var fixture = new Fixture();
        var top = surface(1, 0);
        var home = new HostedShellWindows.Surface(2, ShellSurface.Layer.BOTTOM,
                top.keyboard(), top.bounds(), top.frame());
        fixture.owner.update(List.of(top, home));
        fixture.presentation.update(Set.of(ShellSurface.Layer.BOTTOM));
        assertEquals(1, fixture.windows.get(0).closes);
        assertEquals(0, fixture.windows.get(1).closes);
        fixture.owner.update(List.of(surface(1, 45), home));
        fixture.presentation.update(Set.of(ShellSurface.Layer.BOTTOM, ShellSurface.Layer.TOP));
        assertEquals(3, fixture.windows.size());
        fixture.windows.get(2).ready.complete(null);
        assertEquals(45, fixture.windows.get(2).bounds.get(0).left());
        assertEquals(0, fixture.windows.get(1).closes);
        assertTrue(fixture.errors.isEmpty());
    }

    @Test public void scopeLossRevokesHiddenContributionsAndLateEventsCannotReopenThem() {
        var fixture = new Fixture();
        fixture.presentation.update(Set.of());
        fixture.owner.update(List.of(surface(1, 0)));
        fixture.presentation.close();
        fixture.presentation.close();
        fixture.owner.update(List.of(surface(2, 0)));
        assertEquals(1, fixture.errors.size());
        assertTrue(fixture.windows.isEmpty());
    }

    @Test public void unsupportedRolesAreRejectedEvenWhileConcealed() {
        var fixture = new Fixture();
        fixture.presentation.update(Set.of());
        fixture.owner.update(List.of(new HostedShellWindows.Surface(1, ShellSurface.Layer.TOP,
                ShellSurface.Keyboard.ON_DEMAND, null, null)));
        assertEquals(1, fixture.errors.size());
        fixture.presentation.update(Set.of(ShellSurface.Layer.values()));
        assertTrue(fixture.windows.isEmpty());
    }

    @Test public void receiptCallbacksCanReplaceVisibilityWithoutResurrectingStaleCatalog() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        fixture.owner.presented(1).whenComplete((ignored, error) -> {
            fixture.presentation.update(Set.of(ShellSurface.Layer.TOP));
            fixture.owner.update(List.of(surface(2, 80)));
        });
        fixture.presentation.update(Set.of());
        assertEquals(2, fixture.windows.size());
        fixture.windows.get(1).ready.complete(null);
        assertEquals(80, fixture.windows.get(1).bounds.get(0).left());
        assertTrue(fixture.owner.presented(1).isCompletedExceptionally());
        assertTrue(fixture.errors.isEmpty());
    }

    @Test public void rejectsDuplicateCatalogBeforeOpeningAnyWindows() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0), surface(1, 0)));
        assertTrue(fixture.windows.isEmpty());
        assertEquals(1, fixture.errors.size());
    }

    @Test public void cancellationCanCloseTheScopeDuringGeometryOrRoleReplacement() {
        for (boolean role : new boolean[] {false, true}) {
            var fixture = new Fixture();
            var first = surface(1, 0);
            fixture.owner.update(List.of(first));
            fixture.windows.get(0).ready.complete(null);
            fixture.owner.presented(1).whenComplete((ignored, error) -> fixture.presentation.close());
            fixture.owner.update(List.of(role ? new HostedShellWindows.Surface(first.id(), ShellSurface.Layer.BOTTOM,
                    first.keyboard(), first.bounds(), first.frame()) : surface(1, 25)));
            assertEquals(1, fixture.windows.size());
            assertEquals(1, fixture.windows.get(0).frames.size());
            assertEquals(1, fixture.windows.get(0).closes);
            assertEquals(1, fixture.errors.size());
        }
    }

    @Test public void scopeClosureDuringConcealmentReleasesEachBorrowOnce() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0), surface(2, 80)));
        fixture.owner.presented(1).whenComplete((ignored, error) -> fixture.presentation.close());
        fixture.presentation.update(Set.of());
        assertEquals(List.of(1, 1), fixture.windows.stream().map(window -> window.closes).toList());
        assertEquals(1, fixture.errors.size());
    }

    @Test public void geometryReplacementCanChangePolicyWithoutLosingTheLatestReceipt() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        fixture.windows.get(0).ready.complete(null);
        fixture.owner.presented(1).whenComplete((ignored, error) ->
                fixture.presentation.update(Set.of(ShellSurface.Layer.TOP)));
        fixture.owner.update(List.of(surface(1, 25)));
        assertEquals(2, fixture.windows.get(0).frames.size());
        assertEquals(25, fixture.windows.get(0).bounds.get(1).left());
        fixture.windows.get(0).frames.get(1).complete(null);
        assertTrue(fixture.owner.presented(1).isDone());
        assertFalse(fixture.owner.presented(1).isCompletedExceptionally());
    }

    @Test public void presentationFailureReleasesTheWholeContribution() {
        var fixture = new Fixture();
        fixture.owner.update(List.of(surface(1, 0)));
        var window = fixture.windows.get(0);
        window.ready.complete(null);
        var receipt = fixture.owner.presented(1);
        window.frames.get(0).completeExceptionally(new IllegalStateException("pixels unavailable"));
        assertTrue(receipt.isCompletedExceptionally());
        assertEquals(1, window.closes);
        assertEquals(1, fixture.errors.size());
    }

    @Test public void acceptedRoleChangeBorrowsTheCorrectHostRatherThanRelayoutTheOldLayer() {
        var fixture = new Fixture();
        var top = surface(1, 0);
        fixture.owner.update(List.of(top));
        fixture.windows.get(0).ready.complete(null);
        fixture.owner.update(List.of(new HostedShellWindows.Surface(top.id(), ShellSurface.Layer.BOTTOM,
                top.keyboard(), top.bounds(), top.frame())));
        assertEquals(1, fixture.windows.get(0).closes);
        assertEquals(2, fixture.windows.size());
        fixture.windows.get(0).frames.get(0).completeExceptionally(new IllegalStateException("old host"));
        assertTrue(fixture.errors.isEmpty());
    }

    private static HostedShellWindows.Surface surface(long id, int left) {
        var viewport = new ShellBounds(0, 0, 64, 24);
        return new HostedShellWindows.Surface(id, ShellSurface.Layer.TOP, ShellSurface.Keyboard.NONE,
                new ShellBounds(left, 0, left + 64, 24), new HostedShellFrame(viewport, true, List.of(viewport)));
    }

    private static final class Fixture implements HostedShellWindows.Host {
        final List<Window> windows = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final ShellPresentationScope presentation = new ShellPresentationScope();
        final HostedShellWindows owner = new HostedShellWindows(this, presentation, Runnable::run, errors::add);
        public void validate(HostedShellWindows.Surface surface) {
            if (surface.keyboard() != ShellSurface.Keyboard.NONE) throw new IllegalArgumentException("keyboard policy");
        }
        public Window open(HostedShellWindows.Surface surface) {
            var window = new Window(); windows.add(window); return window;
        }
    }

    private static final class Window implements HostedShellWindows.Window {
        final CompletableFuture<Void> ready = new CompletableFuture<>(), ended = new CompletableFuture<>();
        final List<CompletableFuture<Void>> frames = new ArrayList<>();
        final List<ShellBounds> bounds = new ArrayList<>();
        int closes;
        public CompletableFuture<Void> ready() { return ready; }
        public CompletableFuture<Void> ended() { return ended; }
        public CompletableFuture<Void> present(ShellBounds next, HostedShellFrame frame) {
            bounds.add(next); var receipt = new CompletableFuture<Void>(); frames.add(receipt); return receipt;
        }
        public void close() { closes++; ended.complete(null); }
    }
}
