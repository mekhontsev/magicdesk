package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetProviderInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class DesktopContextMenuController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final AppShortcutRepository mShortcuts;
    private final Map<View, ContextTarget> mTargets = new WeakHashMap<>();
    private final DesktopMenuNavigator mMenuNavigator;

    private LinearLayout mPanel;
    private ScrollView mMenuRoot;
    private View mHoveredTargetView;
    private boolean mRetainOwnerPanel;

    DesktopContextMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mShortcuts = new AppShortcutRepository(activity);
        mMenuNavigator = new DesktopMenuNavigator(activity::hideTopPanel);
    }

    View create() {
        final LinearLayout menu = FileItemContextMenu.createPanel(
                mActivity, mUi);
        menu.setBackground(null);
        mPanel = menu;
        mMenuRoot = new ScrollView(mActivity);
        mMenuRoot.setFillViewport(false);
        mMenuRoot.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(8),
                DesktopUiFactory.COLOR_CYAN));
        mMenuRoot.addView(menu, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mMenuRoot.setVisibility(View.GONE);
        mActivity.registerAutomationUiElement(
                mMenuRoot,
                "panel.context_menu",
                "menu",
                "Context menu");
        return mMenuRoot;
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

    void registerFileTarget(final View view, final DesktopFile file) {
        if (view == null || file == null) {
            return;
        }
        registerTarget(view, ContextTarget.file(file));
    }

    void registerDraggableDesktopAppTarget(
            final View view,
            final AppItem app,
            final DesktopFile file) {
        if (view == null || app == null || file == null) {
            return;
        }
        registerTarget(view, ContextTarget.desktopApp(app, file), false);
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
        final TaskbarController.ContextArea taskbarArea =
                mActivity.taskbar().contextAreaAt(x, y);
        if (taskbarArea == TaskbarController.ContextArea.START) {
            showStartButtonMenu(x, y);
            return;
        }
        if (taskbarArea == TaskbarController.ContextArea.BLANK) {
            showTaskbarMenu(x, y);
            return;
        }
        final OverlayPanelController overlays = mActivity.overlayPanels();
        final boolean insidePanel = overlays != null && overlays.contains(x, y);
        ContextTarget target = findHoveredTarget(x, y, insidePanel);
        if (target == null) {
            target = findTargetAt(x, y, insidePanel);
        }
        if (target != null) {
            showTargetMenu(x, y, target, insidePanel);
            return;
        }
        if (taskbarArea == TaskbarController.ContextArea.ACTION) {
            return;
        }
        if (insidePanel) {
            return;
        }
        showDesktopMenu(x, y);
    }

    void showStartButtonMenu(final float x, final float y) {
        mRetainOwnerPanel = false;
        prepareMenuTitle(mActivity.getString(R.string.action_start));
        addAction(
                R.string.section_apps,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> mActivity.showStartSection(
                        StartMenuController.MENU_APPS, false));
        addAction(
                R.string.file_manager_title,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openFiles());
        addAction(
                R.string.console_title,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openConsole());
        if (TermuxIntegration.isInstalled(mActivity)) {
            addAction(
                    R.string.console_termux_title,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.openTermuxConsole());
        }
        addAction(
                R.string.task_manager_title,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openTaskManager());
        addAction(
                R.string.settings_title,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openSettings());
        addAction(
                R.string.action_close_desktop,
                DesktopUiFactory.COLOR_AMBER,
                true,
                view -> mActivity.closeDesktop());
        positionAndShow(x, y);
    }

    void showTaskbarMenu(final float x, final float y) {
        mRetainOwnerPanel = false;
        prepareMenuTitle(mActivity.getString(R.string.context_taskbar));
        addAction(
                R.string.action_show_desktop,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> mActivity.toggleDesktopWorkspace());
        addAction(
                R.string.task_manager_title,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openTaskManager());
        addAction(
                mActivity.isTaskbarAutoHideEnabled()
                        ? R.string.action_keep_taskbar_visible
                        : R.string.settings_taskbar_auto_hide,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.toggleTaskbarAutoHide());
        addAction(
                R.string.action_taskbar_settings,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openSettings());
        positionAndShow(x, y);
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
        mRetainOwnerPanel = false;
        overlays.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);

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
                R.string.action_new_terminal_application,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> mActivity.createCommandApplication());
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

    private ContextTarget findHoveredTarget(
            final float x,
            final float y,
            final boolean insidePanel) {
        final View view = mHoveredTargetView;
        if (!isEligibleTarget(view, x, y, insidePanel)) {
            mHoveredTargetView = null;
            return null;
        }
        return mTargets.get(view);
    }

    private ContextTarget findTargetAt(
            final float x,
            final float y,
            final boolean insidePanel) {
        for (final Map.Entry<View, ContextTarget> entry : mTargets.entrySet()) {
            final View view = entry.getKey();
            if (isEligibleTarget(view, x, y, insidePanel)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isEligibleTarget(
            final View view,
            final float x,
            final float y,
            final boolean insidePanel) {
        if (view == null
                || !view.isAttachedToWindow()
                || !view.isShown()
                || !mActivity.isPointInside(view, x, y)) {
            return false;
        }
        final OverlayPanelController overlays = mActivity.overlayPanels();
        return !insidePanel
                || (overlays != null
                        && overlays.containsVisiblePanelView(view));
    }

    private void showForView(
            final View view,
            final ContextTarget target) {
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        final OverlayPanelController overlays = mActivity.overlayPanels();
        showTargetMenu(
                location[0] + view.getWidth() / 2f,
                location[1] + view.getHeight() / 2f,
                target,
                overlays != null && overlays.containsVisiblePanelView(view));
    }

    private void showTargetMenu(
            final float x,
            final float y,
            final ContextTarget target,
            final boolean retainOwnerPanel) {
        mRetainOwnerPanel = retainOwnerPanel;
        if (target.app != null) {
            showAppMenu(
                    x, y, target.app, target.task, target.file);
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
        overlays.hide(mMenuRoot);
        mMenuNavigator.prepare(null);
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
                    public void createTerminalApplication() {
                        mActivity.createCommandApplication(file);
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
                mActivity::hideAllPanels);
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
        overlays.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);
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
            final DesktopFile desktopFile) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (mPanel == null || overlays == null) {
            return;
        }
        final TaskRepository.TaskEntry task = exactTask != null
                ? exactTask
                : mActivity.findFirstTask(app.launchTarget);
        showAppMenu(new AppMenuState(
                x,
                y,
                app,
                task,
                desktopFile,
                mShortcuts.load(app)));
    }

    private void showAppMenu(final AppMenuState state) {
        prepareAppMenuTitle(state.app, state.task);

        addAction(
                state.task == null
                        ? R.string.action_open
                        : R.string.action_switch_to,
                DesktopUiFactory.COLOR_CYAN,
                true,
                view -> {
                    if (state.task == null) {
                        mActivity.launchDefault(state.app);
                    } else {
                        mActivity.focusTask(state.app, state.task);
                    }
                });
        if (!state.shortcuts.isEmpty()) {
            addSubmenuAction(
                    R.string.action_app_actions,
                    view -> showAppActionsMenu(state));
        }
        addSubmenuAction(
                R.string.action_window,
                view -> showWindowMenu(state));

        if (BuiltInDesktopAppCatalog.isPinnable(state.app.launchTarget)) {
            final boolean pinned = mActivity.getPinnedPackages()
                    .contains(state.app.packageName);
            addAction(
                    pinned ? R.string.action_unpin : R.string.action_pin,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.togglePinned(state.app));
        }
        if (state.desktopFile != null) {
            addAction(
                    R.string.action_rename,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.renameDesktopFile(state.desktopFile));
            addAction(
                    R.string.action_delete,
                    DesktopUiFactory.COLOR_RED,
                    true,
                    view -> mActivity.confirmDeleteDesktopFile(
                            state.desktopFile));
        } else {
            final boolean desktopShortcut =
                    mActivity.isDesktopShortcut(state.app);
            addAction(
                    desktopShortcut
                            ? R.string.action_remove_from_desktop
                            : R.string.action_add_to_desktop,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.toggleDesktopShortcut(state.app));
        }
        if (mActivity.hasDesktopWidgets(state.app.packageName)) {
            addAction(
                    R.string.action_app_widgets,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.addDesktopWidgets(
                            state.app.packageName));
        }
        addAction(
                R.string.action_app_info,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openAppInfo(state.app));
        addAction(
                R.string.action_close_window,
                DesktopUiFactory.COLOR_AMBER,
                state.task != null,
                view -> mActivity.closeTask(state.app, state.task));
        addAction(
                R.string.action_force_stop,
                DesktopUiFactory.COLOR_RED,
                ShellAccess.isReady(),
                view -> mActivity.confirmForceStop(state.app));
        positionAndShow(state.x, state.y);
    }

    private void showAppActionsMenu(final AppMenuState state) {
        prepareSubmenuTitle(
                mActivity.getString(
                        R.string.context_app_actions_title,
                        state.app.label),
                view -> showAppMenu(state));
        addSubmenuAction(
                R.string.action_add_app_action_to_desktop,
                view -> showAddAppActionMenu(state));
        for (final AppShortcutAction shortcut : state.shortcuts) {
            addAction(
                    shortcut.label,
                    shortcut.icon,
                    DesktopUiFactory.COLOR_CYAN,
                    true,
                    view -> mActivity.launchShortcut(state.app, shortcut));
        }
        positionAndShow(state.x, state.y);
    }

    private void showAddAppActionMenu(final AppMenuState state) {
        prepareSubmenuTitle(
                mActivity.getString(
                        R.string.context_add_app_action_title,
                        state.app.label),
                view -> showAppActionsMenu(state));
        for (final AppShortcutAction shortcut : state.shortcuts) {
            addAction(
                    shortcut.label,
                    shortcut.icon,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.addDesktopShortcut(
                            state.app, shortcut));
        }
        positionAndShow(state.x, state.y);
    }

    private void showWindowMenu(final AppMenuState state) {
        prepareSubmenuTitle(
                mActivity.getString(
                        R.string.context_window_title,
                        state.app.label),
                view -> showAppMenu(state));

        final boolean windowControl = ShellAccess.isReady();
        if (state.app.canFloat && windowControl) {
            addAction(
                    R.string.action_open_floating,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.launchWindowed(state.app));
            if (BuiltInDesktopAppCatalog.supportsMultipleWindows(
                    state.app.launchTarget)) {
                addAction(
                        R.string.action_new_window,
                        DesktopUiFactory.COLOR_PANEL_ALT,
                        true,
                        view -> mActivity.launchNewWindow(state.app));
            }
        }
        addAction(
                R.string.action_open_fullscreen,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> {
                    if (state.task == null) {
                        mActivity.launchFullscreen(state.app);
                    } else {
                        mActivity.openTaskFullscreen(
                                state.app, state.task);
                    }
                });
        final int otherDisplayId = mActivity.getOtherDisplayId(state.task);
        if (state.task != null && otherDisplayId >= 0) {
            addAction(
                    otherDisplayId == 0
                            ? R.string.action_send_to_phone
                            : R.string.action_send_to_external_display,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    ShellAccess.isReady(),
                    view -> mActivity.moveTaskToOtherDisplay(
                            state.app, state.task));
        }
        positionAndShow(state.x, state.y);
    }

    private void prepareAppMenuTitle(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        overlays.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);

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
    }

    private void prepareSubmenuTitle(
            final CharSequence text,
            final View.OnClickListener backListener) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        overlays.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(() -> backListener.onClick(mMenuRoot));

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final ImageButton back = mUi.taskbarIconButton(
                R.drawable.ic_file_back,
                R.string.action_back,
                true);
        back.setOnClickListener(backListener);
        mActivity.registerAutomationUiElement(
                back,
                "context.action.back",
                "menu_item",
                mActivity.getString(R.string.action_back));
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        final TextView title = new TextView(mActivity);
        title.setText(text);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f);
        titleParams.setMargins(dp(8), 0, 0, 0);
        header.addView(title, titleParams);
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button addAction(
            final int textResId,
            final int color,
            final boolean enabled,
            final View.OnClickListener listener) {
        final Button button = addAction(
                mActivity.getString(textResId),
                null,
                color,
                enabled,
                listener);
        mActivity.registerAutomationUiElement(
                button,
                "context.action."
                        + mActivity.getResources()
                                .getResourceEntryName(textResId),
                "menu_item",
                button.getText());
        return button;
    }

    private Button addAction(
            final String text,
            final Drawable icon,
            final int color,
            final boolean enabled,
            final View.OnClickListener listener) {
        return addMenuItem(
                text, icon, color, enabled, true, false, listener);
    }

    private Button addMenuItem(
            final String text,
            final Drawable icon,
            final int color,
            final boolean enabled,
            final boolean dismissBeforeAction,
            final boolean submenu,
            final View.OnClickListener listener) {
        final Button button = mUi.actionButton(text, color);
        button.setEnabled(enabled);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (icon != null) {
            final Drawable menuIcon = icon.mutate();
            final int size = dp(20);
            menuIcon.setBounds(0, 0, size, size);
            button.setCompoundDrawables(menuIcon, null, null, null);
            button.setCompoundDrawablePadding(dp(10));
        }
        button.setOnClickListener(view -> {
            if (dismissBeforeAction) {
                mActivity.hideAllPanels();
            }
            listener.onClick(view);
        });
        mActivity.registerAutomationUiElement(
                button,
                "context.action."
                        + DesktopAutomationUiRegistry.segment(text),
                "menu_item",
                text);
        mMenuNavigator.prefer(button);
        if (submenu) {
            mMenuNavigator.markSubmenu(button);
        }
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, 0);
        mPanel.addView(button, params);
        return button;
    }

    private void addSubmenuAction(
            final int textResId,
            final View.OnClickListener listener) {
        final Button button = addMenuItem(
                mActivity.getString(textResId),
                null,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                false,
                true,
                listener);
        mActivity.registerAutomationUiElement(
                button,
                "context.action."
                        + mActivity.getResources()
                                .getResourceEntryName(textResId),
                "menu_item",
                button.getText());
        final Drawable arrow = mActivity.getDrawable(
                R.drawable.ic_file_forward).mutate();
        final int size = dp(20);
        arrow.setBounds(0, 0, size, size);
        button.setCompoundDrawables(null, null, arrow, null);
    }

    private void positionAndShow(
            final float pointerX,
            final float pointerY) {
        final Rect workArea = mActivity.getDesktopViewport()
                .workAreaBounds(mActivity.getTaskbarHeight());
        final int width = getWidth(workArea.width());
        final int maxHeight = workArea.height();
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        0, View.MeasureSpec.UNSPECIFIED));
        final int menuHeight = Math.min(
                mPanel.getMeasuredHeight(),
                Math.max(1, maxHeight - dp(16)));
        final int areaLeft = workArea.left;
        final int areaTop = workArea.top;
        final int areaRight = workArea.right;
        final int areaBottom = workArea.bottom;
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
        mMenuRoot.scrollTo(0, 0);
        final boolean shown = overlays != null
                && (mRetainOwnerPanel
                        ? overlays.showChild(
                                mMenuRoot,
                                left,
                                top,
                                width,
                                menuHeight,
                                "MagicDesk context menu",
                                mActivity::handleSecondaryClick)
                        : overlays.show(
                                mMenuRoot,
                                left,
                                top,
                                width,
                                menuHeight,
                                true,
                                false,
                                "MagicDesk context menu"));
        if (!shown) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(
                            R.string.status_overlay_panel_unavailable));
        } else if (overlays.isTopPanelFocusable()) {
            mMenuNavigator.activate(mMenuRoot);
        }
    }

    private int getWidth(final int availableWidth) {
        return Math.min(dp(310), Math.max(1, availableWidth - dp(16)));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private static final class AppMenuState {
        final float x;
        final float y;
        final AppItem app;
        final TaskRepository.TaskEntry task;
        final DesktopFile desktopFile;
        final List<AppShortcutAction> shortcuts;

        AppMenuState(
                final float x,
                final float y,
                final AppItem app,
                final TaskRepository.TaskEntry task,
                final DesktopFile desktopFile,
                final List<AppShortcutAction> shortcuts) {
            this.x = x;
            this.y = y;
            this.app = app;
            this.task = task;
            this.desktopFile = desktopFile;
            this.shortcuts = shortcuts;
        }
    }
}
