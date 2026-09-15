package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayMirrorLeaseRegistryTest {
    @Test public void mirrorsShareSourcesWithoutTakingDirectSurfacesAndCleanupUsesBothEndpoints() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                interface IBinder { }
                static class Owner implements IBinder { }
                static class RemoteException extends Exception { }
                interface IDisplayViewer { }
                static class android {
                    static class view { static class Surface { } static class SurfaceControl { } }
                    static class util { static class Log { static void w(String t, String m, Throwable e) { } } }
                }
                interface DisplayPresentationSurface {
                    void attach(android.view.Surface surface, android.view.SurfaceControl parent);
                    void close();
                }
                static class Presentation implements DisplayPresentationSurface {
                    int attaches, closes;
                    public void attach(android.view.Surface s, android.view.SurfaceControl p) { attaches++; }
                    public void close() { closes++; }
                }
                static class Display {
                    int directAttaches, directDetaches, wraps;
                    void present(android.view.Surface s) { directAttaches++; }
                    void detachViewer() { directDetaches++; }
                    DisplayPresentationSurface presentation(DisplayPresentationSurface p) { wraps++; return p; }
                }
                static class Entry {
                    final IBinder owner;
                    final Display display = new Display();
                    Entry(IBinder owner) { this.owner = owner; }
                }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId, source = "virtual";
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    void requirePresentationOutput(DesktopDisplayInfo out) { }
                }
                static class ShellDisplayViewer implements IDisplayViewer {
                    final int source, outputDisplayId;
                    final boolean direct;
                    final DisplayPresentationSurface surface;
                    boolean closed;
                    ShellDisplayViewer(DisplayPresentationSurface p, int s, int o, boolean direct, IBinder owner)
                            throws RemoteException { surface = p; source = s; outputDisplayId = o; this.direct = direct; }
                    boolean isClosed() { return closed; }
                    int sourceDisplayId() { return source; }
                    void close() { if (!closed) { closed = true; surface.close(); } }
                }
                static class FrameworkRuntime {
                    static final List<Presentation> mirrors = new ArrayList<>();
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime displayMirror() throws ReflectiveOperationException { return this; }
                    DisplayPresentationSurface create(int id) {
                        var mirror = new Presentation(); mirrors.add(mirror); return mirror;
                    }
                }
                final Map<Integer, Entry> mDisplays = new LinkedHashMap<>();
                final List<ShellDisplayViewer> mViewers = new ArrayList<>();
                DesktopDisplayInfo[] list() {
                    return new DesktopDisplayInfo[]{new DesktopDisplayInfo(0), new DesktopDisplayInfo(1),
                            new DesktopDisplayInfo(2), new DesktopDisplayInfo(3)};
                }
                static void rejects(Runnable r) {
                    boolean rejected = false;
                    try { r.run(); } catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "invalid binding accepted");
                }
                ShellDisplayViewer open(int source, int output, boolean direct, IBinder owner) {
                    return (ShellDisplayViewer) openViewer(source, "display:" + source, output,
                            "display:" + output, direct, owner, new Owner());
                }
                public static void verify() {
                    Fixture f = new Fixture(); Owner owner = new Owner();
                    Entry first = new Entry(owner), second = new Entry(owner);
                    f.mDisplays.put(1, first); f.mDisplays.put(2, second);
                    var primary = f.open(1, 0, true, owner);
                    var mirror = f.open(1, 0, false, owner);
                    var another = f.open(1, 3, false, owner);
                    var peer = f.open(2, 0, false, owner);
                    primary.surface.attach(null, null); mirror.surface.attach(null, null);
                    check(first.display.directAttaches == 1 && FrameworkRuntime.mirrors.size() == 3,
                            "owned mirror replaced direct Surface instead of using mirror API");
                    rejects(() -> f.open(1, 3, true, owner));
                    rejects(() -> f.open(3, 0, true, owner));
                    rejects(() -> f.open(1, 0, false, new Owner()));
                    rejects(() -> f.open(0, 1, false, owner));
                    mirror.close();
                    check(!primary.closed && first.display.directDetaches == 0, "mirror closed primary output");
                    f.open(1, 0, false, owner);
                    f.closeViewer(3);
                    check(another.closed && !primary.closed && !peer.closed, "output cleanup closed unrelated leases");
                    f.closeViewer(1);
                    check(primary.closed && first.display.directDetaches == 1 && !peer.closed,
                            "source cleanup did not isolate its leases");
                    check(f.mViewers.size() == 1, "closed leases retained in live registry");
                }
                """ + RuntimeSourceFixture.methods("ShellVirtualDisplays", "openViewer", "closeViewer"),
                "DisplayPresentationGraph");
    }
}
