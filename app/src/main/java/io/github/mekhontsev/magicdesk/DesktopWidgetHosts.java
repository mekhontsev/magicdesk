package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** One live host owner per persistent workspace, using only public widget APIs. */
final class DesktopWidgetHosts {
    private static final Map<String, Lease> ACTIVE = new HashMap<>();

    private DesktopWidgetHosts() { }

    static Lease acquire(DesktopShellActivity activity, Runnable changed) throws IOException {
        requireMainThread();
        final String workspace = DesktopWidgetHostIds.workspaceKey(activity.appProfile().serialNumber,
                ExternalDisplayController.getDisplayUniqueId(activity.getCurrentDisplayId()));
        final Context app = activity.getApplicationContext();
        final int hostId = new DesktopWidgetHostIds(app.getSharedPreferences(
                "desktop_widget_hosts", Context.MODE_PRIVATE)).getOrAllocate(workspace);
        return acquire(workspace, new Host(activity, hostId), changed);
    }

    private static Lease acquire(String workspace, Host host, Runnable changed) {
        final Lease previous = ACTIVE.get(workspace);
        // Detach the old callback before replacing it, never after the new host starts.
        if (previous != null) previous.release();
        final Lease lease = new Lease(workspace, host);
        ACTIVE.put(workspace, lease);
        host.changed = () -> {
            if (lease.isCurrent()) changed.run();
        };
        return lease;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("widget hosts belong to the UI thread");
        }
    }

    static final class Lease {
        final String workspace;
        final Host host;
        private boolean listening;

        Lease(String workspace, Host host) {
            this.workspace = workspace;
            this.host = host;
        }

        boolean isCurrent() { return ACTIVE.get(workspace) == this; }

        void start() {
            requireMainThread();
            if (!isCurrent() || listening) return;
            host.startListening();
            listening = true;
        }

        void stop() {
            requireMainThread();
            if (!isCurrent() || !listening) return;
            host.stopListening();
            listening = false;
        }

        boolean owns(int widgetId) {
            if (!isCurrent() || widgetId < 0) return false;
            final int[] ids = host.getAppWidgetIds();
            if (ids == null) return false;
            for (final int id : ids) {
                if (id == widgetId) return true;
            }
            return false;
        }

        void release() {
            requireMainThread();
            if (!isCurrent()) return;
            try {
                stop();
            } finally {
                host.changed = null;
                host.releaseViews();
                ACTIVE.remove(workspace, this);
            }
        }
    }

    static final class Host extends AppWidgetHost {
        private Runnable changed;

        Host(Context context, int hostId) { super(context, hostId); }

        void releaseViews() { clearViews(); }

        @Override public void onAppWidgetRemoved(int appWidgetId) {
            if (changed != null) changed.run();
        }

        @Override protected void onProvidersChanged() {
            if (changed != null) changed.run();
        }

        @Override protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                AppWidgetProviderInfo info) {
            return new WidgetView(context);
        }
    }

    private static final class WidgetView extends AppWidgetHostView {
        WidgetView(Context context) { super(context); }

        @Override protected void prepareView(View view) {
            super.prepareView(view);
            final AppWidgetProviderInfo info = getAppWidgetInfo();
            if (info == null) return;
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
                    && params.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
                    && params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            view.setLayoutParams(params);
        }
    }
}
