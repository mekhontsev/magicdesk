package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_AMBER;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class DesktopShellActivity extends Activity
        implements MagicDeskSessionHost {
    private static final String TAG = "MagicDesk";
    static final String HARDWARE_LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    static final String HARDWARE_LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    static final String HARDWARE_LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    static final String EXTRA_ACTION = "magicdesk_action";
    private static final String ACTION_SHOW_START = "show_start";
    static final String ACTION_RESTORE_WINDOWS = "restore_windows";
    static final String BROADCAST_SHOW_START =
            "io.github.mekhontsev.magicdesk.action.SHOW_START";
    private static final int MAX_DESKTOP_FILES = 30;
    static final int TASKBAR_HEIGHT_DP = 64;
    private static final int COMPACT_TASKBAR_HEIGHT_DP = 52;
    private FrameLayout mDesktopRoot;
    private DesktopLayoutController mDesktopLayout;
    private DesktopWallpaperController mDesktopWallpaperController;
    private OverlayPanelController mOverlayPanelController;
    private DesktopUiFactory mUi;
    private CalendarPanelController mCalendarController;
    private ShortcutHelpController mShortcutHelpController;
    private NotificationCenterController mNotifications;
    private DisplayProfileController mDisplayProfiles;
    private StartMenuController mStartMenuController;
    private TaskOverviewController mTaskOverviewController;
    private AppContextMenuController mContextMenuController;
    private TaskbarController mTaskbarController;
    private AltTabController mAltTabController;
    private WorkspaceController mWorkspaceController;
    private AppTaskController mAppTasks;
    private DesktopTaskSnapshotController mTaskSnapshots;
    private DisplayDensityController mDisplayDensityController;
    private ConsoleControlsController mConsoleControls;
    private MagicDeskSessionController mSessionController;
    private LauncherAppRepository mLauncherApps;
    private DesktopFileRepository mDesktopFileRepository;
    private DesktopItemsController mDesktopItemsController;
    private DesktopInputController mInputController;
    private DesktopHostWindowController mHostWindowController;
    private boolean mDesktopWindowFocusable = true;
    private boolean mLocalTaskbarSuppressed;
    private List<AppItem> mLastApps = Collections.emptyList();
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!DeviceSetupManager.isRuntimeAuthorized()) {
            final Intent setupIntent = DeviceSetupActivity.createLaunchIntent(this);
            final String action = getIntent().getStringExtra(EXTRA_ACTION);
            if (action != null) {
                setupIntent.putExtra(EXTRA_ACTION, action);
            }
            startActivity(setupIntent);
            finish();
            return;
        }
        DesktopRuntimeBridge.registerShell(this);
        mUi = new DesktopUiFactory(this);
        mDesktopLayout = new DesktopLayoutController(
                this,
                new DesktopLayoutController.RuntimeState() {
                    @Override
                    public int displayId() {
                        return getCurrentDisplayId();
                    }

                    @Override
                    public int taskbarHeight() {
                        return getTaskbarHeight();
                    }

                    @Override
                    public void onViewportChanged() {
                        hideAllPanels();
                        MagicDeskRuntimeService.refreshDesktopTasksIfRunning();
                    }
                });
        mCalendarController = new CalendarPanelController(
                this,
                mUi,
                this::hideAllPanels,
                this::captureInteractionStackForPanel,
                this::openCalendarApplication,
                () -> setErrorStatus(
                        "OVERLAY-001",
                        getString(R.string.status_overlay_panel_unavailable)));
        mShortcutHelpController = new ShortcutHelpController(
                this,
                mUi,
                this::hideAllPanels,
                () -> setErrorStatus(
                        "OVERLAY-001",
                        getString(R.string.status_overlay_panel_unavailable)));
        mNotifications = new NotificationCenterController(this, mUi);
        mDisplayProfiles = new DisplayProfileController(this);
        mStartMenuController = new StartMenuController(this, mUi);
        mTaskOverviewController = new TaskOverviewController(this, mUi);
        mContextMenuController = new AppContextMenuController(this, mUi);
        mTaskbarController = new TaskbarController(this, mUi);
        mAltTabController = new AltTabController(this);
        mWorkspaceController = new WorkspaceController(this);
        mAppTasks = new AppTaskController(this);
        mTaskSnapshots = new DesktopTaskSnapshotController(
                this, mWorkspaceController);
        mDisplayDensityController = new DisplayDensityController(this);
        mConsoleControls = new ConsoleControlsController(this, mUi);
        mSessionController = new MagicDeskSessionController(this);
        mLauncherApps = new LauncherAppRepository(this);
        mDesktopFileRepository =
                new DesktopFileRepository(getContentResolver(), MAX_DESKTOP_FILES);
        mDesktopItemsController = new DesktopItemsController(
                this, mUi, mDesktopFileRepository);
        mInputController = new DesktopInputController(this);
        mHostWindowController = new DesktopHostWindowController(this);
        DesktopRuntimeBridge.registerDesktop(this);
        setDesktopWindowFocusable(true);
        setContentView(createDesktopContentView());
        mDesktopRoot.post(mHostWindowController::ensureFullscreen);
        mNotifications.start();
        mConsoleControls.start();
        mDisplayProfiles.start();
        MagicDeskRuntimeService.start(this);
        if (RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT)) {
            ConsoleModeSwitcher.refreshHardwareKeyboardLayout();
        } else {
            RootKeyboardShortcutWatcher.stop();
            ConsoleModeSwitcher.closeRootShell();
        }
        renderApps();
        updateConsoleControls();
        handleLaunchAction(getIntent());
        ensurePreferredConsoleDensity();
    }

    void releaseDesktopOverlays() {
        if (mDesktopLayout != null) {
            mDesktopLayout.release();
        }
        if (mOverlayPanelController != null) {
            mOverlayPanelController.release();
            mOverlayPanelController = null;
        }
        if (mDesktopWallpaperController != null) {
            mDesktopWallpaperController.stop();
            mDesktopWallpaperController = null;
        }
        if (mTaskbarController != null) {
            mTaskbarController.release();
        }
    }

    @Override
    protected void onDestroy() {
        if (mNotifications != null) {
            mNotifications.stop();
        }
        if (mTaskSnapshots != null) {
            mTaskSnapshots.release();
        }
        if (mDesktopItemsController != null) {
            mDesktopItemsController.cancel();
        }
        if (mDisplayProfiles != null) {
            mDisplayProfiles.stop();
        }
        mLastApps = Collections.emptyList();
        if (mHostWindowController != null) {
            mHostWindowController.release();
        }
        releaseDesktopOverlays();
        DesktopRuntimeBridge.unregister(this);
        if (mConsoleControls != null) {
            mConsoleControls.stop();
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (mInputController != null
                && mInputController.handleTouchEvent(event, false)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(final MotionEvent event) {
        if (mInputController != null
                && mInputController.handleGenericMotionEvent(event, false)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    boolean handleDesktopMouseTouchEvent(final MotionEvent event,
            final boolean useRawCoordinates) {
        return mInputController != null
                && mInputController.handleTouchEvent(event, useRawCoordinates);
    }

    boolean handleDesktopMouseGenericEvent(final MotionEvent event,
            final boolean useRawCoordinates) {
        return mInputController != null
                && mInputController.handleGenericMotionEvent(
                        event, useRawCoordinates);
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        if (mInputController != null && mInputController.handleKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void handleSecondaryClick(final float x, final float y) {
        mContextMenuController.handleSecondaryClick(x, y);
    }

    boolean hasVisiblePanel() {
        return mOverlayPanelController != null
                && mOverlayPanelController.hasVisiblePanel();
    }

    OverlayPanelController overlayPanels() {
        return mOverlayPanelController;
    }

    boolean isDesktopShell() {
        return true;
    }

    boolean isActivityUnavailable() {
        return isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && isDestroyed());
    }

    List<AppItem> getLauncherApps() {
        return mLastApps;
    }

    NotificationCenterController notifications() {
        return mNotifications;
    }

    TaskbarController taskbar() {
        return mTaskbarController;
    }

    void scheduleDisplayProfileRefresh() {
        mDisplayProfiles.scheduleRefresh();
    }

    TaskRepository.Snapshot getTaskSnapshot() {
        return mTaskSnapshots.snapshot();
    }

    String getWorkspacePackage() {
        return getWorkspaceProfile().workspacePackage;
    }

    void clearInteractionVisibleTasks() {
        mAppTasks.clearInteractionStack();
    }

    void setTaskSnapshot(final TaskRepository.Snapshot snapshot) {
        mTaskSnapshots.setSnapshot(snapshot);
    }

    boolean isAltTabTaskSelected(final TaskRepository.TaskEntry task) {
        return mAltTabController.isSelected(task);
    }

    boolean isWorkspaceApp(final String packageName) {
        return packageName != null
                && packageName.equals(getWorkspaceProfile().workspacePackage);
    }

    boolean hasDesktopFolder() {
        return getWorkspaceProfile().folderUri != null;
    }

    boolean isPointInside(final View view, final float x, final float y) {
        if (view == null || mDesktopRoot == null || !view.isShown()) {
            return false;
        }
        final int[] rootLocation = new int[2];
        final int[] viewLocation = new int[2];
        mDesktopRoot.getLocationOnScreen(rootLocation);
        view.getLocationOnScreen(viewLocation);
        final Rect bounds = new Rect(
                viewLocation[0] - rootLocation[0],
                viewLocation[1] - rootLocation[1],
                viewLocation[0] - rootLocation[0] + view.getWidth(),
                viewLocation[1] - rootLocation[1] + view.getHeight());
        return bounds.contains(Math.round(x), Math.round(y));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLocalTaskbarSuppressed = false;
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        refreshWorkspaceProfileForDisplay();
        resolveMonitorIdentityAsync();
        setDesktopWindowFocusable(true);
        setTaskbarVisible(true);
        if (mLastApps.isEmpty()) {
            renderApps();
        } else {
            refreshTaskSnapshot();
        }
        refreshDesktopFolder(true);
        updateConsoleControls();
        mNotifications.refresh();
        ensurePreferredConsoleDensity();
        if (mHostWindowController != null) {
            mHostWindowController.ensureFullscreen();
        }
    }

    @Override
    protected void onStop() {
        if (getCurrentDisplayId() == Display.DEFAULT_DISPLAY) {
            mLocalTaskbarSuppressed = true;
            hideAllPanels();
            setTaskbarVisible(false);
        }
        super.onStop();
    }

    @Override
    public void onMultiWindowModeChanged(
            final boolean inMultiWindowMode,
            final Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfig);
        if (mHostWindowController != null) {
            mHostWindowController.onMultiWindowModeChanged(inMultiWindowMode);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mDesktopItemsController.handleActivityResult(
                requestCode, resultCode, data);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshWorkspaceProfileForDisplay();
        resolveMonitorIdentityAsync();
        setDesktopWindowFocusable(true);
    }

    void setDesktopWindowFocusable(final boolean focusable) {
        if (mDesktopWindowFocusable == focusable) {
            return;
        }
        mDesktopWindowFocusable = focusable;
        if (focusable) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            refreshWorkspaceProfileForDisplay();
            resolveMonitorIdentityAsync();
        }
        refreshTaskSnapshot();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchAction(intent);
    }

    private void handleLaunchAction(final Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getStringExtra(EXTRA_ACTION);
        intent.removeExtra(EXTRA_ACTION);
        if (ACTION_SHOW_START.equals(action)) {
            captureInteractionStackForPanel();
            setStartMenuVisible(true);
        } else if (ACTION_RESTORE_WINDOWS.equals(action)) {
            restoreLastVisibleWindows();
        }
    }

    static Intent createShowStartIntent(final Context context) {
        return DesktopActivity.createLaunchIntent(context)
                .putExtra(EXTRA_ACTION, ACTION_SHOW_START);
    }

    void syncTaskbarWithSnapshot(final TaskRepository.Snapshot snapshot) {
        mTaskSnapshots.sync(snapshot);
    }

    private View createDesktopContentView() {
        final FrameLayout root = new FrameLayout(this);
        mDesktopRoot = root;
        mOverlayPanelController = new OverlayPanelController(
                this, getCurrentDisplayId());
        root.setBackgroundColor(COLOR_BACKGROUND);
        mDesktopLayout.attachDesktopRoot(root);

        final ImageView wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setBackgroundColor(COLOR_BACKGROUND);
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mDesktopWallpaperController = new DesktopWallpaperController(
                this, wallpaper, message -> setErrorStatus(
                        "WALLPAPER-001",
                        getString(R.string.status_wallpaper_failed, message)));
        mDesktopWallpaperController.start();

        final LinearLayout desktop = new LinearLayout(this);
        desktop.setOrientation(LinearLayout.VERTICAL);
        desktop.setPadding(desktopDp(24, 10), desktopDp(22, 8),
                desktopDp(24, 10), getTaskbarHeight() + desktopDp(12, 6));
        desktop.setClickable(true);
        desktop.setFocusable(false);
        desktop.setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            desktop.setDefaultFocusHighlightEnabled(false);
        }
        final GestureDetector desktopGestures = new GestureDetector(
                this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(final MotionEvent event) {
                        hideAllPanels();
                        clearInteractionVisibleTasks();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(final MotionEvent event) {
                        desktop.performClick();
                        return true;
                    }

                    @Override
                    public void onLongPress(final MotionEvent event) {
                        captureInteractionStackForPanel();
                        showDesktopContextMenu(event.getRawX(), event.getRawY());
                    }
                });
        desktop.setOnTouchListener((view, event) -> {
            final boolean handled = desktopGestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                hideAllPanels();
                clearInteractionVisibleTasks();
            }
            return handled;
        });
        desktop.setOnClickListener(view -> { });

        final GridLayout desktopIcons = mDesktopItemsController.createGrid();
        final LinearLayout.LayoutParams iconsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        desktop.addView(desktopIcons, iconsParams);

        root.addView(desktop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mStartMenuController.create();
        final LinearLayout taskbar = mTaskbarController.create();
        if (!mDesktopLayout.attachTaskbar(
                taskbar, mOverlayPanelController, "MagicDesk taskbar")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
        }

        mContextMenuController.create();
        mTaskOverviewController.create();
        mNotifications.createPanel();
        mCalendarController.createPanel();
        mShortcutHelpController.createPanel();
        return root;
    }

    void toggleCalendarPanel() {
        mCalendarController.toggle(
                mOverlayPanelController,
                mDesktopLayout.viewport().contentBounds(),
                getTaskbarHeight());
    }

    private void openCalendarApplication() {
        final Intent intent = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
        final ResolveInfo resolved = getPackageManager().resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null) {
            hideAllPanels();
            setErrorStatus("CALENDAR-001",
                    getString(R.string.status_calendar_open_failed));
            return;
        }

        final AppItem app = LauncherAppRepository.find(
                mLastApps, resolved.activityInfo.packageName);
        hideAllPanels();
        if (app != null) {
            launchFloating(app, true);
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        invokeIntOption(options, "setLaunchDisplayId", getCurrentDisplayId());
        try {
            startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            setErrorStatus(
                    "CALENDAR-001",
                    getString(R.string.status_calendar_open_failed),
                    "package=" + resolved.activityInfo.packageName,
                    e);
        }
    }


    void toggleTaskOverview() {
        mTaskOverviewController.toggle();
    }

    void showTaskOverview() {
        mTaskOverviewController.show();
    }

    void populateTaskOverview(final TaskRepository.Snapshot snapshot) {
        mTaskOverviewController.populate(snapshot);
    }

    boolean showTaskOverviewPanel() {
        return mTaskOverviewController.showPanel();
    }

    void registerContextTarget(final View view, final AppItem app,
            final TaskRepository.TaskEntry task) {
        mContextMenuController.registerTarget(view, app, task);
    }

    void showDesktopContextMenu(final float x, final float y) {
        mContextMenuController.showDesktopMenu(x, y);
    }

    void showStartSection(final int mode) {
        mStartMenuController.showSection(mode);
    }

    void showStartSection(final int mode, final boolean focusable) {
        mStartMenuController.showSection(mode, focusable);
    }

    void confirmForceStop(final AppItem app) {
        hideAllPanels();
        new AlertDialog.Builder(this)
                .setTitle(R.string.force_stop_title)
                .setMessage(getString(R.string.force_stop_message, app.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_force_stop,
                        (dialog, which) -> forceStopApp(app))
                .show();
    }

    TaskRepository.TaskEntry findFirstTask(final String packageName) {
        return mTaskSnapshots.findFirstTask(packageName);
    }

    static TaskRepository.TaskEntry findTask(
            final TaskRepository.Snapshot snapshot, final int taskId) {
        if (snapshot == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    void renderApps() {
        final List<AppItem> apps =
                mLauncherApps.load(isUniversalFreeformEnabled());
        mLastApps = apps;

        if (apps.isEmpty()) {
            setStatus(R.string.status_no_apps);
            return;
        }

        setStatus(getString(R.string.status_ready,
                Integer.valueOf(apps.size()),
                Integer.valueOf(getCurrentDisplayId())));
        renderDesktop(apps);
    }

    private void renderDesktop(final List<AppItem> apps) {
        refreshWorkspaceProfileForDisplay();
        resolveMonitorIdentityAsync();
        renderDesktopIcons(apps);
        renderTaskbarPins(apps);
        renderStartMenuContent();
        refreshTaskSnapshot();
        refreshDesktopFolder(false);
    }

    void renderDesktopIcons(final List<AppItem> apps) {
        mDesktopItemsController.render(apps);
    }

    void chooseDesktopFolder() {
        mDesktopItemsController.chooseFolder();
    }

    void clearDesktopFolder() {
        mDesktopItemsController.clearFolder();
    }

    void refreshDesktopFolder(final boolean force) {
        mDesktopItemsController.refreshFolder(force);
    }

    void refreshTaskSnapshot() {
        mTaskSnapshots.refresh();
    }

    List<TaskRepository.TaskEntry> findTasks(final String packageName) {
        return mTaskSnapshots.findTasks(packageName);
    }

    boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return mTaskSnapshots.isTaskbarTask(task);
    }

    AppItem findOrLoadApp(final List<AppItem> apps, final String packageName) {
        return mLauncherApps.findOrLoad(
                apps, packageName, isUniversalFreeformEnabled());
    }

    List<String> getOrderedPinnedPackages(final List<AppItem> apps,
            final Set<String> pinnedPackages) {
        final List<String> ordered = new ArrayList<>();
        for (final String packageName : DesktopPreferences.favoritePackages()) {
            if (pinnedPackages.contains(packageName)) {
                ordered.add(packageName);
            }
        }
        for (final AppItem app : apps) {
            if (pinnedPackages.contains(app.packageName)
                    && !ordered.contains(app.packageName)) {
                ordered.add(app.packageName);
            }
        }
        return ordered;
    }

    Set<String> getPinnedPackages() {
        return mWorkspaceController.getPinnedPackages();
    }

    void togglePinned(final AppItem app) {
        mWorkspaceController.togglePinned(app);
    }

    boolean isDesktopShortcut(final String packageName) {
        return mWorkspaceController.isDesktopShortcut(packageName);
    }

    void toggleDesktopShortcut(final AppItem app) {
        mWorkspaceController.toggleDesktopShortcut(app);
    }

    void setWorkspaceApp(final AppItem app,
            final TaskRepository.TaskEntry task, final boolean keep) {
        mWorkspaceController.setWorkspaceApp(app, task, keep);
    }

    void restoreWorkspaceApp(
            final TaskRepository.Snapshot snapshot,
            final boolean bringToFront) {
        mWorkspaceController.restore(snapshot, bringToFront);
    }

    void focusTask(final AppItem app, final TaskRepository.TaskEntry task) {
        mAppTasks.focusTask(app, task);
    }

    void advanceAltTab(final boolean reverse) {
        mAltTabController.advance(reverse);
    }

    void finishAltTab() {
        mAltTabController.finish();
    }

    void cancelAltTabFromRuntime() {
        resetAltTabState();
        if (mTaskOverviewController.isVisible()) {
            hideAllPanels();
        }
    }

    void resetAltTabState() {
        mAltTabController.reset();
    }

    void openTaskFullscreen(final AppItem app,
            final TaskRepository.TaskEntry task) {
        mAppTasks.openTaskFullscreen(app, task);
    }

    int getOtherDisplayId(final TaskRepository.TaskEntry task) {
        return mAppTasks.getOtherDisplayId(task);
    }

    void moveTaskToOtherDisplay(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mAppTasks.moveTaskToOtherDisplay(app, task);
    }

    void closeTask(final AppItem app, final TaskRepository.TaskEntry task) {
        mAppTasks.closeTask(app, task);
    }

    private void forceStopApp(final AppItem app) {
        mAppTasks.forceStop(app);
    }

    void toggleDesktopWorkspace() {
        hideAllPanels();
        ConsoleModeSwitcher.showMagicDesk();
    }

    void captureDesktopScreenshot() {
        hideAllPanels();
        final View decor = getWindow().getDecorView();
        if (!decor.isAttachedToWindow()) {
            ConsoleModeSwitcher.captureScreenshot();
            return;
        }
        // removeViewImmediate() has detached the overlay. Two display frames let
        // WindowManager commit that removal before the external display is captured.
        decor.postOnAnimation(() ->
                decor.postOnAnimation(ConsoleModeSwitcher::captureScreenshot));
    }

    void restoreLastVisibleWindows() {
        mAppTasks.restoreLastVisibleWindows();
    }

    void captureInteractionStackForPanel() {
        mAppTasks.captureInteractionStackForPanel();
    }

    void renderTaskbarPins(final List<AppItem> apps) {
        mTaskbarController.renderPins(apps);
    }

    void renderStartMenuContent() {
        mStartMenuController.render();
    }

    void toggleStartMenu() {
        mStartMenuController.toggle();
    }

    void showStartFromRuntime() {
        captureInteractionStackForPanel();
        setStartMenuVisible(true);
    }

    private void setStartMenuVisible(final boolean visible) {
        mStartMenuController.setVisible(visible);
    }

    void toggleToolsMenu() {
        mStartMenuController.toggleTools();
    }

    void toggleHardwareMenu() {
        mStartMenuController.toggleHardware();
    }

    void openDeviceSetup() {
        hideAllPanels();
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getCurrentDisplayId());
        startActivity(
                DeviceSetupActivity.createManualIntent(this),
                options.toBundle());
    }

    void openControlPanel() {
        hideAllPanels();
        PhoneControlPanelLauncher.open(this);
    }

    void toggleShortcutHelp() {
        mShortcutHelpController.toggle(
                mOverlayPanelController,
                mDesktopLayout.viewport().contentBounds(),
                getTaskbarHeight());
    }

    void hideAllPanels() {
        if (mOverlayPanelController != null) {
            mOverlayPanelController.hideAll();
        }
    }

    int getDesktopAreaWidth() {
        return mDesktopLayout.desktopAreaWidth();
    }

    int getDesktopAreaHeight() {
        return mDesktopLayout.desktopAreaHeight();
    }

    int getDesktopAreaLeft() {
        return mDesktopLayout.desktopAreaLeft();
    }

    int getDesktopAreaTop() {
        return mDesktopLayout.desktopAreaTop();
    }

    int getTaskbarHeight() {
        return desktopDp(TASKBAR_HEIGHT_DP, COMPACT_TASKBAR_HEIGHT_DP);
    }

    DesktopViewport getDesktopViewport() {
        return mDesktopLayout.viewport();
    }

    void updateConsoleControls() {
        mConsoleControls.update();
    }

    void togglePhoneScreen() {
        mConsoleControls.togglePhoneScreen();
    }

    void populateToolsControls(
            final LinearLayout parent,
            final int spacing) {
        mConsoleControls.populateTools(parent, spacing);
    }

    void populateHardwareControls(
            final LinearLayout parent,
            final int spacing) {
        mConsoleControls.populateHardware(parent, spacing);
    }

    int getPreferredDesktopDpi() {
        return getWorkspaceProfile().dpi;
    }

    void setPreferredDesktopDpi(final int dpi) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.dpi = dpi;
        saveWorkspaceProfile();
        DesktopPreferences.saveLegacyDesktopDpi(this, dpi);
    }

    WorkspaceProfileStore.Profile getWorkspaceProfile() {
        return mDisplayProfiles.getProfile();
    }

    private void refreshWorkspaceProfileForDisplay() {
        mDisplayProfiles.refreshForDisplay();
    }

    private void resolveMonitorIdentityAsync() {
        mDisplayProfiles.resolveMonitorIdentityAsync();
    }

    String getMonitorProfileLabel() {
        return mDisplayProfiles.getMonitorLabel();
    }

    void saveWorkspaceProfile() {
        mDisplayProfiles.save();
    }

    void onWorkspaceProfileReset() {
        mWorkspaceController.resetProfileState();
        mDesktopItemsController.resetProfileState();
    }

    void onMonitorProfileResolved(
            final int previousDpi, final int resolvedDpi) {
        onWorkspaceProfileReset();
        renderApps();
        refreshDesktopFolder(true);
        updateConsoleControls();
        if (resolvedDpi != previousDpi) {
            mDisplayDensityController.resetApplyState();
            ensurePreferredConsoleDensity();
        }
    }


    void launchDefault(final AppItem app) {
        mAppTasks.launchDefault(app);
    }

    void launchFloating(final AppItem app) {
        mAppTasks.launchFloating(app, false);
    }

    private void launchFloating(
            final AppItem app,
            final boolean rootColdLaunch) {
        mAppTasks.launchFloating(app, rootColdLaunch);
    }

    void launchFullscreen(final AppItem app) {
        mAppTasks.launchFullscreen(app);
    }

    void setTaskbarVisible(final boolean visible) {
        mTaskbarController.setVisible(
                visible && !mLocalTaskbarSuppressed);
    }

    Button createKernelFixesAction() {
        final Button action = createActionButton(
                R.string.action_kernel_fixes, COLOR_AMBER);
        action.setOnClickListener(view -> {
            hideAllPanels();
            if (!KernelFixesIntegration.launch(this)) {
                setErrorStatus(
                        "KERNEL-FIXES-001",
                        getString(R.string.status_kernel_fixes_unavailable));
            }
        });
        return action;
    }

    void exitMagicDesk() {
        mSessionController.exit();
    }

    void applyDensity(final int dpi) {
        mDisplayDensityController.apply(dpi);
    }

    void resetDensity() {
        mDisplayDensityController.reset();
    }

    private void ensurePreferredConsoleDensity() {
        mDisplayDensityController.ensurePreferred();
    }

    void showLaunchFailure(final RuntimeException e) {
        Log.w(TAG, "launch failed", e);
        final String message = e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage();
        setErrorStatus(
                "APP-LAUNCH-001",
                getString(R.string.status_launch_failed, message),
                "display=" + getCurrentDisplayId(),
                e);
        Toast.makeText(this, getString(R.string.status_launch_failed, message),
                Toast.LENGTH_LONG).show();
    }

    void openDiagnostics() {
        hideAllPanels();
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getCurrentDisplayId());
        try {
            startActivity(DiagnosticsActivity.createIntent(this), options.toBundle());
        } catch (RuntimeException e) {
            setErrorStatus(
                    "DIAGNOSTICS-001",
                    "Cannot open compatibility diagnostics",
                    "display=" + getCurrentDisplayId(),
                    e);
        }
    }

    int getCurrentDisplayId() {
        final Display display = getWindowManager().getDefaultDisplay();
        return display == null ? 0 : display.getDisplayId();
    }

    static void invokeIntOption(final ActivityOptions options, final String methodName,
            final int value) {
        try {
            final Method method = ActivityOptions.class.getMethod(methodName, Integer.TYPE);
            method.invoke(options, Integer.valueOf(value));
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, methodName + " unavailable", e);
        } catch (RuntimeException e) {
            Log.w(TAG, methodName + " failed", e);
        }
    }

    private boolean isUniversalFreeformEnabled() {
        return Settings.Global.getInt(getContentResolver(),
                "enable_freeform_support", 0) == 1
                && Settings.Global.getInt(getContentResolver(),
                "force_resizable_activities", 0) == 1;
    }

    private static boolean isPackageNameSafe(final String packageName) {
        if (packageName == null || packageName.length() == 0 || packageName.length() > 220) {
            return false;
        }
        for (int i = 0; i < packageName.length(); i++) {
            final char ch = packageName.charAt(i);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '.') {
                continue;
            }
            return false;
        }
        return packageName.indexOf('.') > 0 && packageName.indexOf("..") < 0;
    }

    private Button createActionButton(final int textResId, final int accentColor) {
        return mUi.actionButton(textResId, accentColor);
    }

    void setStatus(final int stringResId) {
        setStatus(getString(stringResId));
    }

    void setErrorStatus(final String code, final String message) {
        setErrorStatus(code, message, "", null);
    }

    void setErrorStatus(final String code, final String message,
            final String technicalDetail, final Throwable error) {
        CompatibilityDiagnostics.record(code, message, technicalDetail, error);
        setStatus(message + " [" + code + "]");
    }

    void setStatus(final String text) {
        if (mConsoleControls != null) {
            mConsoleControls.setActivityStatus(text);
        }
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(final int normalValue, final int compactValue) {
        return mUi.desktopDp(
                normalValue, compactValue, isCompactDesktopPreview());
    }

    boolean isCompactDesktopPreview() {
        return getResources().getConfiguration().screenWidthDp < 700;
    }

    @Override
    public Activity sessionActivity() {
        return this;
    }

    @Override
    public void showSessionStatus(final String message) {
        setStatus(message);
    }

    @Override
    public void showSessionError(
            final String code,
            final String message,
            final Throwable error) {
        setErrorStatus(code, message, "", error);
    }

    @Override
    public void releaseSessionUi() {
        releaseDesktopOverlays();
    }

}
