package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DesktopWidgetController {
    static final int REQUEST_BIND = 1101;
    static final int REQUEST_CONFIGURE = 1102;
    private static final String TAG = "MagicDeskWidgets";
    private static final String STATE_PENDING_ID = "desktop_widget_pending_id";
    private static final String STATE_PENDING_NEW = "desktop_widget_pending_new";

    private final DesktopShellActivity mActivity;
    private final AppWidgetManager mManager;
    private DesktopWidgetHosts.Lease mLease;
    private final DesktopWidgetPickerController mPicker;
    private final Runnable mChanged;
    private final Map<Integer, AppWidgetHostView> mViews = new HashMap<>();
    private int mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean mPendingNewWidget;

    DesktopWidgetController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui,
            final Runnable changed) {
        mActivity = activity;
        mManager = AppWidgetManager.getInstance(activity);
        mPicker = new DesktopWidgetPickerController(activity, ui);
        mChanged = changed;
    }

    void saveInstanceState(final Bundle outState) {
        outState.putInt(STATE_PENDING_ID, mPendingWidgetId);
        outState.putBoolean(STATE_PENDING_NEW, mPendingNewWidget);
    }

    void restoreInstanceState(final Bundle state) {
        if (state == null) {
            return;
        }
        mPendingWidgetId = state.getInt(
                STATE_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        mPendingNewWidget = mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && state.getBoolean(STATE_PENDING_NEW, false);
    }

    void start() {
        if (!ensureHost()) return;
        try {
            mLease.start();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot start widget host", error);
            CompatibilityDiagnostics.record(
                    "WIDGETS-001",
                    "Android widgets are unavailable",
                    "AppWidgetHost.startListening failed",
                    error);
        }
    }

    void stop() {
        if (mLease == null) return;
        try {
            mLease.stop();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot stop widget host", error);
        }
    }

    void release() {
        if (mActivity.isFinishing() && mPendingNewWidget) {
            deleteWidgetId(mPendingWidgetId);
            clearPendingWidget();
        }
        if (mLease != null) {
            try { mLease.release(); }
            catch (RuntimeException error) { Log.w(TAG, "Cannot release widget host", error); }
        }
        mViews.clear();
    }

    private boolean ensureHost() {
        if (mLease != null) return mLease.isCurrent();
        try {
            mLease = DesktopWidgetHosts.acquire(mActivity, mChanged);
            return true;
        } catch (IOException | RuntimeException error) {
            reportUnavailable("Cannot acquire workspace widget host", error);
            return false;
        }
    }

    boolean owns(final int appWidgetId) {
        return ensureHost() && mLease.owns(appWidgetId);
    }

    List<WidgetEntry> widgets() {
        if (!ensureHost()) return Collections.emptyList();
        final int[] ids = mLease.host.getAppWidgetIds();
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        final List<WidgetEntry> widgets = new ArrayList<>();
        for (final int appWidgetId : ids) {
            if (appWidgetId == mPendingWidgetId && mPendingNewWidget) continue;
            final AppWidgetProviderInfo info =
                    mManager.getAppWidgetInfo(appWidgetId);
            if (info != null) {
                widgets.add(new WidgetEntry(appWidgetId, info));
            } else if (appWidgetId != mPendingWidgetId) {
                deleteWidgetId(appWidgetId);
            }
        }
        return widgets;
    }

    AppWidgetHostView createView(final WidgetEntry widget) {
        if (!owns(widget.appWidgetId)) throw new IllegalArgumentException("widget belongs to another workspace");
        AppWidgetHostView view = mViews.get(
                Integer.valueOf(widget.appWidgetId));
        if (view == null) {
            view = mLease.host.createView(
                    mActivity, widget.appWidgetId, widget.info);
            mViews.put(Integer.valueOf(widget.appWidgetId), view);
        }
        final ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
        view.setAppWidget(widget.appWidgetId, widget.info);
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    void addWidget() {
        if (mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        if (!ensureHost()) return;
        mActivity.hideAllPanels();
        mPicker.show(mManager.getInstalledProviders(), this::bindWidget);
    }

    boolean hasWidgets(final String packageName) {
        return !providersForPackage(packageName).isEmpty();
    }

    void addWidgets(final String packageName) {
        if (mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        if (!ensureHost()) return;
        final List<AppWidgetProviderInfo> providers =
                providersForPackage(packageName);
        if (providers.isEmpty()) {
            return;
        }
        mActivity.hideAllPanels();
        mPicker.show(providers, this::bindWidget);
    }

    private List<AppWidgetProviderInfo> providersForPackage(
            final String packageName) {
        if (!PackageNameValidator.isSafe(packageName)) {
            return Collections.emptyList();
        }
        final List<AppWidgetProviderInfo> installed;
        try {
            installed = mManager.getInstalledProvidersForPackage(
                    packageName, Process.myUserHandle());
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot list widgets for " + packageName, error);
            return Collections.emptyList();
        }
        if (installed == null || installed.isEmpty()) {
            return Collections.emptyList();
        }
        final List<AppWidgetProviderInfo> visible = new ArrayList<>();
        for (final AppWidgetProviderInfo info : installed) {
            if (info != null
                    && info.provider != null
                    && (info.widgetFeatures
                            & AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER)
                            == 0) {
                visible.add(info);
            }
        }
        return visible;
    }

    private void bindWidget(final AppWidgetProviderInfo info) {
        if (mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        if (!ensureHost()) return;
        final int appWidgetId;
        try {
            appWidgetId = mLease.host.allocateAppWidgetId();
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                throw new IllegalStateException("Android widget service is unavailable");
            }
        } catch (RuntimeException error) {
            reportUnavailable("Cannot allocate widget ID", error);
            return;
        }
        mPendingWidgetId = appWidgetId;
        mPendingNewWidget = true;
        try {
            if (mManager.bindAppWidgetIdIfAllowed(
                    appWidgetId,
                    info.getProfile(),
                    info.provider,
                    null)) {
                finishInitialBinding(appWidgetId);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Direct widget binding was denied", error);
        }
        final Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                        info.getProfile());
        try {
            mActivity.startActivityForResult(intent, REQUEST_BIND);
        } catch (RuntimeException error) {
            deleteWidgetId(appWidgetId);
            mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
            mPendingNewWidget = false;
            reportUnavailable("The widget could not be bound", error);
        }
    }

    boolean handleActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        if (requestCode != REQUEST_BIND
                && requestCode != REQUEST_CONFIGURE) {
            return false;
        }
        final int appWidgetId = resolveResultId(data);
        if (!owns(mPendingWidgetId)) {
            clearPendingWidget();
            return true;
        }
        if (appWidgetId != mPendingWidgetId) {
            return true;
        }
        if (resultCode != Activity.RESULT_OK
                || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (mPendingNewWidget
                    && mPendingWidgetId
                            != AppWidgetManager.INVALID_APPWIDGET_ID) {
                deleteWidgetId(mPendingWidgetId);
            }
            mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
            mPendingNewWidget = false;
            return true;
        }
        if (requestCode == REQUEST_BIND) {
            finishInitialBinding(appWidgetId);
            return true;
        }
        completePendingWidget();
        return true;
    }

    private void finishInitialBinding(final int appWidgetId) {
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(appWidgetId);
        if (info == null) {
            deleteWidgetId(appWidgetId);
            clearPendingWidget();
            reportUnavailable("The selected widget was not bound", null);
            return;
        }
        if (info.configure == null) {
            completePendingWidget();
            return;
        }
        try {
            mLease.host.startAppWidgetConfigureActivityForResult(
                    mActivity,
                    appWidgetId,
                    0,
                    REQUEST_CONFIGURE,
                    (Bundle) null);
        } catch (RuntimeException error) {
            deleteWidgetId(appWidgetId);
            clearPendingWidget();
            reportUnavailable(
                    "The widget configuration could not be opened", error);
        }
    }

    private void completePendingWidget() {
        clearPendingWidget();
        mChanged.run();
    }

    private void clearPendingWidget() {
        mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        mPendingNewWidget = false;
    }

    void configure(final int appWidgetId) {
        if (mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        if (!owns(appWidgetId)) return;
        final AppWidgetProviderInfo info =
                mManager.getAppWidgetInfo(appWidgetId);
        if (info == null || info.configure == null) {
            return;
        }
        mPendingWidgetId = appWidgetId;
        mPendingNewWidget = false;
        try {
            mLease.host.startAppWidgetConfigureActivityForResult(
                    mActivity,
                    appWidgetId,
                    0,
                    REQUEST_CONFIGURE,
                    (Bundle) null);
        } catch (RuntimeException error) {
            mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
            mPendingNewWidget = false;
            reportUnavailable(
                    "The widget configuration could not be opened", error);
        }
    }

    void remove(final int appWidgetId) {
        deleteWidgetId(appWidgetId);
        mChanged.run();
    }

    void updateSize(
            final AppWidgetHostView view,
            final DesktopPlacement placement,
            final int cellWidth,
            final int cellHeight) {
        if (!owns(view.getAppWidgetId())) return;
        final float density = mActivity.getResources()
                .getDisplayMetrics().density;
        final int widthDp = Math.max(
                1, Math.round(placement.columnSpan * cellWidth / density));
        final int heightDp = Math.max(
                1, Math.round(placement.rowSpan * cellHeight / density));
        try {
            view.updateAppWidgetSize(
                    null, widthDp, heightDp, widthDp, heightDp);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot update widget size", error);
        }
    }

    private int resolveResultId(final Intent data) {
        if (data != null) {
            final int resultId = data.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (resultId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                return resultId;
            }
        }
        return mPendingWidgetId;
    }

    private void deleteWidgetId(final int appWidgetId) {
        try {
            if (mLease == null || !mLease.owns(appWidgetId)) return;
            mViews.remove(Integer.valueOf(appWidgetId));
            mLease.host.deleteAppWidgetId(appWidgetId);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot delete widget " + appWidgetId, error);
        }
    }

    private void reportUnavailable(
            final String detail,
            final Throwable error) {
        CompatibilityDiagnostics.record(
                "WIDGETS-002",
                "Android widgets are unavailable",
                detail,
                error);
        mActivity.setErrorStatus(
                "WIDGETS-002",
                mActivity.getString(R.string.status_widgets_unavailable),
                detail,
                error);
    }

    static final class WidgetEntry {
        final int appWidgetId;
        final AppWidgetProviderInfo info;

        WidgetEntry(
                final int appWidgetId,
                final AppWidgetProviderInfo info) {
            this.appWidgetId = appWidgetId;
            this.info = info;
        }

        String itemId() {
            return "widget:" + appWidgetId;
        }
    }

}
