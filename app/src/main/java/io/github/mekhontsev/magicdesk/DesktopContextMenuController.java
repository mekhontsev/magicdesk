package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetProviderInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.GridLayout;
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
    private ContentRequestScope mShortcutRequest;

    private LinearLayout mPanel;
    private ScrollView mMenuRoot;
    private View mHoveredTargetView;
    private boolean mRetainOwnerPanel;
    private boolean mRequestKeyboardFocus = true;

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
        mMenuRoot.setBackground(mUi.menuSurface());
        mMenuRoot.addView(menu, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mMenuRoot.setVisibility(View.GONE);
        mMenuRoot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(final View view) {
            }

            @Override
            public void onViewDetachedFromWindow(final View view) {
                cancelShortcutRequest();
            }
        });
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
        view.setOnContextClickListener(target -> {
            mActivity.captureInteractionStackForPanel();
            showForView(target, contextTarget, false);
            return true;
        });
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
        mRequestKeyboardFocus = false;
        final TaskbarController.ContextArea taskbarArea =
                mActivity.taskbar().contextAreaAt(x, y);
        if (taskbarArea == TaskbarController.ContextArea.START) {
            populateStartButtonMenu(x, y);
            return;
        }
        if (taskbarArea == TaskbarController.ContextArea.BLANK) {
            populateTaskbarMenu(x, y);
            return;
        }
        final DesktopPanelWindowController panels = mActivity.panels();
        final boolean insidePanel = panels != null && panels.contains(x, y);
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
        populateDesktopMenu(x, y);
    }

    void showStartButtonMenu(final float x, final float y) {
        mRequestKeyboardFocus = true;
        populateStartButtonMenu(x, y);
    }

    private void populateStartButtonMenu(final float x, final float y) {
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
if (RuntimeLimits.active().termux() && TermuxIntegration.isInstalled(mActivity)) {
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
        mRequestKeyboardFocus = true;
        populateTaskbarMenu(x, y);
    }

    private void populateTaskbarMenu(final float x, final float y) {
        mActivity.captureInteractionStackForPanel();
        final TaskRepository.TaskEntry activeTask = mActivity.interactionActiveTask();
        mRetainOwnerPanel = false;
        prepareMenuTitle(mActivity.getString(R.string.context_taskbar));
        addAction(R.string.action_back_to_application, DesktopUiFactory.COLOR_PANEL_ALT,
                activeTask != null, view -> mActivity.backToTask(activeTask));
        addAction(R.string.display_switch_menu, DesktopUiFactory.COLOR_PANEL_ALT,
                ShellAccess.isReady(), view -> DisplaySwitchController.show(mActivity));
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
        final MagicDeskSettings.Values settings = MagicDeskSettings.load();
        addCheckableAction(R.string.settings_taskbar_auto_hide,
                settings.taskbarAutoHide, mActivity::setTaskbarAutoHide);
        addCheckableAction(R.string.settings_keyboard_on_app_display,
                settings.keyboardOnAppDisplay, mActivity::setKeyboardOnAppDisplay);
        addAction(
                R.string.action_settings,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> mActivity.openSettings());
        // Do not force focus for pointer-opened menus: after dismissal Android can
        // retain the removed child as its input target and report a no-focused-window ANR.
        // Keyboard entry points still request focus explicitly; IME targeting stays excluded.
        positionAndShow(x, y);
    }

    void showForRegisteredView(final View view) {
        mRequestKeyboardFocus = true;
        final ContextTarget target = mTargets.get(view);
        if (target != null && view.isAttachedToWindow() && view.isShown()) {
            mActivity.captureInteractionStackForPanel();
            showForView(view, target);
        }
    }

    void showDesktopMenu(final float x, final float y) {
        mRequestKeyboardFocus = true;
        populateDesktopMenu(x, y);
    }

    private void populateDesktopMenu(final float x, final float y) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (mPanel == null || panels == null) {
            return;
        }
        mRetainOwnerPanel = false;
        cancelShortcutRequest();
        panels.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);

        final TextView title = mUi.menuHeader(
                mActivity.getString(R.string.context_desktop),
                TextUtils.TruncateAt.END);
        mPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
        final boolean hasClipboardContent =
                FileClipboardInterop.canPaste(mActivity);
        addAction(
                R.string.file_manager_paste,
                DesktopUiFactory.COLOR_PANEL_ALT,
                hasClipboardContent,
                view -> mActivity.pasteDesktopFiles());
        addAction(
                R.string.action_open_clipboard_content,
                DesktopUiFactory.COLOR_PANEL_ALT,
                hasClipboardContent,
                view -> mActivity.openClipboardContent());
        addAction(
                R.string.action_share_clipboard_content,
                DesktopUiFactory.COLOR_PANEL_ALT,
                hasClipboardContent,
                view -> mActivity.shareClipboardContent());
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
                R.string.action_use_default_wallpaper,
                DesktopUiFactory.COLOR_PANEL_ALT,
                mActivity.isUsingCustomDesktopWallpaper(),
                view -> mActivity.useDefaultDesktopWallpaper());
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
        final DesktopPanelWindowController panels = mActivity.panels();
        return !insidePanel
                || (panels != null
                        && panels.containsVisiblePanelView(view));
    }

    private void showForView(
            final View view,
            final ContextTarget target) {
        showForView(view, target, true);
    }

    private void showForView(final View view, final ContextTarget target, final boolean keyboardFocus) {
        mRequestKeyboardFocus = keyboardFocus;
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        final DesktopPanelWindowController panels = mActivity.panels();
        showTargetMenu(
                location[0] + view.getWidth() / 2f,
                location[1] + view.getHeight() / 2f,
                target,
                panels != null && panels.containsVisiblePanelView(view));
    }

    void registerStartTarget(View view, AppItem app,
            java.util.function.Supplier<StartDisplaySelector.Target> destination,
            java.util.function.Supplier<DesktopLaunchPresentation> presentation) {
        registerTarget(view, ContextTarget.startApp(app, destination, presentation));
    }

    private void showTargetMenu(
            final float x,
            final float y,
            final ContextTarget target,
            final boolean retainOwnerPanel) {
        mRetainOwnerPanel = retainOwnerPanel;
        if (target.app != null) {
            showAppMenu(
                    x, y, target.app, target.task, target.file,
                    target.launchDestination == null ? null : target.launchDestination.get(),
                    target.launchPresentation == null ? DesktopLaunchPresentation.automatic() : target.launchPresentation.get());
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
        final DesktopPanelWindowController panels = mActivity.panels();
        if (mPanel == null || panels == null) {
            return;
        }
        cancelShortcutRequest();
        panels.hide(mMenuRoot);
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
                    public void share() {
                        mActivity.shareDesktopFile(file);
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
        final DesktopPanelWindowController panels = mActivity.panels();
        if (mPanel == null || panels == null) {
            return;
        }
        cancelShortcutRequest();
        panels.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);
        final TextView title = mUi.menuHeader(
                text, TextUtils.TruncateAt.END);
        mPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showAppMenu(
            final float x,
            final float y,
            final AppItem app,
            final TaskRepository.TaskEntry exactTask,
            final DesktopFile desktopFile,
            final StartDisplaySelector.Target destination,
            final DesktopLaunchPresentation presentation) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (mPanel == null || panels == null) {
            return;
        }
        final TaskRepository.TaskEntry task = exactTask != null
                ? exactTask
                : destination == null ? mActivity.findFirstTask(app) : null;
        showAppMenu(new AppMenuState(
                x,
                y,
                app,
                task,
                desktopFile,
                List.of(),
                false, destination, presentation));
        if (!panels.isRequested(mMenuRoot)) {
            return;
        }
        final ContentRequestScope request = AndroidDesktopActionDispatcher.createContentScope();
        mShortcutRequest = request;
        request.submit(cancelled -> {
            final List<AppShortcutAction> shortcuts = mShortcuts.load(app);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            final boolean hasWidgets = mActivity.hasDesktopWidgets(app.packageName);
            return new AppMenuState(x, y, app, task, desktopFile,
                    shortcuts, hasWidgets, destination, presentation);
        }, null).thenAccept(completion -> request.deliver(mActivity::runOnUiThread, () -> {
            if (request != mShortcutRequest) {
                return;
            }
            cancelShortcutRequest();
            if (mActivity.isActivityUnavailable() || !panels.isRequested(mMenuRoot)) {
                return;
            }
            if (completion.failure != null) {
                Log.w("MagicDesk", "Cannot load application menu actions", completion.failure);
            } else if (completion.value != null) {
                showAppMenu(completion.value);
            }
        }));
    }

    private void cancelShortcutRequest() {
        final ContentRequestScope request = mShortcutRequest;
        mShortcutRequest = null;
        if (request != null) {
            request.close();
        }
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
                        launchApp(state, state.presentation);
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
            final boolean pinned = mActivity.getPinnedApps()
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
        if (state.hasWidgets) {
            addAction(
                    R.string.action_app_widgets,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.addDesktopWidgets(
                            state.app.packageName));
        }
        if (!BuildConfig.APPLICATION_ID.equals(state.app.packageName)) {
            addAction(
                    R.string.action_app_presentation_settings,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> mActivity.openApplicationSettings(
                            state.app.identity));
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
        final var forceClose = BuiltInWindowRegistry.forceCloseAction(state.task);
        addAction(
                forceClose == null ? R.string.action_force_stop : forceClose.label(),
                DesktopUiFactory.COLOR_RED,
                forceClose != null
                        || (ShellAccess.isReady() && !BuildConfig.APPLICATION_ID.equals(state.app.packageName)),
                view -> mActivity.confirmForceStop(state.app, state.task));
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
                    view -> {
                        if (state.destination == null) {
                            mActivity.launchShortcut(state.app, shortcut);
                        } else {
                            StartEntryLauncher.request(mActivity, new DesktopLaunchRequest(
                                    shortcut.label, state.app.packageName, null,
                                    new AndroidShortcutSpec(state.app.identity, state.app.launchTarget, shortcut.id),
                                    null, DesktopLaunchPresentation.forMode(state.presentation.mode), DesktopLaunchArguments.empty(), ""),
                                    state.destination, () -> !mActivity.isActivityUnavailable(),
                                    mActivity::hideAllPanels, this::launchFailed);
                        }
                    });
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

        final boolean windowControl = ShellAccess.isReady()
                && (state.destination == null || DesktopRuntimeBridge.hasWorkspace(state.destination.displayId()));
        if (state.task != null && windowControl) {
            addAction(R.string.action_restore_window, DesktopUiFactory.COLOR_PANEL_ALT,
                    true, view -> mActivity.arrangeTask(state.task, DesktopTaskController.SHORTCUT_RESTORE));
            addSubmenuAction(R.string.action_arrange_window, view -> showArrangeMenu(state));
        }
        if (state.app.canFloat && windowControl) {
            addAction(
                    R.string.action_open_floating,
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    true,
                    view -> launchApp(state, DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED)));
            if (BuiltInDesktopAppCatalog.supportsMultipleWindows(
                    state.app.launchTarget)) {
                addAction(
                        R.string.action_new_window,
                        DesktopUiFactory.COLOR_PANEL_ALT,
                        true,
                        view -> launchApp(state, DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED)
                                .withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW)));
            }
        }
        addAction(
                R.string.action_open_fullscreen,
                DesktopUiFactory.COLOR_PANEL_ALT,
                true,
                view -> {
                    if (state.task == null) {
                        launchApp(state, DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN));
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

    private void showArrangeMenu(final AppMenuState state) {
        prepareSubmenuTitle(mActivity.getString(R.string.action_arrange_window),
                view -> showWindowMenu(state));
        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(2);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_LEFT,
                R.drawable.ic_snap_left, R.string.arrange_left);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_RIGHT,
                R.drawable.ic_snap_right, R.string.arrange_right);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_TOP_LEFT,
                R.drawable.ic_snap_top_left, R.string.arrange_top_left);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_TOP_RIGHT,
                R.drawable.ic_snap_top_right, R.string.arrange_top_right);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_BOTTOM_LEFT,
                R.drawable.ic_snap_bottom_left, R.string.arrange_bottom_left);
        addArrangement(grid, state, DesktopTaskController.SHORTCUT_SNAP_BOTTOM_RIGHT,
                R.drawable.ic_snap_bottom_right, R.string.arrange_bottom_right);
        mPanel.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        positionAndShow(state.x, state.y);
    }

    private void addArrangement(final GridLayout grid, final AppMenuState state,
            final int arrangement, final int icon, final int label) {
        final ImageButton button = mUi.menuIconButton(icon, label);
        button.setTooltipText(mActivity.getString(label));
        button.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            mActivity.arrangeTask(state.task, arrangement);
        });
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.width = 0;
        params.height = dp(56);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        grid.addView(button, params);
        mMenuNavigator.prefer(button);
        mActivity.registerAutomationUiElement(button,
                "context.arrange." + mActivity.getResources().getResourceEntryName(label),
                "button", mActivity.getString(label));
    }

    private void launchApp(AppMenuState state, DesktopLaunchPresentation presentation) {
        if (state.destination == null) {
            if (presentation.mode == DesktopLaunchMode.AUTO) { mActivity.launchDefault(state.app); }
            else if (presentation.mode == DesktopLaunchMode.FULLSCREEN) { mActivity.launchFullscreen(state.app); }
            else if (presentation.instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW) {
                mActivity.launchNewWindow(state.app);
            } else { mActivity.launchWindowed(state.app); }
            return;
        }
        final StartDisplaySelector.Target destination = presentation.mode == DesktopLaunchMode.WINDOWED
                ? new StartDisplaySelector.Target(state.destination.displayId(), state.destination.uniqueId(), "desktop")
                : state.destination;
        DisplayAppLauncher.launch(mActivity, state.app, StartEntryLauncher.placement(destination), destination.uniqueId(),
                presentation, () -> !mActivity.isActivityUnavailable(),
                mActivity::hideAllPanels, this::launchFailed);
    }

    private void launchFailed(Throwable error) {
        android.widget.Toast.makeText(mActivity, ShellAccess.usefulMessage(error),
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void prepareAppMenuTitle(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final DesktopPanelWindowController panels = mActivity.panels();
        cancelShortcutRequest();
        panels.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(null);

        final TextView title = mUi.menuHeader(
                app.label, TextUtils.TruncateAt.END);
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
            taskInfo.setPadding(dp(10), 0, dp(10), dp(4));
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
        final DesktopPanelWindowController panels = mActivity.panels();
        cancelShortcutRequest();
        panels.hide(mMenuRoot);
        mPanel.removeAllViews();
        mMenuNavigator.prepare(() -> backListener.onClick(mMenuRoot));

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final ImageButton back = mUi.menuIconButton(
                R.drawable.ic_file_back,
                R.string.action_back);
        back.setOnClickListener(backListener);
        mActivity.registerAutomationUiElement(
                back,
                "context.action.back",
                "menu_item",
                mActivity.getString(R.string.action_back));
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        final TextView title = mUi.menuHeader(
                text, TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f);
        header.addView(title, titleParams);
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                mUi.menuItemHeight()));
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

    private void addCheckableAction(final int textResId, final boolean checked,
            final java.util.function.BiConsumer<Boolean, Runnable> action) {
        final android.widget.CheckBox button = mUi.menuCheckBox(
                mActivity.getString(textResId), checked);
        addMenuItem(button, null, true, false, false, view -> {
            button.setEnabled(false);
            // Returning editor focus must follow application of the new IME policy.
            action.accept(button.isChecked(), () -> {
                button.setEnabled(true);
                final DesktopPanelWindowController panels = mActivity.panels();
                if (button.isShown() && button.getParent() == mPanel && panels != null) {
                    panels.hide(mMenuRoot);
                }
            });
        });
        mActivity.registerAutomationUiElement(button,
                "context.action." + mActivity.getResources().getResourceEntryName(textResId),
                "checkbox", button.getText());
    }

    private Button addMenuItem(
            final String text,
            final Drawable icon,
            final int color,
            final boolean enabled,
            final boolean dismissBeforeAction,
            final boolean submenu,
            final View.OnClickListener listener) {
        final Button button = mUi.menuItem(text, color);
        return addMenuItem(button, icon, enabled, dismissBeforeAction, submenu, listener);
    }

    private Button addMenuItem(final Button button, final Drawable icon,
            final boolean enabled, final boolean dismissBeforeAction,
            final boolean submenu, final View.OnClickListener listener) {
        final String text = button.getText().toString();
        button.setEnabled(enabled);
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
                        mUi.menuItemHeight());
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
        final Rect workArea = mActivity.getDesktopPanelAreaBounds();
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
        final ShellPanelPlacement placement = ShellPanelPlacement.atPointer(
                Math.round(pointerX), Math.round(pointerY), width, menuHeight, dp(8));

        final DesktopPanelWindowController panels = mActivity.panels();
        mMenuRoot.scrollTo(0, 0);
        final boolean shown = panels != null
                && (mRetainOwnerPanel
                        ? panels.showChild(
                                mMenuRoot,
                                placement,
                                "MagicDesk context menu",
                                mActivity::handleSecondaryClick)
                        : panels.show(
                                mMenuRoot,
                                placement,
                                mRequestKeyboardFocus,
                                false,
                                "MagicDesk context menu"));
        if (!shown) {
            mActivity.setErrorStatus(
                    "PANEL-001",
                    mActivity.getString(
                            R.string.status_desktop_panel_unavailable));
        } else if (panels.isTopPanelFocusable()) {
            mMenuNavigator.activate(mMenuRoot);
        }
    }

    private int getWidth(final int availableWidth) {
        return mUi.menuWidth(availableWidth, dp(8));
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
        final boolean hasWidgets;
        final StartDisplaySelector.Target destination;
        final DesktopLaunchPresentation presentation;

        AppMenuState(
                final float x,
                final float y,
                final AppItem app,
                final TaskRepository.TaskEntry task,
                final DesktopFile desktopFile,
                final List<AppShortcutAction> shortcuts,
                final boolean hasWidgets, final StartDisplaySelector.Target destination,
                final DesktopLaunchPresentation presentation) {
            this.x = x;
            this.y = y;
            this.app = app;
            this.task = task;
            this.desktopFile = desktopFile;
            this.shortcuts = shortcuts;
            this.hasWidgets = hasWidgets;
            this.destination = destination;
            this.presentation = presentation;
        }
    }
}
