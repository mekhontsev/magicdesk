package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetProviderInfo;
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

final class DesktopContextMenuController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Map<View, ContextTarget> mTargets = new WeakHashMap<>();

    private LinearLayout mPanel;
    private View mHoveredTargetView;

    DesktopContextMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout create() {
        final LinearLayout menu = FileItemContextMenu.createPanel(
                mActivity, mUi);
        menu.setVisibility(View.GONE);
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
        registerTarget(view, ContextTarget.app(app, task));
    }

    void registerDraggableFileTarget(final View view, final DesktopFile file) {
        if (view == null || file == null) {
            return;
        }
        registerTarget(view, ContextTarget.file(file), false);
    }

    void registerDraggableDesktopAppTarget(
            final View view,
            final AppItem app) {
        if (view == null || app == null) {
            return;
        }
        registerTarget(view, ContextTarget.desktopApp(app), false);
    }

    void registerWidgetTarget(
            final View view,
            final int appWidgetId,
            final String label,
            final boolean configurable,
            final int resizeMode) {
        if (view == null || appWidgetId < 0) {
            return;
        }
        registerTarget(
                view,
                ContextTarget.widget(
                        appWidgetId, label, configurable, resizeMode),
                true);
    }

    private void registerTarget(
            final View view,
            final ContextTarget contextTarget) {
        registerTarget(view, contextTarget, true);
    }

    private void registerTarget(
            final View view,
            final ContextTarget contextTarget,
            final boolean installLongClickListener) {
        mTargets.put(view, contextTarget);
        view.setHapticFeedbackEnabled(false);
        if (installLongClickListener) {
            view.setOnLongClickListener(target -> {
                mActivity.captureInteractionStackForPanel();
                showForView(target, contextTarget);
                return true;
            });
        }
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
            showTargetMenu(x, y, target);
            return;
        }
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.contains(x, y)) {
            return;
        }
        showDesktopMenu(x, y);
    }

    void showForRegisteredView(final View view) {
        final ContextTarget target = mTargets.get(view);
        if (target != null && view.isAttachedToWindow() && view.isShown()) {
            mActivity.captureInteractionStackForPanel();
            showForView(view, target);
        }
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
                R.string.action_new_file,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> mActivity.createDesktopFile(false));
        addAction(
                R.string.action_new_folder,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> mActivity.createDesktopFile(true));
        addAction(
                R.string.file_manager_paste,
                DesktopUiFactory.COLOR_PANEL_ALT,
                !FileManagerClipboard.snapshot().isEmpty(),
                view -> mActivity.pasteDesktopFiles());
        addAction(
                R.string.action_add_widget,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.addDesktopWidget());
        addAction(
                R.string.action_choose_wallpaper,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.chooseDesktopWallpaper());
        addAction(
                R.string.action_use_system_wallpaper,
                DesktopUiFactory.COLOR_PANEL_ALT,
                mActivity.isUsingCustomDesktopWallpaper(),
                view -> mActivity.useSystemDesktopWallpaper());
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
                R.string.action_open_desktop_folder,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openDesktopFolder());
        addAction(
                R.string.section_tools,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.showStartSection(
                        StartMenuController.MENU_TOOLS, false));
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
            final ContextTarget target) {
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        showTargetMenu(
                location[0] + view.getWidth() / 2f,
                location[1] + view.getHeight() / 2f,
                target);
    }

    private void showTargetMenu(
            final float x,
            final float y,
            final ContextTarget target) {
        if (target.app != null) {
            showAppMenu(
                    x, y, target.app, target.task, target.desktopItem);
        } else if (target.file != null) {
            showFileMenu(x, y, target.file);
        } else if (target.appWidgetId >= 0) {
            showWidgetMenu(x, y, target);
        }
    }

    private void showFileMenu(
            final float x,
            final float y,
            final DesktopFile file) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (mPanel == null || overlays == null) {
            return;
        }
        overlays.hide(mPanel);
        FileItemContextMenu.populate(
                mActivity,
                mUi,
                mPanel,
                FileItemContextMenu.Target.from(file),
                new FileItemContextMenu.Actions() {
                    @Override
                    public void open() {
                        mActivity.openDesktopFile(file);
                    }

                    @Override
                    public void openWith() {
                        mActivity.openDesktopFileWith(file);
                    }

                    @Override
                    public void install() {
                        mActivity.installDesktopApk(file);
                    }

                    @Override
                    public void runScript() {
                        mActivity.runDesktopScript(file);
                    }

                    @Override
                    public void setWallpaper() {
                        mActivity.setDesktopWallpaperFromFile(file);
                    }

                    @Override
                    public void createDesktopShortcut() {
                        // Desktop entries cannot create links to themselves.
                    }

                    @Override
                    public void copy() {
                        mActivity.copyDesktopFile(file, false);
                    }

                    @Override
                    public void cut() {
                        mActivity.copyDesktopFile(file, true);
                    }

                    @Override
                    public void rename() {
                        mActivity.renameDesktopFile(file);
                    }

                    @Override
                    public void delete() {
                        mActivity.confirmDeleteDesktopFile(file);
                    }

                    @Override
                    public void copyPath() {
                        mActivity.copyDesktopFilePath(file);
                    }

                    @Override
                    public void properties() {
                        mActivity.showDesktopFileProperties(file);
                    }
                },
                () -> overlays.hide(mPanel));
        positionAndShow(x, y);
    }

    private void showWidgetMenu(
            final float x,
            final float y,
            final ContextTarget target) {
        prepareMenuTitle(target.widgetLabel == null
                ? mActivity.getString(R.string.widget_default_name)
                : target.widgetLabel);
        addAction(R.string.action_widget_move,
                DesktopUiFactory.COLOR_CYAN, true,
                view -> mActivity.beginDesktopWidgetMove(
                        target.appWidgetId));
        addAction(R.string.action_widget_wider,
                DesktopUiFactory.COLOR_PANEL_ALT,
                (target.widgetResizeMode
                        & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0,
                view -> mActivity.resizeDesktopWidget(
                        target.appWidgetId, 1, 0));
        addAction(R.string.action_widget_narrower,
                DesktopUiFactory.COLOR_PANEL_ALT,
                (target.widgetResizeMode
                        & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0,
                view -> mActivity.resizeDesktopWidget(
                        target.appWidgetId, -1, 0));
        addAction(R.string.action_widget_taller,
                DesktopUiFactory.COLOR_PANEL_ALT,
                (target.widgetResizeMode
                        & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0,
                view -> mActivity.resizeDesktopWidget(
                        target.appWidgetId, 0, 1));
        addAction(R.string.action_widget_shorter,
                DesktopUiFactory.COLOR_PANEL_ALT,
                (target.widgetResizeMode
                        & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0,
                view -> mActivity.resizeDesktopWidget(
                        target.appWidgetId, 0, -1));
        addAction(R.string.action_widget_configure,
                DesktopUiFactory.COLOR_PANEL_ALT,
                target.widgetConfigurable,
                view -> mActivity.configureDesktopWidget(
                        target.appWidgetId));
        addAction(R.string.action_delete,
                DesktopUiFactory.COLOR_RED, true,
                view -> mActivity.removeDesktopWidget(
                        target.appWidgetId));
        positionAndShow(x, y);
    }

    private void prepareMenuTitle(final CharSequence text) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (mPanel == null || overlays == null) {
            return;
        }
        overlays.hide(mPanel);
        mPanel.removeAllViews();
        final TextView title = new TextView(mActivity);
        title.setText(text);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        mPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showAppMenu(
            final float x,
            final float y,
            final AppItem app,
            final TaskRepository.TaskEntry exactTask,
            final boolean desktopItem) {
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
                : mActivity.findFirstTask(app.launchTarget);
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
                        mActivity.launchWindowed(app);
                    });
            if (BuiltInDesktopAppCatalog.supportsMultipleWindows(
                    app.launchTarget)) {
                addAction(
                        R.string.action_new_window,
                        DesktopUiFactory.COLOR_PANEL_ALT,
                        true,
                        view -> {
                            mActivity.hideAllPanels();
                            mActivity.launchNewWindow(app);
                        });
            }
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

        if (BuiltInDesktopAppCatalog.isPinnable(app.launchTarget)) {
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
        }
        if (desktopItem) {
            addAction(
                    R.string.action_delete,
                    DesktopUiFactory.COLOR_RED,
                    true,
                    view -> mActivity.deleteDesktopShortcut(app));
        } else {
            final boolean desktopShortcut =
                    mActivity.isDesktopShortcut(app);
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
        }
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
