package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_RED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;

final class NotificationCenterController {
    private static final String TAG = "MagicDeskNotificationsUi";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopNotificationListenerService.Listener mListener =
            new DesktopNotificationListenerService.Listener() {
                @Override
                public void onNotificationsChanged(
                        final DesktopNotificationListenerService.Snapshot snapshot) {
                    mActivity.runOnUiThread(() -> handleSnapshot(snapshot));
                }

                @Override
                public void onNotificationPopup(
                        final DesktopNotificationListenerService.Entry entry) {
                    mActivity.runOnUiThread(() -> showPopup(entry));
                }
            };

    private DesktopNotificationListenerService.Snapshot mSnapshot =
            DesktopNotificationListenerService.getSnapshot();
    private LinearLayout mPanel;
    private TextView mBadge;
    private ImageButton mButton;

    NotificationCenterController(
            final DesktopShellActivity activity, final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void start() {
        DesktopNotificationListenerService.addListener(mListener);
        DesktopNotificationListenerService.requestRebindIfGranted(mActivity);
    }

    void stop() {
        DesktopNotificationListenerService.removeListener(mListener);
    }

    void refresh() {
        DesktopNotificationListenerService.requestRebindIfGranted(mActivity);
        handleSnapshot(DesktopNotificationListenerService.getSnapshot());
    }

    LinearLayout createPanel() {
        mPanel = new LinearLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(dp(14), dp(14), dp(14), dp(12));
        mPanel.setBackground(mUi.rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        mPanel.setVisibility(View.GONE);
        mPanel.setClickable(true);
        mPanel.setFocusable(true);
        return mPanel;
    }

    View createTaskbarButton(final boolean compact) {
        final FrameLayout container = new FrameLayout(mActivity);
        mButton = mUi.taskbarIconButton(
                R.drawable.ic_notifications,
                R.string.action_notifications,
                compact);
        mButton.setOnClickListener(view -> toggle());
        container.addView(mButton, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mBadge = new TextView(mActivity);
        mBadge.setTextColor(Color.WHITE);
        mBadge.setTextSize(9);
        mBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mBadge.setGravity(Gravity.CENTER);
        mBadge.setMinWidth(dp(17));
        mBadge.setMinHeight(dp(17));
        mBadge.setPadding(dp(3), 0, dp(3), 0);
        mBadge.setBackground(mUi.rounded(COLOR_RED, dp(9), COLOR_RED));
        mBadge.setVisibility(View.GONE);
        final FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(17),
                Gravity.TOP | Gravity.END);
        badgeParams.setMargins(0, 0, dp(1), 0);
        container.addView(mBadge, badgeParams);
        updateBadge();
        return container;
    }

    void toggle() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null || mPanel == null) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            mActivity.hideAllPanels();
            return;
        }
        mActivity.captureInteractionStackForPanel();
        DesktopNotificationListenerService.markAllRead();
        render();
        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int areaHeight = mActivity.getDesktopAreaHeight();
        final int width =
                Math.min(dp(420), Math.max(dp(280), areaWidth - dp(16)));
        final int height = Math.max(
                dp(180), areaHeight - mActivity.getTaskbarHeight() - dp(16));
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, areaWidth - width - dp(8));
        final int top = mActivity.getDesktopAreaTop() + dp(8);
        if (!overlays.show(mPanel, left, top, width, height,
                false, "MagicDesk notifications")) {
            mActivity.setErrorStatus("OVERLAY-001", mActivity.getString(
                    R.string.status_overlay_panel_unavailable));
        }
    }

    private void handleSnapshot(
            final DesktopNotificationListenerService.Snapshot snapshot) {
        if (snapshot == null || mActivity.isActivityUnavailable()) {
            return;
        }
        mSnapshot = snapshot;
        updateBadge();
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.isVisible(mPanel)) {
            render();
        }
    }

    private void updateBadge() {
        if (mBadge == null) {
            return;
        }
        final int unread = mSnapshot == null ? 0 : mSnapshot.unreadCount;
        mBadge.setText(unread > 99 ? "99+" : Integer.toString(unread));
        mBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        if (mButton == null) {
            return;
        }
        final String description = unread > 0
                ? mActivity.getResources().getQuantityString(
                        R.plurals.notification_count_description,
                        unread,
                        Integer.valueOf(unread))
                : mActivity.getString(R.string.action_notifications);
        mButton.setContentDescription(description);
        mButton.setTooltipText(description);
    }

    private void render() {
        if (mPanel == null) {
            return;
        }
        mPanel.removeAllViews();
        addHeader();
        if (!DesktopNotificationListenerService.isAccessGranted(mActivity)) {
            addAccessState(R.string.notification_access_off, true);
            return;
        }
        if (mSnapshot == null || !mSnapshot.connected) {
            if (mSnapshot != null
                    && !TextUtils.isEmpty(mSnapshot.connectionIssueCode)) {
                addAccessState(mActivity.getString(
                        R.string.notification_listener_reconnect_failed,
                        mSnapshot.connectionIssueCode), false);
            } else {
                addAccessState(
                        R.string.notification_listener_connecting, false);
            }
            return;
        }
        if (mSnapshot.entries.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.notifications_empty);
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mPanel.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(10), 0, 0);
        final LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final DesktopNotificationListenerService.Entry entry
                : mSnapshot.entries) {
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(8));
            list.addView(createItem(entry, false), params);
        }
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void addHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final int count = mSnapshot == null ? 0 : mSnapshot.entries.size();
        final TextView title = new TextView(mActivity);
        title.setText(mActivity.getString(
                R.string.notifications_title, Integer.valueOf(count)));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button clear = mUi.smallButton(
                R.string.action_clear_notifications, COLOR_PANEL_ALT);
        clear.setEnabled(hasClearableNotifications());
        clear.setOnClickListener(view -> {
            if (!DesktopNotificationListenerService.clearAllNotifications()) {
                mActivity.setErrorStatus(
                        "NOTIFICATIONS-002",
                        mActivity.getString(
                                R.string.status_notifications_unavailable));
            }
        });
        header.addView(clear, new LinearLayout.LayoutParams(
                dp(82), LinearLayout.LayoutParams.WRAP_CONTENT));
        final Button close =
                mUi.smallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> mActivity.hideAllPanels());
        final LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        dp(72), LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(dp(8), 0, 0, 0);
        header.addView(close, closeParams);
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private boolean hasClearableNotifications() {
        if (mSnapshot == null || !mSnapshot.connected) {
            return false;
        }
        for (final DesktopNotificationListenerService.Entry entry
                : mSnapshot.entries) {
            if (entry.clearable) {
                return true;
            }
        }
        return false;
    }

    private void addAccessState(
            final int messageResId, final boolean showSettings) {
        addAccessState(mActivity.getString(messageResId), showSettings);
    }

    private void addAccessState(
            final CharSequence text, final boolean showSettings) {
        final LinearLayout state = new LinearLayout(mActivity);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(16), dp(20), dp(16), dp(20));
        final TextView message = new TextView(mActivity);
        message.setText(text);
        message.setTextColor(COLOR_MUTED);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        state.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (showSettings) {
            final Button settings = mUi.actionButton(
                    R.string.action_notification_access, COLOR_CYAN);
            settings.setOnClickListener(
                    view -> openNotificationAccessSettings());
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(14), 0, 0);
            state.addView(settings, params);
        }
        mPanel.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private View createItem(
            final DesktopNotificationListenerService.Entry entry,
            final boolean popup) {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(10), dp(10), dp(10));
        final int borderColor =
                entry.importance >= NotificationManager.IMPORTANCE_HIGH
                        ? COLOR_CYAN : COLOR_PANEL_ALT;
        item.setBackground(
                mUi.rounded(COLOR_PANEL_ALT, dp(8), borderColor));
        item.setClickable(true);
        item.setFocusable(true);
        addItemHeader(item, entry, popup);
        addItemText(item, entry, popup);
        addItemActions(item, entry, popup);
        item.setOnClickListener(view -> openNotification(entry));
        item.setContentDescription(mActivity.getString(
                R.string.notification_item_description,
                entry.appName,
                TextUtils.isEmpty(entry.title) ? entry.text : entry.title));
        return item;
    }

    private void addItemHeader(
            final LinearLayout item,
            final DesktopNotificationListenerService.Entry entry,
            final boolean popup) {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(loadIcon(entry));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        final TextView app = new TextView(mActivity);
        app.setText(entry.appName);
        app.setTextColor(COLOR_MUTED);
        app.setTextSize(12);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams appParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        appParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(app, appParams);
        final TextView time = new TextView(mActivity);
        time.setText(DateFormat.getTimeFormat(mActivity)
                .format(new Date(entry.postTime)));
        time.setTextColor(COLOR_MUTED);
        time.setTextSize(11);
        time.setSingleLine(true);
        header.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (entry.clearable) {
            final ImageButton dismiss = mUi.taskbarIconButton(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.action_dismiss_notification,
                    mActivity.isCompactDesktopPreview());
            dismiss.setPadding(dp(7), dp(7), dp(7), dp(7));
            dismiss.setOnClickListener(view -> {
                final OverlayPanelController overlays =
                        mActivity.overlayPanels();
                if (popup && overlays != null) {
                    overlays.hideTransient();
                }
                DesktopNotificationListenerService.dismissNotification(
                        entry.key);
            });
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(34), dp(34));
            params.setMargins(dp(6), 0, 0, 0);
            header.addView(dismiss, params);
        }
        item.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addItemText(
            final LinearLayout item,
            final DesktopNotificationListenerService.Entry entry,
            final boolean popup) {
        if (!TextUtils.isEmpty(entry.title)) {
            final TextView title = new TextView(mActivity);
            title.setText(entry.title);
            title.setTextColor(COLOR_TEXT);
            title.setTextSize(14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(popup ? 1 : 2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(7), 0, 0);
            item.addView(title, params);
        }
        if (!TextUtils.isEmpty(entry.text)) {
            final TextView text = new TextView(mActivity);
            text.setText(entry.text);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setMaxLines(popup ? 2 : 5);
            text.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(4), 0, 0);
            item.addView(text, params);
        }
    }

    private void addItemActions(
            final LinearLayout item,
            final DesktopNotificationListenerService.Entry entry,
            final boolean popup) {
        if (entry.actions.isEmpty()) {
            return;
        }
        final LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final int count = Math.min(popup ? 1 : 3, entry.actions.size());
        for (int index = 0; index < count; index++) {
            final DesktopNotificationListenerService.ActionEntry action =
                    entry.actions.get(index);
            final Button button = mUi.smallButton(action.title, COLOR_CYAN);
            button.setOnClickListener(view -> invokeAction(entry, action));
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (index > 0) {
                params.setMargins(dp(6), 0, 0, 0);
            }
            actions.addView(button, params);
        }
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        item.addView(actions, params);
    }

    private Drawable loadIcon(
            final DesktopNotificationListenerService.Entry entry) {
        if (entry.icon != null) {
            try {
                final Drawable icon = entry.icon.loadDrawable(mActivity);
                if (icon != null) {
                    return icon;
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "failed to load notification icon for "
                        + entry.packageName, error);
            }
        }
        try {
            return mActivity.getPackageManager()
                    .getApplicationIcon(entry.packageName);
        } catch (PackageManager.NameNotFoundException error) {
            return mActivity.getDrawable(
                    android.R.drawable.sym_def_app_icon);
        }
    }

    private void showPopup(
            final DesktopNotificationListenerService.Entry entry) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (!mActivity.isDesktopShell() || entry == null || overlays == null
                || isDeviceLocked()) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            DesktopNotificationListenerService.markRead(entry.key);
            return;
        }
        final View popup = createItem(entry, true);
        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int areaHeight = mActivity.getDesktopAreaHeight();
        final int width = Math.min(dp(380), areaWidth - dp(24));
        popup.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(dp(100), areaHeight
                                - mActivity.getTaskbarHeight() - dp(24)),
                        View.MeasureSpec.AT_MOST));
        final int height = Math.max(dp(92), popup.getMeasuredHeight());
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, areaWidth - width - dp(12));
        final int top = mActivity.getDesktopAreaTop() + Math.max(
                dp(12),
                areaHeight - mActivity.getTaskbarHeight()
                        - height - dp(12));
        if (!overlays.showTransient(
                popup, left, top, width, height, 7000L,
                "MagicDesk notification")) {
            Log.w(TAG, "notification popup overlay unavailable");
        }
    }

    private boolean isDeviceLocked() {
        final KeyguardManager manager =
                mActivity.getSystemService(KeyguardManager.class);
        return manager != null && manager.isDeviceLocked();
    }

    private void openNotification(
            final DesktopNotificationListenerService.Entry entry) {
        if (entry == null) {
            return;
        }
        mActivity.hideAllPanels();
        if (entry.hasContentIntent
                && DesktopNotificationListenerService.openNotification(
                        mActivity, entry.key, mActivity.getCurrentDisplayId())) {
            return;
        }
        final AppItem app = LauncherAppRepository.find(
                mActivity.getLauncherApps(), entry.packageName);
        if (app != null) {
            mActivity.launchDefault(app);
        } else {
            mActivity.setErrorStatus(
                    "NOTIFICATIONS-003",
                    mActivity.getString(
                            R.string.status_notification_open_failed));
        }
    }

    private void invokeAction(
            final DesktopNotificationListenerService.Entry entry,
            final DesktopNotificationListenerService.ActionEntry action) {
        mActivity.hideAllPanels();
        if (!DesktopNotificationListenerService.invokeAction(
                mActivity, entry.key, action.index,
                mActivity.getCurrentDisplayId())) {
            mActivity.setErrorStatus(
                    "NOTIFICATIONS-004",
                    mActivity.getString(
                            R.string.status_notification_action_failed));
        }
    }

    private void openNotificationAccessSettings() {
        mActivity.hideAllPanels();
        final String component = DesktopNotificationListenerService
                .getComponentName(mActivity).flattenToString();
        final Intent intent = new Intent(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        try {
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException detailFailure) {
            Log.w(TAG, "notification detail settings unavailable",
                    detailFailure);
            final Intent fallback = new Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mActivity.startActivity(fallback, options.toBundle());
            } catch (RuntimeException error) {
                mActivity.showLaunchFailure(error);
            }
        }
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
