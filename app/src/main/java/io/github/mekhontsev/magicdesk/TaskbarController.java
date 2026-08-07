package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.os.BatteryManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TaskbarController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    private LinearLayout mTaskbar;
    private LinearLayout mPins;
    private TextView mKeyboardLayout;
    private final InputMethodMenuController mInputMethodMenu;
    private TextView mBatteryStatus;
    private ImageButton mSystemButton;
    private ImageButton mPhoneScreenButton;
    private Intent mLastBatteryIntent;
    private boolean mChargeSeparationEnabled;
    private final List<Integer> mTaskOrder = new ArrayList<>();

    TaskbarController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mInputMethodMenu = new InputMethodMenuController(activity, ui);
    }

    LinearLayout create() {
        final LinearLayout taskbar = new LinearLayout(mActivity) {
            private final int mTouchSlop = ViewConfiguration.get(
                    mActivity).getScaledTouchSlop();
            private float mBlankDownX;
            private float mBlankDownY;
            private boolean mBlankLongPressPending;
            private final Runnable mBlankLongPress = () -> {
                if (!mBlankLongPressPending) {
                    return;
                }
                mBlankLongPressPending = false;
                mActivity.captureInteractionStackForPanel();
                mActivity.showDesktopContextMenu(mBlankDownX, mBlankDownY);
            };

            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (mActivity.handleDesktopMouseTouchEvent(event, true)) {
                    cancelBlankLongPress();
                    return true;
                }
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelBlankLongPress();
                    if (!isActionAt(event.getX(), event.getY())) {
                        mActivity.hideAllPanels();
                        mActivity.clearInteractionVisibleTasks();
                        mBlankDownX = event.getRawX();
                        mBlankDownY = event.getRawY();
                        mBlankLongPressPending = true;
                        postDelayed(
                                mBlankLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (action == MotionEvent.ACTION_MOVE
                        && mBlankLongPressPending
                        && (Math.abs(event.getRawX() - mBlankDownX) > mTouchSlop
                                || Math.abs(event.getRawY() - mBlankDownY)
                                        > mTouchSlop)) {
                    cancelBlankLongPress();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    cancelBlankLongPress();
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mActivity.handleDesktopMouseGenericEvent(event, true)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

            private void cancelBlankLongPress() {
                mBlankLongPressPending = false;
                removeCallbacks(mBlankLongPress);
            }
        };
        taskbar.setOrientation(LinearLayout.HORIZONTAL);
        taskbar.setGravity(Gravity.CENTER_VERTICAL);
        taskbar.setPadding(
                desktopDp(10, 4),
                desktopDp(8, 4),
                desktopDp(10, 4),
                desktopDp(8, 4));
        taskbar.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                0,
                DesktopUiFactory.COLOR_PANEL_ALT));

        final Button start = mUi.actionButton(
                R.string.action_start,
                DesktopUiFactory.COLOR_CYAN);
        start.setTextSize(14);
        start.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        start.setOnClickListener(view -> mActivity.toggleStartMenu());
        taskbar.addView(start, new LinearLayout.LayoutParams(
                desktopDp(108, 72),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final HorizontalScrollView taskScroll =
                new HorizontalScrollView(mActivity);
        taskScroll.setHorizontalScrollBarEnabled(false);
        taskScroll.setFillViewport(true);
        mPins = new LinearLayout(mActivity);
        mPins.setOrientation(LinearLayout.HORIZONTAL);
        mPins.setGravity(Gravity.CENTER_VERTICAL);
        taskScroll.addView(mPins, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        final LinearLayout.LayoutParams pinsParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        pinsParams.setMargins(
                desktopDp(10, 4), 0, desktopDp(10, 4), 0);
        taskbar.addView(taskScroll, pinsParams);

        final ImageButton showDesktop = taskbarButton(
                R.drawable.ic_show_desktop,
                R.string.action_show_desktop);
        showDesktop.setOnClickListener(view ->
                mActivity.toggleDesktopWorkspace());
        addButton(taskbar, showDesktop);

        final ImageButton taskOverview = taskbarButton(
                android.R.drawable.ic_menu_recent_history,
                R.string.action_open_tasks);
        taskOverview.setOnClickListener(view ->
                mActivity.toggleTaskOverview());
        addButton(taskbar, taskOverview);

        taskbar.addView(
                mActivity.notifications().createTaskbarButton(
                        mActivity.isCompactDesktopPreview()),
                new LinearLayout.LayoutParams(
                        desktopDp(46, 38),
                        LinearLayout.LayoutParams.MATCH_PARENT));

        mKeyboardLayout = new TextView(mActivity);
        mKeyboardLayout.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mKeyboardLayout.setTextSize(
                mActivity.isCompactDesktopPreview() ? 11 : 13);
        mKeyboardLayout.setAutoSizeTextTypeUniformWithConfiguration(
                8,
                mActivity.isCompactDesktopPreview() ? 11 : 13,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        mKeyboardLayout.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD);
        mKeyboardLayout.setGravity(Gravity.CENTER);
        mKeyboardLayout.setClickable(true);
        mKeyboardLayout.setFocusable(true);
        mKeyboardLayout.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                desktopDp(8, 6),
                DesktopUiFactory.COLOR_PANEL_ALT));
        mKeyboardLayout.setOnClickListener(mInputMethodMenu::toggle);
        mKeyboardLayout.setEnabled(
                ShellAccess.isReady());
        taskbar.addView(mKeyboardLayout, new LinearLayout.LayoutParams(
                desktopDp(48, 38),
                LinearLayout.LayoutParams.MATCH_PARENT));
        if (mActivity.isCompactDesktopPreview()) {
            mKeyboardLayout.setVisibility(View.GONE);
        }

        mPhoneScreenButton = taskbarButton(
                R.drawable.ic_phone_screen_off,
                R.string.tooltip_phone_screen);
        mPhoneScreenButton.setOnClickListener(view ->
                mActivity.togglePhoneScreen());
        mPhoneScreenButton.setEnabled(false);
        addButton(taskbar, mPhoneScreenButton);
        if (mActivity.isCompactDesktopPreview()
                || mActivity.getCurrentDisplayId() == Display.DEFAULT_DISPLAY) {
            mPhoneScreenButton.setVisibility(View.GONE);
        }

        mSystemButton = taskbarButton(
                android.R.drawable.ic_menu_manage,
                R.string.section_system);
        mSystemButton.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        addButton(taskbar, mSystemButton);

        mBatteryStatus = new TextView(mActivity);
        mBatteryStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mBatteryStatus.setTextSize(
                mActivity.isCompactDesktopPreview() ? 10 : 12);
        mBatteryStatus.setGravity(Gravity.CENTER);
        mBatteryStatus.setSingleLine(true);
        mBatteryStatus.setClickable(true);
        mBatteryStatus.setFocusable(true);
        mBatteryStatus.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        taskbar.addView(mBatteryStatus, new LinearLayout.LayoutParams(
                desktopDp(58, 44),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final TextClock clock = new TextClock(mActivity);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(DesktopUiFactory.COLOR_TEXT);
        clock.setTextSize(mActivity.isCompactDesktopPreview() ? 12 : 16);
        clock.setGravity(Gravity.CENTER);
        clock.setClickable(true);
        clock.setFocusable(true);
        clock.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                desktopDp(8, 6),
                DesktopUiFactory.COLOR_PANEL_ALT));
        clock.setContentDescription(
                mActivity.getString(R.string.action_calendar));
        clock.setTooltipText(mActivity.getString(R.string.action_calendar));
        clock.setOnClickListener(view -> mActivity.toggleCalendarPanel());
        taskbar.addView(clock, new LinearLayout.LayoutParams(
                desktopDp(72, 50),
                LinearLayout.LayoutParams.MATCH_PARENT));
        mTaskbar = taskbar;
        return taskbar;
    }

    void release() {
        mTaskbar = null;
        mPins = null;
        mKeyboardLayout = null;
        mInputMethodMenu.release();
        mBatteryStatus = null;
        mSystemButton = null;
        mPhoneScreenButton = null;
    }

    void setVisible(final boolean visible) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && mTaskbar != null) {
            overlays.setPersistentVisible(visible);
        }
    }

    void setPhoneScreenActionEnabled(final boolean enabled) {
        if (mPhoneScreenButton != null) {
            mPhoneScreenButton.setEnabled(enabled);
        }
    }

    void renderPins(final List<AppItem> apps) {
        if (mPins == null) {
            return;
        }
        mPins.removeAllViews();
        final List<String> pinnedPackages = mActivity.getPinnedPackages();
        final String workspacePackage = mActivity.getWorkspacePackage();
        if (workspacePackage != null
                && !pinnedPackages.contains(workspacePackage)) {
            pinnedPackages.add(workspacePackage);
        }
        final Set<Integer> renderedTaskIds = new HashSet<>();
        final List<TaskRepository.TaskEntry> orderedTasks =
                getOrderedTaskbarTasks();

        for (final String packageName : pinnedPackages) {
            final AppItem app = LauncherAppRepository.find(apps, packageName);
            if (app == null) {
                continue;
            }
            final List<TaskRepository.TaskEntry> packageTasks =
                    findTasks(orderedTasks, packageName);
            if (packageTasks.isEmpty()) {
                addPin(app, null);
                continue;
            }
            for (final TaskRepository.TaskEntry task : packageTasks) {
                addPin(app, task);
                renderedTaskIds.add(Integer.valueOf(task.taskId));
            }
        }

        for (final TaskRepository.TaskEntry task : orderedTasks) {
            if (renderedTaskIds.contains(
                    Integer.valueOf(task.taskId))) {
                continue;
            }
            final AppItem app = mActivity.findOrLoadApp(
                    apps, task.packageName);
            if (app != null) {
                addPin(app, task);
            }
        }
    }

    List<String> getPinnedPackages() {
        return DesktopPreferences.taskbarPackages(mActivity);
    }

    void togglePinned(final AppItem app) {
        final List<String> pinned = getPinnedPackages();
        final boolean nowPinned;
        if (pinned.remove(app.packageName)) {
            nowPinned = false;
        } else {
            pinned.add(app.packageName);
            nowPinned = true;
        }
        DesktopPreferences.saveTaskbarPackages(mActivity, pinned);
        renderPins(mActivity.getLauncherApps());
        mActivity.renderStartMenuContent();
        mActivity.setStatus(mActivity.getString(
                nowPinned
                        ? R.string.status_app_pinned
                        : R.string.status_app_unpinned,
                app.label));
    }

    private List<TaskRepository.TaskEntry> getOrderedTaskbarTasks() {
        final List<TaskRepository.TaskEntry> liveTasks = new ArrayList<>();
        final Set<Integer> liveTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task :
                mActivity.getTaskSnapshot().tasks) {
            if (!mActivity.isTaskbarTask(task)) {
                continue;
            }
            liveTasks.add(task);
            liveTaskIds.add(Integer.valueOf(task.taskId));
        }

        for (int index = mTaskOrder.size() - 1; index >= 0; index--) {
            if (!liveTaskIds.contains(mTaskOrder.get(index))) {
                mTaskOrder.remove(index);
            }
        }
        for (final TaskRepository.TaskEntry task : liveTasks) {
            final Integer taskId = Integer.valueOf(task.taskId);
            if (!mTaskOrder.contains(taskId)) {
                mTaskOrder.add(taskId);
            }
        }

        final List<TaskRepository.TaskEntry> orderedTasks = new ArrayList<>();
        for (final Integer taskId : mTaskOrder) {
            for (final TaskRepository.TaskEntry task : liveTasks) {
                if (task.taskId == taskId.intValue()) {
                    orderedTasks.add(task);
                    break;
                }
            }
        }
        return orderedTasks;
    }

    private static List<TaskRepository.TaskEntry> findTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final String packageName) {
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (packageName.equals(task.packageName)) {
                result.add(task);
            }
        }
        return result;
    }

    void updateKeyboardLayout() {
        if (mKeyboardLayout == null) {
            return;
        }
        final String layout = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_STATE);
        String layoutLabel = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_LABEL_STATE);
        if (layoutLabel == null || layoutLabel.isEmpty()) {
            layoutLabel = "russian".equals(layout)
                    ? "RU"
                    : ("english".equals(layout) ? "EN" : "??");
        }
        final String layoutName = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_NAME_STATE);
        mKeyboardLayout.setText(layoutLabel);
        final String description = mActivity.getString(
                R.string.keyboard_layout_description,
                layoutName == null || layoutName.isEmpty()
                        ? layoutLabel
                        : layoutName);
        mKeyboardLayout.setContentDescription(description);
        mKeyboardLayout.setTooltipText(description);
    }

    void updatePhoneScreen(
            final boolean phoneScreenOff,
            final boolean visible,
            final boolean phoneScreenControl) {
        if (mPhoneScreenButton == null) {
            return;
        }
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        mPhoneScreenButton.setImageResource(phoneScreenOff
                ? R.drawable.ic_phone_screen_on
                : R.drawable.ic_phone_screen_off);
        mPhoneScreenButton.setColorFilter(
                phoneScreenOff
                        ? DesktopUiFactory.COLOR_CYAN
                        : DesktopUiFactory.COLOR_TEXT);
        mPhoneScreenButton.setContentDescription(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setTooltipText(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setEnabled(phoneScreenControl);
        mPhoneScreenButton.setAlpha(phoneScreenControl ? 1f : 0.45f);
        mPhoneScreenButton.setVisibility(
                visible && !mActivity.isCompactDesktopPreview()
                        ? View.VISIBLE : View.GONE);
    }

    void updateSystemStatus(final boolean shortcutsReady) {
        if (mSystemButton == null) {
            return;
        }
        final boolean taskControl =
                ShellAccess.isReady();
        final int color = taskControl && shortcutsReady
                ? DesktopUiFactory.COLOR_CYAN
                : (taskControl
                        ? DesktopUiFactory.COLOR_AMBER
                        : DesktopUiFactory.COLOR_MUTED);
        final String description = mActivity.getString(
                R.string.system_status_description,
                ShellAccess.statusLabel(),
                mActivity.getString(R.string.state_ready),
                mActivity.getString(shortcutsReady
                        ? R.string.state_ready
                        : R.string.state_unavailable));
        mSystemButton.setColorFilter(color);
        mSystemButton.setContentDescription(description);
        mSystemButton.setTooltipText(description);
    }

    void updateBattery(final Intent battery) {
        if (mBatteryStatus == null || battery == null) {
            return;
        }
        mLastBatteryIntent = battery;
        final int level =
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale =
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1
                : Math.max(
                        0,
                        Math.min(
                                100,
                                Math.round(level * 100f / scale)));
        final int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        final boolean charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING;
        final boolean full =
                status == BatteryManager.BATTERY_STATUS_FULL;
        mBatteryStatus.setText(percent < 0
                ? mActivity.getString(R.string.battery_compact_unknown)
                : mActivity.getString(
                        R.string.battery_compact,
                        Integer.valueOf(percent)));
        mBatteryStatus.setTextColor(
                charging || mChargeSeparationEnabled
                        ? DesktopUiFactory.COLOR_CYAN
                        : DesktopUiFactory.COLOR_TEXT);
        final String state = mActivity.getString(
                charging
                        ? R.string.battery_state_charging
                        : (full
                                ? R.string.battery_state_full
                                : R.string.battery_state_discharging));
        final String description = percent < 0
                ? mActivity.getString(R.string.battery_status_unknown)
                : (mChargeSeparationEnabled
                        ? mActivity.getString(
                                R.string.battery_status_bypass_description,
                                Integer.valueOf(percent))
                        : mActivity.getString(
                        R.string.battery_status_description,
                        Integer.valueOf(percent),
                        state));
        mBatteryStatus.setContentDescription(description);
        mBatteryStatus.setTooltipText(description);
    }

    void updateChargeSeparation(final boolean enabled) {
        mChargeSeparationEnabled = enabled;
        if (mLastBatteryIntent != null) {
            updateBattery(mLastBatteryIntent);
        }
    }

    private void addPin(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mPins.addView(
                createPin(app, task),
                new LinearLayout.LayoutParams(
                        desktopDp(48, 36),
                        LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private View createPin(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final FrameLayout item = new FrameLayout(mActivity);
        final boolean workspaceApp =
                mActivity.isWorkspaceApp(app.packageName);
        final int borderColor = workspaceApp
                ? DesktopUiFactory.COLOR_AMBER
                : (task == null
                        ? DesktopUiFactory.COLOR_PANEL_ALT
                        : (task.active
                                ? DesktopUiFactory.COLOR_AMBER
                                : DesktopUiFactory.COLOR_CYAN));
        item.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                desktopDp(10, 8),
                borderColor));
        item.setClickable(true);
        item.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        icon.setPadding(
                desktopDp(7, 5),
                desktopDp(7, 5),
                desktopDp(7, 5),
                desktopDp(7, 5));
        item.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (task != null) {
            final View running = new View(mActivity);
            running.setBackgroundColor(task.active
                    ? DesktopUiFactory.COLOR_AMBER
                    : DesktopUiFactory.COLOR_CYAN);
            final FrameLayout.LayoutParams runningParams =
                    new FrameLayout.LayoutParams(
                            desktopDp(20, 14),
                            dp(3),
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            runningParams.setMargins(0, 0, 0, dp(2));
            item.addView(running, runningParams);
        }

        final String description = task == null
                ? app.label
                : mActivity.getString(
                        R.string.taskbar_running_description,
                        app.label,
                        Integer.valueOf(task.taskId));
        item.setContentDescription(description);
        item.setTooltipText(description);
        item.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            if (task == null) {
                mActivity.launchDefault(app);
            } else {
                mActivity.toggleTaskbarTask(app, task);
            }
        });
        mActivity.registerContextTarget(item, app, task);
        return item;
    }

    private void addButton(
            final LinearLayout taskbar,
            final ImageButton button) {
        taskbar.addView(button, new LinearLayout.LayoutParams(
                desktopDp(46, 38),
                LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private ImageButton taskbarButton(
            final int drawableResId,
            final int descriptionResId) {
        return mUi.taskbarIconButton(
                drawableResId,
                descriptionResId,
                mActivity.isCompactDesktopPreview());
    }

    private boolean isActionAt(final float localX, final float localY) {
        if (mTaskbar == null) {
            return false;
        }
        for (int index = 0; index < mTaskbar.getChildCount(); index++) {
            if (isActionViewAt(
                    mTaskbar,
                    mTaskbar.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActionViewAt(
            final ViewGroup parent,
            final View view,
            final float parentX,
            final float parentY) {
        if (view == null || !view.isShown() || !view.isEnabled()) {
            return false;
        }
        final float localX =
                parentX + parent.getScrollX() - view.getLeft();
        final float localY =
                parentY + parent.getScrollY() - view.getTop();
        if (localX < 0
                || localY < 0
                || localX >= view.getWidth()
                || localY >= view.getHeight()) {
            return false;
        }
        if (view.hasOnClickListeners()) {
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (isActionViewAt(
                    group,
                    group.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }
}
