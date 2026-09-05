package io.github.mekhontsev.magicdesk;

import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;

/** Owns the desktop popup, not the shared Start contents or phone HOME. */
final class StartMenuController implements StartMenuContent.Host {
    static final int MENU_RECENT = StartMenuContent.MENU_RECENT;
    static final int MENU_APPS = StartMenuContent.MENU_APPS;
    static final int MENU_TOOLS = StartMenuContent.MENU_TOOLS;
    static final int MENU_CAPTURE = StartMenuContent.MENU_CAPTURE;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final StartMenuContent mContent;
    private LinearLayout mPanel;

    StartMenuController(final DesktopShellActivity activity, final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mContent = new StartMenuContent(activity, ui, StartMenuScope.DESKTOP, this);
    }

    LinearLayout create() {
        mPanel = mContent.create();
        mPanel.setVisibility(View.GONE);
        return mPanel;
    }

    void render() { mContent.render(); }
    void release() { mContent.release(); }
    boolean ownsPanel(final View panel) { return panel != null && panel == mPanel; }

    boolean isVisible() {
        return mActivity.panels() != null && mActivity.panels().isVisible(mPanel);
    }

    boolean isToolsVisible() {
        return requested() && mContent.isUtilityVisible();
    }

    private boolean requested() {
        return mActivity.panels() != null && mActivity.panels().isRequested(mPanel);
    }

    void toggle() { setVisible(!requested()); }
    void toggleTools() {
        if (isToolsVisible()) {
            setVisible(false);
        } else {
            showSection(MENU_TOOLS, false);
        }
    }

    void showCapture() { mContent.showSection(MENU_CAPTURE); }
    void showSection(final int mode) { showSection(mode, true); }
    void showSection(final int mode, final boolean focusable) {
        mContent.showSection(mode);
        setVisible(true, focusable);
    }

    void setVisible(final boolean visible) { setVisible(visible, true); }
    void setVisible(final boolean visible, final boolean focusable) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null || mPanel == null) {
            return;
        }
        if (!visible) {
            mContent.pause();
            panels.hide(mPanel);
            return;
        }
        mContent.prepare(focusable);
        final int width = getWidth();
        final int height = getHeight();
        final int left = mActivity.getDesktopAreaLeft() + mUi.desktopDp(
                16, 6, mActivity.isCompactDesktopPreview());
        final int top = mActivity.getDesktopAreaTop() + Math.max(
                0, mActivity.getDesktopAreaHeight() - mActivity.getTaskbarHeight() - height);
        if (!panels.show(mPanel, left, top, width, height, focusable,
                mActivity.getCurrentDisplayId() == Display.DEFAULT_DISPLAY,
                "MagicDesk Start")) {
            mActivity.setErrorStatus(
                    "PANEL-001", mActivity.getString(R.string.status_desktop_panel_unavailable));
            return;
        }
        if (mPanel.hasWindowFocus()) {
            mContent.focusSearch();
        }
    }

    private int getWidth() {
        final int margin = mUi.desktopDp(16, 6, mActivity.isCompactDesktopPreview());
        return Math.min(mUi.dp(560), Math.max(
                1, mActivity.getDesktopAreaWidth() - margin * 2));
    }

    private int getHeight() {
        final int margin = mUi.desktopDp(12, 4, mActivity.isCompactDesktopPreview());
        return Math.min(mUi.dp(620), Math.max(
                1, mActivity.getDesktopAreaHeight() - mActivity.getTaskbarHeight() - margin));
    }

    @Override public List<AppItem> apps() { return mActivity.getLauncherApps(); }
    @Override public List<DesktopApplicationRepository.Entry> desktopApplications() {
        return mActivity.getDesktopApplications();
    }
    @Override public List<String> recentApps() {
        return DesktopPreferences.recentAppKeys(mActivity);
    }
    @Override public DesktopAutomationUiRegistry automation() { return mActivity.automationUi(); }
    @Override public void dismiss() { mActivity.hideTopPanel(); }
    @Override public void requestSearchFocus() { setVisible(true, true); }
    @Override public boolean mouseTouch(final MotionEvent event) {
        return mActivity.handleDesktopMouseTouchEvent(event, true);
    }
    @Override public boolean mouseMotion(final MotionEvent event) {
        return mActivity.handleDesktopMouseGenericEvent(event, true);
    }
    @Override public void appContext(final View view, final AppItem app) {
        mActivity.registerContextTarget(view, app, null);
    }
    @Override public void fileContext(final View view, final DesktopFile file) {
        mActivity.registerFileContextTarget(view, file);
    }
    @Override public void populateTools(
            final LinearLayout parent, final int spacing, final boolean capture) {
        if (capture) {
            mActivity.populateCaptureControls(parent, spacing);
        } else {
            mActivity.populateToolsControls(parent, spacing);
        }
    }

    @Override public void open(final StartSearchController.Result result) {
        mActivity.hideAllPanels();
        if (result.app != null) {
            mActivity.launchDefault(result.app);
            return;
        }
        if (result.desktopApplication != null) {
            mActivity.launchDesktopShortcut(
                    result.desktopApplication.shortcut,
                    DesktopLaunchArguments.empty(),
                    result.desktopApplication.desktopFilePath);
            return;
        }
        if (result.file != null) {
            final android.content.Intent intent = result.file.directory
                    ? FileManagerActivity.createIntent(
                            mActivity, result.file.absolutePath)
                    : FileManagerActivity.createRevealIntent(
                            mActivity, result.file);
            mActivity.launchInternalWindow(
                    intent,
                    BuiltInDesktopAppCatalog.filesTarget(),
                    mActivity.getString(R.string.file_manager_title));
            return;
        }
        if (result.builtIn != null) {
            final AppLaunchTarget target = result.builtIn.launchTarget;
            if (BuiltInDesktopAppCatalog.filesTarget().equals(target)) {
                mActivity.openFiles();
            } else if (BuiltInDesktopAppCatalog.consoleTarget().equals(target)) {
                mActivity.openConsole();
            } else if (BuiltInDesktopAppCatalog.taskManagerTarget().equals(target)) {
                mActivity.openTaskManager();
            } else if (BuiltInDesktopAppCatalog.settingsTarget().equals(target)) {
                mActivity.openSettings();
            }
            return;
        }
        if (result.action == StartSearchController.Action.SHOW_DESKTOP) {
            mActivity.toggleDesktopWorkspace();
        } else if (result.action == StartSearchController.Action.SCREENSHOT) {
            mActivity.captureDesktopScreenshot();
        } else if (result.action
                == StartSearchController.Action.SCREEN_RECORDING) {
            mActivity.toggleDesktopRecording();
        }

    }
}
