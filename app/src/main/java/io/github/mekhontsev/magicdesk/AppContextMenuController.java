package io.github.mekhontsev.magicdesk;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

final class AppContextMenuController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Map<View, ContextTarget> mTargets = new WeakHashMap<>();

    private LinearLayout mPanel;
    private View mHoveredTargetView;

    AppContextMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout create() {
        final LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(10), dp(10), dp(10));
        menu.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(8),
                DesktopUiFactory.COLOR_CYAN));
        menu.setVisibility(View.GONE);
        menu.setClickable(true);
        menu.setFocusable(true);
        mPanel = menu;
        return menu;
    }

    void registerTarget(
            final View view,
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        if (view == null || app == null) {
            return;
        }
        mTargets.put(view, new ContextTarget(app, task));
        view.setHapticFeedbackEnabled(false);
        view.setOnLongClickListener(target -> {
            mActivity.captureInteractionStackForPanel();
            showForView(target, app, task);
            return true;
        });
        view.setOnHoverListener((hoveredView, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE) {
                mHoveredTargetView = hoveredView;
            } else if (action == MotionEvent.ACTION_HOVER_EXIT
                    && mHoveredTargetView == hoveredView) {
                mHoveredTargetView = null;
            }
            return false;
        });
    }

    void handleSecondaryClick(final float x, final float y) {
        ContextTarget target = findHoveredTarget();
        if (target == null) {
            target = findTargetAt(x, y);
        }
        if (target != null) {
            showAppMenu(x, y, target.app, target.task);
            return;
        }
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.contains(x, y)) {
            return;
        }
        showDesktopMenu(x, y);
    }

    void showDesktopMenu(final float x, final float y) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (mPanel == null || overlays == null) {
            return;
        }
        overlays.hide(mPanel);
        mPanel.removeAllViews();

        final TextView title = new TextView(mActivity);
        title.setText(R.string.context_desktop);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(6));
        mPanel.addView(title, titleParams);

        addAction(
                R.string.action_refresh,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> {
                    mActivity.hideAllPanels();
                    mActivity.renderApps();
                    mActivity.refreshDesktopFolder(true);
                });
        addAction(
                R.string.action_open_tasks,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.showTaskOverview());
        addAction(
                R.string.action_choose_desktop_folder,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.chooseDesktopFolder());
        addAction(
                R.string.action_hide_desktop_folder,
                DesktopUiFactory.COLOR_PANEL_ALT,
                mActivity.hasDesktopFolder(),
                view -> {
                    mActivity.hideAllPanels();
                    mActivity.clearDesktopFolder();
                });
        addAction(
                R.string.section_tools,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.showStartSection(
                        StartMenuController.MENU_TOOLS, false));
        addAction(
                R.string.action_manage_taskbar,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.showStartSection(
                        StartMenuController.MENU_PINNED));
        positionAndShow(x, y);
    }

    private ContextTarget findHoveredTarget() {
        final View view = mHoveredTargetView;
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) {
            mHoveredTargetView = null;
            return null;
        }
        return mTargets.get(view);
    }

    private ContextTarget findTargetAt(final float x, final float y) {
        for (final Map.Entry<View, ContextTarget> entry : mTargets.entrySet()) {
            final View view = entry.getKey();
            if (view != null
                    && view.isShown()
                    && mActivity.isPointInside(view, x, y)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void showForView(
            final View view,
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        showAppMenu(
                location[0] + view.getWidth() / 2f,
                location[1] + view.getHeight() / 2f,
                app,
                task);
    }

    private void showAppMenu(
            final float x,
            final float y,
            final AppItem app,
            final TaskRepository.TaskEntry exactTask) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (mPanel == null || overlays == null) {
            return;
        }
        overlays.hide(mPanel);
        mPanel.removeAllViews();

        final TextView title = new TextView(mActivity);
        title.setText(app.label);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        mPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TaskRepository.TaskEntry task = exactTask != null
                ? exactTask
                : mActivity.findFirstTask(app.packageName);
        if (task != null) {
            final TextView taskInfo = new TextView(mActivity);
            taskInfo.setText(mActivity.getString(
                    R.string.context_task_status,
                    Integer.valueOf(task.taskId),
                    mActivity.getString(task.isFreeform()
                            ? R.string.badge_window
                            : R.string.badge_fullscreen)));
            taskInfo.setTextColor(DesktopUiFactory.COLOR_MUTED);
            taskInfo.setTextSize(12);
            final LinearLayout.LayoutParams taskInfoParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            taskInfoParams.setMargins(0, dp(2), 0, dp(6));
            mPanel.addView(taskInfo, taskInfoParams);
        }

        addAction(
                task == null
                        ? R.string.action_open
                        : R.string.action_switch_to,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> {
                    mActivity.hideAllPanels();
                    if (task == null) {
                        mActivity.launchDefault(app);
                    } else {
                        mActivity.focusTask(app, task);
                    }
                });
        final boolean windowControl = ShellAccess.isReady();
        if (app.canFloat && windowControl) {
            addAction(
                    R.string.action_open_floating,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> {
                        mActivity.hideAllPanels();
                        mActivity.launchFloating(app);
                    });
        }
        addAction(
                R.string.action_open_fullscreen,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> {
                    mActivity.hideAllPanels();
                    if (task == null) {
                        mActivity.launchFullscreen(app);
                    } else {
                        mActivity.openTaskFullscreen(app, task);
                    }
                });
        final int otherDisplayId =
                mActivity.getOtherDisplayId(task);
        if (task != null && otherDisplayId >= 0) {
            addAction(
                    otherDisplayId == 0
                            ? R.string.action_send_to_phone
                            : R.string.action_send_to_external_display,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    ShellAccess.isReady(),
                    view -> mActivity.moveTaskToOtherDisplay(
                            app, task));
        }

        final boolean pinned =
                mActivity.getPinnedPackages().contains(app.packageName);
        addAction(
                pinned ? R.string.action_unpin : R.string.action_pin,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> {
                    mActivity.hideAllPanels();
                    mActivity.togglePinned(app);
                });
        final boolean desktopShortcut =
                mActivity.isDesktopShortcut(app.packageName);
        addAction(
                desktopShortcut
                        ? R.string.action_remove_from_desktop
                        : R.string.action_add_to_desktop,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> {
                    mActivity.hideAllPanels();
                    mActivity.toggleDesktopShortcut(app);
                });
        final boolean workspaceApp =
                mActivity.isWorkspaceApp(app.packageName);
        addAction(
                workspaceApp
                        ? R.string.action_remove_from_workspace
                        : R.string.action_keep_in_workspace,
                workspaceApp
                        ? DesktopUiFactory.COLOR_AMBER
                        : DesktopUiFactory.COLOR_PANEL_ALT,
                windowControl && (workspaceApp || app.canFloat),
                view -> {
                    mActivity.hideAllPanels();
                    mActivity.setWorkspaceApp(app, task, !workspaceApp);
                });
        addAction(
                R.string.action_close_window,
                DesktopUiFactory.COLOR_AMBER,
                task != null,
                view -> mActivity.closeTask(app, task));
        addAction(
                R.string.action_force_stop,
                DesktopUiFactory.COLOR_RED,
                ShellAccess.isReady(),
                view -> mActivity.confirmForceStop(app));
        positionAndShow(x, y);
    }

    private void addAction(
            final int textResId,
            final int color,
            final boolean enabled,
            final View.OnClickListener listener) {
        final Button button = mUi.actionButton(textResId, color);
        button.setEnabled(enabled);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, 0);
        mPanel.addView(button, params);
    }

    private void positionAndShow(
            final float pointerX,
            final float pointerY) {
        final int width = getWidth();
        final int maxHeight = mActivity.getDesktopAreaHeight();
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(1, maxHeight - dp(16)),
                        View.MeasureSpec.AT_MOST));
        final int menuHeight = mPanel.getMeasuredHeight();
        final int areaLeft = mActivity.getDesktopAreaLeft();
        final int areaTop = mActivity.getDesktopAreaTop();
        final int areaRight =
                areaLeft + mActivity.getDesktopAreaWidth();
        final int areaBottom =
                areaTop + mActivity.getDesktopAreaHeight();
        int left = Math.round(pointerX) + dp(8);
        int top = Math.round(pointerY) + dp(8);
        if (left + width > areaRight - dp(8)) {
            left = Math.round(pointerX) - width - dp(8);
        }
        if (top + menuHeight > areaBottom - dp(8)) {
            top = Math.round(pointerY) - menuHeight - dp(8);
        }
        left = Math.max(
                areaLeft + dp(8),
                Math.min(left, areaRight - width - dp(8)));
        top = Math.max(
                areaTop + dp(8),
                Math.min(top, areaBottom - menuHeight - dp(8)));

        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null || !overlays.show(
                mPanel,
                left,
                top,
                width,
                menuHeight,
                false,
                "MagicDesk context menu")) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(
                            R.string.status_overlay_panel_unavailable));
        }
    }

    private int getWidth() {
        final int width =
                mActivity.getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(310), Math.max(dp(250), width - dp(24)));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
