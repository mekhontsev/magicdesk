package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DesktopWidgetController {
    static final int REQUEST_BIND = 1101;
    static final int REQUEST_CONFIGURE = 1102;
    private static final int HOST_ID = 0x4d44;
    private static final String TAG = "MagicDeskWidgets";

    private final DesktopShellActivity mActivity;
    private final AppWidgetManager mManager;
    private final AppWidgetHost mHost;
    private final DesktopWidgetPickerController mPicker;
    private final Runnable mChanged;
    private final Map<Integer, AppWidgetHostView> mViews = new HashMap<>();
    private int mPendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean mPendingNewWidget;
    private boolean mListening;

    DesktopWidgetController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui,
            final Runnable changed) {
        mActivity = activity;
        mManager = AppWidgetManager.getInstance(activity);
        mHost = new DesktopAppWidgetHost(activity, HOST_ID);
        mPicker = new DesktopWidgetPickerController(activity, ui);
        mChanged = changed;
    }

    void start() {
        if (mListening) {
            return;
        }
        try {
            mHost.startListening();
            mListening = true;
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
        if (!mListening) {
            return;
        }
        try {
            mHost.stopListening();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot stop widget host", error);
        }
        mListening = false;
    }

    void release() {
        stop();
        mViews.clear();
    }

    List<WidgetEntry> widgets() {
        final int[] ids = mHost.getAppWidgetIds();
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        final List<WidgetEntry> widgets = new ArrayList<>();
        for (final int appWidgetId : ids) {
            final AppWidgetProviderInfo info =
                    mManager.getAppWidgetInfo(appWidgetId);
            if (info != null) {
                widgets.add(new WidgetEntry(appWidgetId, info));
            } else {
                deleteWidgetId(appWidgetId);
            }
        }
        return widgets;
    }

    AppWidgetHostView createView(final WidgetEntry widget) {
        AppWidgetHostView view = mViews.get(
                Integer.valueOf(widget.appWidgetId));
        if (view == null) {
            view = mHost.createView(
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
        mActivity.hideAllPanels();
        mPicker.show(mManager.getInstalledProviders(), this::bindWidget);
    }

    private void bindWidget(final AppWidgetProviderInfo info) {
        final int appWidgetId;
        try {
            appWidgetId = mHost.allocateAppWidgetId();
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
            mHost.startAppWidgetConfigureActivityForResult(
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
        final AppWidgetProviderInfo info =
                mManager.getAppWidgetInfo(appWidgetId);
        if (info == null || info.configure == null) {
            return;
        }
        mPendingWidgetId = appWidgetId;
        mPendingNewWidget = false;
        try {
            mHost.startAppWidgetConfigureActivityForResult(
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
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        try {
            mViews.remove(Integer.valueOf(appWidgetId));
            mHost.deleteAppWidgetId(appWidgetId);
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

    private static final class DesktopAppWidgetHost extends AppWidgetHost {
        DesktopAppWidgetHost(final Context context, final int hostId) {
            super(context, hostId);
        }

        @Override
        protected AppWidgetHostView onCreateView(
                final Context context,
                final int appWidgetId,
                final AppWidgetProviderInfo info) {
            return new DesktopAppWidgetHostView(context);
        }
    }

    private static final class DesktopAppWidgetHostView
            extends AppWidgetHostView {
        DesktopAppWidgetHostView(final Context context) {
            super(context);
        }

        @Override
        protected void prepareView(final View view) {
            super.prepareView(view);
            final AppWidgetProviderInfo info = getAppWidgetInfo();
            if (info == null) {
                return;
            }
            final FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) view.getLayoutParams();
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
