package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;
import org.junit.Test;

public final class WaylandShellPublicationTest {
    @Test public void queuedLifecycleSnapshotsAreObservedInOrderAndRevocationDropsPendingEvents() throws Exception {
        var source = Path.of("../wayland-runtime/src/main/java/io/github/mekhontsev/magicdesk/wayland/WaylandSession.java")
                .toAbsolutePath().toString();
        RuntimeSourceFixture.verify("""
                final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                final Queue<Runnable> queue = new ArrayDeque<>();
                final Main main = new Main();
                class Main { void post(Runnable action) { queue.add(action); } }
                record WaylandShellSurface(boolean mapped) { }
                static class Listener { Runnable action; void changed() { action.run(); } }
                static class ShellBinding {
                    final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
                    List<WaylandShellSurface> surfaces = List.of();
                    final Listener listener = new Listener();
                }
                public static void verify() {
                    var fixture = new Fixture();
                    var binding = new ShellBinding();
                    var observed = new ArrayList<Boolean>();
                    binding.listener.action = () -> observed.add(binding.surfaces.get(0).mapped());
                    fixture.publishShellSurface(binding, List.of(new WaylandShellSurface(true)));
                    fixture.publishShellSurface(binding, List.of(new WaylandShellSurface(false)));
                    fixture.publishShellSurface(binding, List.of(new WaylandShellSurface(true)));
                    check(binding.surfaces.isEmpty(), "Worker published outside the event boundary");
                    while (!fixture.queue.isEmpty()) fixture.queue.remove().run();
                    check(observed.equals(List.of(true, false, true)), "Lost mapping lifecycle: " + observed);
                    fixture.publishShellSurface(binding, List.of(new WaylandShellSurface(false)));
                    binding.released.set(true);
                    fixture.queue.remove().run();
                    check(observed.size() == 3, "Revoked owner delivered a queued snapshot");
                    binding.released.set(false);
                    fixture.closed.set(true);
                    fixture.publishShellSurface(binding, List.of(new WaylandShellSurface(false)));
                    fixture.queue.remove().run();
                    check(observed.size() == 3, "Closed session delivered a queued snapshot");
                }
                """ + RuntimeSourceFixture.methods(source, "publishShellSurface"));
    }
}
