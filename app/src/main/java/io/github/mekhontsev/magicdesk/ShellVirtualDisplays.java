package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;

import java.util.LinkedHashMap;
import java.util.Map;

/** Owns display tokens independently of HOME, tasks, and desktop sessions. */
final class ShellVirtualDisplays implements AutoCloseable {
    private final Context mContext;
    private final Map<Integer, Entry> mDisplays = new LinkedHashMap<>();
    private final java.util.List<ShellDisplayViewer> mViewers = new java.util.ArrayList<>();

    ShellVirtualDisplays(final Context context) { mContext = context; }

    synchronized AutoCloseable keepAwake(int displayId, String uniqueId, IBinder owner) {
        final Entry entry = mDisplays.get(displayId);
        if (entry == null || !entry.owner.equals(owner)) {
            throw new IllegalArgumentException("display is not owned by this client");
        }
        boolean valid = false;
        for (DesktopDisplayInfo info : list()) {
            if (info.id == displayId && info.uniqueId.equals(uniqueId)) valid = true;
        }
        if (!valid) throw new IllegalArgumentException("display identity changed");
        return entry.display.keepAwake();
    }

    synchronized DesktopDisplayInfo create(final VirtualDisplaySpec spec, final IBinder owner) {
        if (owner == null) {
            throw new IllegalArgumentException("virtual display owner is required");
        }
        try {
            final FrameworkVirtualDisplayApi api = FrameworkRuntime.current().virtualDisplays();
            final FrameworkVirtualDisplayApi.OwnedDisplay display = api.create(ShellIdentityContext.create(mContext), spec);
            final int id = display.getDisplay().getDisplayId();
            final IBinder.DeathRecipient death = () -> releaseOwner(id, owner);
            boolean linked = false;
            try {
                owner.linkToDeath(death, 0);
                linked = true;
                final DesktopDisplayInfo info = api.describe(display.getDisplay(), true);
                mDisplays.put(id, new Entry(display, owner, death));
                return info;
            } catch (RemoteException | ReflectiveOperationException | RuntimeException error) {
                if (linked) { owner.unlinkToDeath(death, 0); }
                display.release();
                throw error;
            }
        } catch (RemoteException | ReflectiveOperationException
                | android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("cannot create virtual display: " + error, error);
        }
    }

    synchronized DesktopDisplayInfo[] list() {
        try {
            final DisplayManager manager = ShellIdentityContext.create(mContext)
                    .getSystemService(DisplayManager.class);
            if (manager == null) {
                throw new IllegalStateException("display service unavailable");
            }
            final Display[] displays = manager.getDisplays();
            final DesktopDisplayInfo[] result = new DesktopDisplayInfo[displays.length];
            final FrameworkVirtualDisplayApi api = FrameworkRuntime.current().virtualDisplays();
            for (int i = 0; i < displays.length; i++) {
                result[i] = api.describe(displays[i], mDisplays.containsKey(displays[i].getDisplayId()));
            }
            return result;
        } catch (ReflectiveOperationException
                | android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("cannot read display catalog: " + error, error);
        }
    }

