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

    ShellVirtualDisplays(final Context context) { mContext = context; }

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
        entry.display.release();
        mDisplays.remove(displayId);
        entry.owner.unlinkToDeath(entry.death, 0);
    }

    private synchronized void releaseOwner(final int id, final IBinder owner) {
        final Entry entry = mDisplays.get(id);
        if (entry != null && entry.owner.equals(owner)) {
            entry.display.release();
            mDisplays.remove(id);
        }
    }

    @Override public synchronized void close() {
        for (final Entry entry : mDisplays.values()) {
            entry.display.release();
            entry.owner.unlinkToDeath(entry.death, 0);
        }
        mDisplays.clear();
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
