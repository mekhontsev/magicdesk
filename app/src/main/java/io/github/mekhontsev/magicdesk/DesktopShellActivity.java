package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_AMBER;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class DesktopShellActivity extends Activity
        implements MagicDeskSessionHost,
        DisplayProfileController.Host,
        DesktopHostWindowController.Host {
    private static final String TAG = "MagicDesk";
    static final String HARDWARE_LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    static final String HARDWARE_LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    static final String HARDWARE_LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    static final String EXTRA_ACTION = "magicdesk_action";
    static final String EXTRA_EXPECTED_DISPLAY_ID =
            "magicdesk_expected_display_id";
    static final String EXTRA_PROFILE_DISPLAY_ID =
            "magicdesk_profile_display_id";
    static final String EXTRA_PROFILE_KEY = "magicdesk_profile_key";
    static final String EXTRA_TARGET_KIND = "magicdesk_target_kind";
    private static final String ACTION_SHOW_START = "show_start";
    static final String ACTION_RESTORE_WINDOWS = "restore_windows";
    private static final String STATE_TOOLS_VISIBLE = "tools_visible";
    private static final String STATE_EXPECTED_DISPLAY_ID =
            "expected_display_id";
    private static final String STATE_PROFILE_DISPLAY_ID =
            "profile_display_id";
    private static final String STATE_PROFILE_KEY = "profile_key";
    private static final String STATE_TARGET_KIND = "target_kind";
    private static final Map<Integer, Integer> EXPECTED_DISPLAY_BY_TASK =
            new HashMap<>();
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
    private SystemPanelController mSystemPanelController;
    private DisplayProfileController mDisplayProfiles;
    private StartMenuController mStartMenuController;
    private TaskOverviewController mTaskOverviewController;
    private DesktopContextMenuController mContextMenuController;
    private TaskbarController mTaskbarController;
    private DesktopTaskbarRevealController mTaskbarRevealController;
    private AltTabController mAltTabController;
    private WorkspaceAppController mWorkspaceAppController;
    private DesktopWorkspaceController mDesktopWorkspaceController;
    private AppTaskController mAppTasks;
    private DesktopTaskSnapshotController mTaskSnapshots;
    private DisplayDensityController mDisplayDensityController;
    private DesktopControlsController mDesktopControls;
    private MagicDeskSessionController mSessionController;
    private LauncherAppRepository mLauncherApps;
    private DesktopInputController mInputController;
    private DesktopHostWindowController mHostWindowController;
    private DesktopSystemActionsController mSystemActions;
    private boolean mDesktopWindowFocusable = true;
    private boolean mTaskbarVisible = true;
    private int mExpectedDisplayId = Display.INVALID_DISPLAY;
    private int mDesktopProfileDisplayId = Display.INVALID_DISPLAY;
    private String mDesktopProfileKey = "";
    private DesktopDisplayTarget.Kind mDesktopTargetKind;
    private List<AppItem> mLastApps = Collections.emptyList();
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int displayId = getCurrentDisplayId();
        final Integer retainedDisplayId =
                EXPECTED_DISPLAY_BY_TASK.get(Integer.valueOf(getTaskId()));
        mExpectedDisplayId = retainedDisplayId != null
                ? retainedDisplayId.intValue()
                : savedInstanceState == null
                        ? getIntent().getIntExtra(
                                EXTRA_EXPECTED_DISPLAY_ID, displayId)
                        : savedInstanceState.getInt(
                                STATE_EXPECTED_DISPLAY_ID, displayId);
        EXPECTED_DISPLAY_BY_TASK.put(
                Integer.valueOf(getTaskId()),
                Integer.valueOf(mExpectedDisplayId));
        final Bundle source = savedInstanceState == null
                ? getIntent().getExtras() : savedInstanceState;
        if (source != null) {
            mDesktopProfileDisplayId = source.getInt(
                    savedInstanceState == null
                            ? EXTRA_PROFILE_DISPLAY_ID
                            : STATE_PROFILE_DISPLAY_ID,
                    Display.INVALID_DISPLAY);
            mDesktopProfileKey = source.getString(
                    savedInstanceState == null
                            ? EXTRA_PROFILE_KEY : STATE_PROFILE_KEY,
                    "");
            mDesktopTargetKind = parseTargetKind(source.getString(
                    savedInstanceState == null
                            ? EXTRA_TARGET_KIND : STATE_TARGET_KIND,
                    ""));
        }
        final DisplayManager displayManager =
                getSystemService(DisplayManager.class);
        final boolean expectedDisplayExists = displayManager != null
                && displayManager.getDisplay(mExpectedDisplayId) != null;
        if (isFinishing()
                || displayId != mExpectedDisplayId
                || !expectedDisplayExists) {
            Log.i(TAG, "discarding desktop moved from display="
                    + mExpectedDisplayId + " to display=" + displayId
                    + " finishing=" + isFinishing()
                    + " targetExists=" + expectedDisplayExists);
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
            return;
        }
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
        if (mDesktopTargetKind != null
                && mExpectedDisplayId > Display.DEFAULT_DISPLAY) {
            DesktopRuntimeBridge.noteDesktopTarget(
                    DesktopDisplayTarget.restore(
                            mDesktopTargetKind,
                            mExpectedDisplayId,
                            mDesktopProfileDisplayId,
                            mDesktopProfileKey));
        }
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
                    public void onImeInsetsChanged(
                            final boolean visible,
                            final int bottomInset) {
                        DesktopShellActivity.this.onDesktopImeInsetsChanged(
                                visible, bottomInset);
                    }

                    @Override
                    public void onViewportChanged() {
                        hideAllPanels();
                        if (mTaskbarRevealController != null) {
                            mTaskbarRevealController.updateViewport();
                        }
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
        mDisplayProfiles = new DisplayProfileController(this, this);
        mStartMenuController = new StartMenuController(this, mUi);
        mTaskOverviewController = new TaskOverviewController(this, mUi);
        mContextMenuController = new DesktopContextMenuController(this, mUi);
        mTaskbarController = new TaskbarController(this, mUi);
        mTaskbarRevealController =
                new DesktopTaskbarRevealController(this);
        mAltTabController = new AltTabController(this);
        mDesktopWorkspaceController =
                new DesktopWorkspaceController(this, mUi);
        mWorkspaceAppController = new WorkspaceAppController(
                this, mDesktopWorkspaceController.content());
        mAppTasks = new AppTaskController(this);
        mTaskSnapshots = new DesktopTaskSnapshotController(
                this, mWorkspaceAppController);
        mDisplayDensityController = new DisplayDensityController(this);
        mDesktopControls = new DesktopControlsController(this, mUi);
        mSystemPanelController = new SystemPanelController(this, mUi);
        mSessionController = new MagicDeskSessionController(this);
        mLauncherApps = new LauncherAppRepository(this);
        mInputController = new DesktopInputController(this);
        mHostWindowController = new DesktopHostWindowController(this);
        mSystemActions = new DesktopSystemActionsController(this);
        DesktopRuntimeBridge.registerDesktop(this);
        setDesktopWindowFocusable(true);
        setContentView(createDesktopContentView());
        mTaskbarRevealController.start();
        mDesktopRoot.post(mHostWindowController::ensureConfigured);
        mNotifications.start();
        mDesktopControls.start();
        mDisplayProfiles.start();
        MagicDeskRuntimeService.start(this);
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.refreshHardwareKeyboardLayout();
        }
        if (!ShellAccess.isReady()) {
            KeyboardShortcutWatcher.stop();
        }
        renderApps();
        updateDesktopControls();
        handleLaunchAction(getIntent());
        ensurePreferredConsoleDensity();
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_TOOLS_VISIBLE)) {
            mDesktopRoot.post(this::toggleToolsMenu);
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putInt(STATE_EXPECTED_DISPLAY_ID, mExpectedDisplayId);
        outState.putInt(
                STATE_PROFILE_DISPLAY_ID, mDesktopProfileDisplayId);
        outState.putString(STATE_PROFILE_KEY, mDesktopProfileKey);
        outState.putString(
                STATE_TARGET_KIND,
                mDesktopTargetKind == null ? "" : mDesktopTargetKind.name());
        outState.putBoolean(
                STATE_TOOLS_VISIBLE,
                mStartMenuController != null
                        && mStartMenuController.isToolsVisible());
        super.onSaveInstanceState(outState);
    }

    private static DesktopDisplayTarget.Kind parseTargetKind(
            final String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return DesktopDisplayTarget.Kind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    void releaseDesktopOverlays() {
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.release();
            mTaskbarRevealController = null;
        }
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
        if (mDesktopWorkspaceController != null) {
            mDesktopWorkspaceController.release();
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
        if (mDesktopControls != null) {
            mDesktopControls.stop();
        }
        if (!isChangingConfigurations()) {
            EXPECTED_DISPLAY_BY_TASK.remove(Integer.valueOf(getTaskId()));
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

    private void onDesktopImeInsetsChanged(
            final boolean visible,
            final int bottomInset) {
        if (mDesktopLayout != null) {
            mDesktopLayout.setTaskbarBottomInset(
                    visible ? bottomInset : 0);
        }
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.setForcedVisible(visible);
        }
    }

    boolean isDesktopShell() {
        return true;
    }

    @Override
    public boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
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
        return mWorkspaceAppController.getWorkspacePackage();
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
        return mWorkspaceAppController.isWorkspaceApp(packageName);
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
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        refreshDisplayProfile();
        setDesktopWindowFocusable(true);
        setTaskbarVisible(true);
        if (mLastApps.isEmpty()) {
            renderApps();
        } else {
            refreshTaskSnapshot();
        }
        refreshDesktopFolder(true);
        updateDesktopControls();
        mNotifications.refresh();
        ensurePreferredConsoleDensity();
        if (mHostWindowController != null) {
            mHostWindowController.ensureConfigured();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mDesktopWorkspaceController != null) {
            mDesktopWorkspaceController.start();
        }
    }

    @Override
    protected void onStop() {
        if (mDesktopWorkspaceController != null) {
            mDesktopWorkspaceController.stop();
        }
        if (getCurrentDisplayId() == Display.DEFAULT_DISPLAY) {
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
        if (mDesktopWallpaperController != null
                && mDesktopWallpaperController.handleActivityResult(
                        requestCode, resultCode, data)) {
            return;
        }
        mDesktopWorkspaceController.handleActivityResult(
                requestCode, resultCode, data);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshDisplayProfile();
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

    boolean isDesktopHostReady() {
        return mHostWindowController != null
                && mHostWindowController.isReady();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // A client may leave Android pointer capture orphaned after losing focus.
            getWindow().getDecorView().releasePointerCapture();
            refreshDisplayProfile();
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

    // GestureDetector routes confirmed taps through performClick().
    @SuppressLint("ClickableViewAccessibility")
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
                this, wallpaper);
        mDesktopWallpaperController.start();

        final LinearLayout desktop = new LinearLayout(this);
        desktop.setOrientation(LinearLayout.VERTICAL);
        desktop.setPadding(desktopDp(24, 10), desktopDp(22, 8),
                desktopDp(24, 10), getTaskbarHeight() + desktopDp(12, 6));
        desktop.setClickable(true);
        desktop.setFocusable(false);
        desktop.setFocusableInTouchMode(false);
        desktop.setDefaultFocusHighlightEnabled(false);
        final GestureDetector desktopGestures = new GestureDetector(
                this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(final MotionEvent event) {
                        hideAllPanels();
                        mDesktopWorkspaceController.cancelEditMode();
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

        final DesktopGridLayout desktopIcons =
                mDesktopWorkspaceController.createGrid();
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
        mSystemPanelController.createPanel();
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
            launchFloating(app);
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getCurrentDisplayId());
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

    void registerDesktopAppContextTarget(
            final View view, final AppItem app) {
        mContextMenuController.registerDesktopAppTarget(view, app);
    }

    void registerFileContextTarget(
            final View view,
            final DesktopFile file) {
        mContextMenuController.registerFileTarget(view, file);
    }

    void registerWidgetContextTarget(
            final View view,
            final int appWidgetId,
            final String label,
            final boolean configurable,
            final int resizeMode) {
        mContextMenuController.registerWidgetTarget(
                view, appWidgetId, label, configurable, resizeMode);
    }

    void showDesktopContextMenu(final float x, final float y) {
        mContextMenuController.showDesktopMenu(x, y);
    }

    void onDesktopMetadataChanged(
            final boolean stateChanged,
            final boolean wallpaperChanged) {
        if (wallpaperChanged && mDesktopWallpaperController != null) {
            mDesktopWallpaperController.reloadExternal();
        }
        if (!stateChanged) {
            return;
        }
        mDisplayProfiles.reloadStoredProfile();
        renderApps();
        updateDesktopControls();
    }

    void addDesktopWidget() {
        mDesktopWorkspaceController.addWidget();
    }

    void chooseDesktopWallpaper() {
        hideAllPanels();
        mDesktopWallpaperController.chooseWallpaper();
    }

    void useSystemDesktopWallpaper() {
        hideAllPanels();
        mDesktopWallpaperController.useSystemWallpaper();
    }

    boolean isUsingCustomDesktopWallpaper() {
        return mDesktopWallpaperController != null
                && mDesktopWallpaperController.isUsingCustomWallpaper();
    }

    void configureDesktopWidget(final int appWidgetId) {
        hideAllPanels();
        mDesktopWorkspaceController.configureWidget(appWidgetId);
    }

    void beginDesktopWidgetMove(final int appWidgetId) {
        hideAllPanels();
        mDesktopWorkspaceController.beginWidgetMove(appWidgetId);
    }

    void removeDesktopWidget(final int appWidgetId) {
        hideAllPanels();
        mDesktopWorkspaceController.removeWidget(appWidgetId);
    }

    void resizeDesktopWidget(
            final int appWidgetId,
            final int columnDelta,
            final int rowDelta) {
        hideAllPanels();
        mDesktopWorkspaceController.resizeWidget(
                appWidgetId, columnDelta, rowDelta);
    }

    void openDesktopFile(final DesktopFile file) {
        mDesktopWorkspaceController.openFile(file);
    }

    void createDesktopFile(final boolean directory) {
        showDesktopNameDialog(
                directory
                        ? R.string.new_folder_title
                        : R.string.new_file_title,
                "",
                directory
                        ? R.string.new_folder_name_hint
                        : R.string.new_file_name_hint,
                name -> mDesktopWorkspaceController.createFile(
                        name, directory));
    }

    void renameDesktopFile(final DesktopFile file) {
        showDesktopNameDialog(
                R.string.rename_desktop_entry_title,
                file.name,
                R.string.desktop_entry_name_hint,
                name -> mDesktopWorkspaceController.renameFile(
                        file, name));
    }

    void confirmDeleteDesktopFile(final DesktopFile file) {
        hideAllPanels();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.delete_desktop_entry_title)
                .setMessage(getString(
                        file.directory
                                ? R.string.delete_desktop_folder_message
                                : R.string.delete_desktop_file_message,
                        file.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.action_delete,
                        (confirmedDialog, which) ->
                                mDesktopWorkspaceController.deleteFile(file))
                .create();
        configureOverlayDialog(dialog);
        dialog.show();
    }

    void deleteDesktopShortcut(final AppItem app) {
        hideAllPanels();
        mDesktopWorkspaceController.deleteShortcut(app);
    }

    void showStartSection(final int mode) {
        mStartMenuController.showSection(mode);
    }

    void showStartSection(final int mode, final boolean focusable) {
        mStartMenuController.showSection(mode, focusable);
    }

    void confirmForceStop(final AppItem app) {
        hideAllPanels();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.force_stop_title)
                .setMessage(getString(R.string.force_stop_message, app.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_force_stop,
                        (confirmedDialog, which) -> forceStopApp(app))
                .create();
        final Window window = dialog.getWindow();
        if (window != null && Settings.canDrawOverlays(this)) {
            window.setType(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.show();
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
        refreshDisplayProfile();
        renderDesktopIcons(apps);
        renderTaskbarPins(apps);
        renderStartMenuContent();
        refreshTaskSnapshot();
        refreshDesktopFolder(false);
    }

    void renderDesktopIcons(final List<AppItem> apps) {
        mDesktopWorkspaceController.render(apps);
    }

    void openDesktopFolder() {
        hideAllPanels();
        mDesktopWorkspaceController.openFolder();
    }

    void refreshDesktopFolder(final boolean force) {
        mDesktopWorkspaceController.refreshFolder(force);
    }

    private void showDesktopNameDialog(
            final int titleResId,
            final String initialValue,
            final int hintResId,
            final DesktopNameAction action) {
        hideAllPanels();
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(hintResId);
        input.setText(initialValue);
        input.setSelectAllOnFocus(true);
        final int padding = mUi.dp(20);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleResId)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_create, null)
                .create();
        configureOverlayDialog(dialog);
        dialog.setOnShowListener(ignored -> {
            final Button positive = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE);
            positive.setText(initialValue.length() == 0
                    ? R.string.action_create : R.string.action_rename);
            positive.setOnClickListener(view -> {
                final String name = input.getText().toString().trim();
                if (name.length() == 0) {
                    input.setError(getString(
                            R.string.desktop_entry_name_required));
                    return;
                }
                dialog.dismiss();
                action.run(name);
            });
            input.requestFocus();
            final Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            input.post(() -> {
                final InputMethodManager inputMethodManager =
                        getSystemService(InputMethodManager.class);
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(
                            input, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        });
        dialog.show();
    }

    private void configureOverlayDialog(final AlertDialog dialog) {
        final Window window = dialog.getWindow();
        if (window != null && Settings.canDrawOverlays(this)) {
            window.setType(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @FunctionalInterface
    private interface DesktopNameAction {
        void run(String name);
    }

    @Override
    public void refreshTaskSnapshot() {
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

    AppItem findOrLoadApp(
            final List<AppItem> apps,
            final AppLaunchTarget target) {
        return mLauncherApps.findOrLoad(
                apps, target, isUniversalFreeformEnabled());
    }

    List<String> getPinnedPackages() {
        return mTaskbarController.getPinnedPackages();
    }

    void togglePinned(final AppItem app) {
        mTaskbarController.togglePinned(app);
    }

    boolean isDesktopShortcut(final AppItem app) {
        return mDesktopWorkspaceController.isDesktopShortcut(app);
    }

    void toggleDesktopShortcut(final AppItem app) {
        mDesktopWorkspaceController.toggleDesktopShortcut(app);
    }

    void setWorkspaceApp(final AppItem app,
            final TaskRepository.TaskEntry task, final boolean keep) {
        mWorkspaceAppController.setWorkspaceApp(app, task, keep);
    }

    void restoreWorkspaceApp(
            final TaskRepository.Snapshot snapshot,
            final boolean bringToFront) {
        mWorkspaceAppController.restore(snapshot, bringToFront);
    }

    void focusTask(final AppItem app, final TaskRepository.TaskEntry task) {
        mAppTasks.focusTask(app, task);
    }

    void toggleTaskbarTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mAppTasks.toggleTaskbarTask(app, task);
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
        mSystemActions.showDesktop();
    }

    void captureDesktopScreenshot() {
        mSystemActions.captureScreenshot();
    }

    void toggleDesktopRecording() {
        mSystemActions.toggleRecording();
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

    void toggleSystemPanel() {
        mSystemPanelController.toggle();
    }

    void openDeviceSetup() {
        mSystemActions.openDeviceSetup();
    }

    void openControlPanel() {
        mSystemActions.openControlPanel();
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

    Rect getTaskbarBounds() {
        return mDesktopLayout.taskbarBounds();
    }

    void updateDesktopControls() {
        mDesktopControls.update();
    }

    void setHardwarePanelVisible(final boolean visible) {
        mDesktopControls.setHardwarePanelVisible(visible);
    }

    void togglePhoneScreen() {
        mDesktopControls.togglePhoneScreen();
    }

    void populateToolsControls(
            final LinearLayout parent,
            final int spacing) {
        mDesktopControls.populateTools(parent, spacing);
    }

    void populateSystemControls(
            final LinearLayout parent,
            final int spacing) {
        mDesktopControls.populateSystem(parent, spacing);
    }

    void populateCaptureControls(
            final LinearLayout parent,
            final int spacing) {
        mDesktopControls.populateCapture(parent, spacing);
    }

    void showCaptureControls() {
        mStartMenuController.showCapture();
    }

    int getPreferredDesktopDpi() {
        return getDisplayProfile().dpi;
    }

    void setPreferredDesktopDpi(final int dpi) {
        final DisplayProfileStore.Profile profile = getDisplayProfile();
        profile.dpi = dpi;
        profile.dpiExplicit = true;
        saveDisplayProfile();
    }

    DisplayProfileStore.Profile getDisplayProfile() {
        return mDisplayProfiles.getProfile();
    }

    private void refreshDisplayProfile() {
        mDisplayProfiles.refreshForDisplay();
    }

    String getDisplayProfileLabel() {
        return mDisplayProfiles.getDisplayLabel();
    }

    void saveDisplayProfile() {
        mDisplayProfiles.save();
    }

    @Override
    public void onDisplayProfileReset() {
        mWorkspaceAppController.resetProfileState();
        mDesktopWorkspaceController.resetDisplayProfile();
    }

    void launchDefault(final AppItem app) {
        mAppTasks.launchDefault(app);
    }

    void launchFloating(final AppItem app) {
        mAppTasks.launchFloating(app);
    }

    void launchWindowed(final AppItem app) {
        mAppTasks.launchWindowed(app);
    }

    void launchFullscreen(final AppItem app) {
        mAppTasks.launchFullscreen(app);
    }

    void setTaskbarVisible(final boolean visible) {
        mTaskbarVisible = visible;
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.setPolicyVisible(visible);
        } else {
            mTaskbarController.setVisible(visible);
        }
    }

    boolean isTaskbarVisible() {
        return mTaskbarVisible;
    }

    void exitMagicDesk() {
        mSessionController.exit();
    }

    void closeDesktop() {
        mSessionController.closeDesktop();
    }

    void applyDensity(final int dpi) {
        mDisplayDensityController.apply(dpi);
    }

    void applyRecommendedDensity() {
        mDisplayDensityController.applyRecommended();
    }

    void resetDensity() {
        mDisplayDensityController.reset();
    }

    int getRecommendedDesktopDpi() {
        return mDisplayProfiles.getRecommendedDpi();
    }

    private void ensurePreferredConsoleDensity() {
        mDisplayDensityController.ensurePreferred();
    }

    void showLaunchFailure(final Exception e) {
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
        mSystemActions.openDiagnostics();
    }

    @Override
    public int getCurrentDisplayId() {
        final Display display = getWindowManager().getDefaultDisplay();
        return display == null ? 0 : display.getDisplayId();
    }

    @Override
    public int getDesktopProfileDisplayId() {
        return mDesktopProfileDisplayId;
    }

    @Override
    public String getDesktopProfileKey() {
        return mDesktopProfileKey;
    }

    @Override
    public Rect getMaximumWindowBounds() {
        return getWindowManager().getMaximumWindowMetrics().getBounds();
    }

    static void setLaunchWindowingMode(
            final ActivityOptions options,
            final int value) {
        try {
            final Method method = ActivityOptions.class.getMethod(
                    "setLaunchWindowingMode", Integer.TYPE);
            method.invoke(options, Integer.valueOf(value));
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "setLaunchWindowingMode unavailable", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "setLaunchWindowingMode failed", e);
        }
    }

    private boolean isUniversalFreeformEnabled() {
        return Settings.Global.getInt(getContentResolver(),
                "enable_freeform_support", 0) == 1
                && Settings.Global.getInt(getContentResolver(),
                "force_resizable_activities", 0) == 1;
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
        if (mDesktopControls != null) {
            mDesktopControls.setActivityStatus(text);
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

}