    synchronized void remove(final int displayId, final String uniqueId, final IBinder owner) {
        final Entry entry = mDisplays.get(displayId);
        if (entry == null || !entry.owner.equals(owner)) {
            throw new IllegalArgumentException("display is not owned by this client");
        }
        try {
            if (!FrameworkRuntime.current().virtualDisplays()
                    .describe(entry.display.getDisplay(), true).uniqueId.equals(uniqueId)) {
                throw new IllegalArgumentException("display identity changed");
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot verify virtual display identity", error);
        }
        closeViewer(displayId);
        entry.display.release();
        mDisplays.remove(displayId);
        entry.owner.unlinkToDeath(entry.death, 0);
    }

    private synchronized void releaseOwner(final int id, final IBinder owner) {
        final Entry entry = mDisplays.get(id);
        if (entry != null && entry.owner.equals(owner)) {
            closeViewer(id);
            entry.display.release();
            mDisplays.remove(id);
        }
    }

    @Override public synchronized void close() {
        for (ShellDisplayViewer viewer : new java.util.ArrayList<>(mViewers)) closeViewer(viewer);
        mViewers.clear();
        for (final Entry entry : mDisplays.values()) {
            entry.display.release();
            entry.owner.unlinkToDeath(entry.death, 0);
        }
        mDisplays.clear();
    }

    synchronized IDisplayViewer openViewer(int sourceId, String sourceUniqueId,
            int outputId, String outputUniqueId, boolean direct, IBinder displayOwner, IBinder viewerOwner) {
        final Entry entry = mDisplays.get(sourceId);
        if (entry != null && !entry.owner.equals(displayOwner)) {
            throw new IllegalArgumentException("virtual source belongs to another client");
        }
        if (direct && entry == null) throw new IllegalArgumentException("direct output requires an owned virtual source");
        DesktopDisplayInfo source = null;
        DesktopDisplayInfo output = null;
        final DesktopDisplayInfo[] catalog = list();
        for (DesktopDisplayInfo display : catalog) {
            if (display.id == sourceId && display.uniqueId.equals(sourceUniqueId)) source = display;
            if (display.id == outputId && display.uniqueId.equals(outputUniqueId)) output = display;
        }
        if (source == null || output == null || sourceId == outputId || viewerOwner == null) {
            throw new IllegalArgumentException("invalid or disconnected viewer endpoint");
        }
        source.requirePresentationOutput(output);
        // Presentation edges are output -> source. A cycle would feed the
        // viewer's own window back into itself, even though all IDs are valid.
        final java.util.List<DisplayPresentationGraph.Edge> edges = new java.util.ArrayList<>();
        mViewers.removeIf(ShellDisplayViewer::isClosed);
        for (ShellDisplayViewer viewer : mViewers) {
            if (direct && viewer.direct && viewer.sourceDisplayId() == sourceId) {
                throw new IllegalStateException("source already has a direct output");
            }
            edges.add(new DisplayPresentationGraph.Edge(viewer.outputDisplayId, viewer.sourceDisplayId()));
        }
        edges.add(new DisplayPresentationGraph.Edge(outputId, sourceId));
        DisplayPresentationGraph.requireAcyclic(edges, java.util.Arrays.stream(catalog)
                .filter(d -> "overlay".equals(d.source)).map(d -> d.id).toList());
        try {
            final DisplayPresentationSurface presentation = !direct
                    ? FrameworkRuntime.current().displayMirror().create(sourceId)
                    : new DisplayPresentationSurface() {
                        @Override public void attach(android.view.Surface surface,
                                android.view.SurfaceControl parent) { entry.display.present(surface); }
                        @Override public void close() { entry.display.detachViewer(); }
                    };
            final ShellDisplayViewer viewer = new ShellDisplayViewer(
                    entry == null ? presentation : entry.display.presentation(presentation),
                    sourceId, outputId, direct, viewerOwner);
            mViewers.add(viewer);
            return viewer;
        } catch (RemoteException | ReflectiveOperationException error) {
            throw new IllegalStateException("could not open display presentation", error);
        }
    }

    private void closeViewer(int displayId) {
        for (ShellDisplayViewer viewer : new java.util.ArrayList<>(mViewers)) {
            if (viewer.sourceDisplayId() == displayId || viewer.outputDisplayId == displayId) closeViewer(viewer);
        }
        mViewers.removeIf(ShellDisplayViewer::isClosed);
    }

    private void closeViewer(ShellDisplayViewer viewer) {
        try { viewer.close(); }
        catch (RuntimeException error) { android.util.Log.w("MagicDeskDisplays", "Viewer cleanup failed", error); }
    }

    private static final class Entry {
        final FrameworkVirtualDisplayApi.OwnedDisplay display;
        final IBinder owner;
        final IBinder.DeathRecipient death;
        Entry(final FrameworkVirtualDisplayApi.OwnedDisplay display,
                final IBinder owner, final IBinder.DeathRecipient death) {
            this.display = display;
            this.owner = owner;
            this.death = death;
        }
    }
}
