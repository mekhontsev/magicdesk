package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_AMBER;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;

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
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
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
        DisplayProfileController.Host {
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
    static final String EXTRA_ACTIVATION_SOURCE =
            "magicdesk_activation_source";
    static final String EXTRA_SESSION_POLICY = "magicdesk_session_policy";
    private static final String ACTION_SHOW_START = "show_start";
    static final String ACTION_RESTORE_WINDOWS = "restore_windows";
    private static final String STATE_TOOLS_VISIBLE = "tools_visible";
    private static final String STATE_EXPECTED_DISPLAY_ID =
            "expected_display_id";
    private static final String STATE_PROFILE_DISPLAY_ID =
            "profile_display_id";
    private static final String STATE_PROFILE_KEY = "profile_key";
    private static final String STATE_TARGET_KIND = "target_kind";
    private static final String STATE_ACTIVATION_SOURCE =
            "activation_source";
    private static final String STATE_SESSION_POLICY = "session_policy";
    private static final Map<Integer, Integer> EXPECTED_DISPLAY_BY_TASK =
            new HashMap<>();
    static final int TASKBAR_HEIGHT_DP = 64;
    private static final int COMPACT_TASKBAR_HEIGHT_DP = 52;
    private FrameLayout mDesktopRoot;
    private DesktopLayoutController mDesktopLayout;
    private DesktopWallpaperController mDesktopWallpaperController;
    private OverlayPanelController mOverlayPanelController;
    private DesktopUiFactory mUi;
    private DesktopAutomationUiRegistry mAutomationUi;
    private CalendarPanelController mCalendarController;
    private ShortcutHelpController mShortcutHelpController;
    private NotificationCenterController mNotifications;
    private SystemPanelController mSystemPanelController;
    private DisplayProfileController mDisplayProfiles;
    private StartMenuController mStartMenuController;
    private TaskOverviewController mTaskOverviewController;
    private DesktopContextMenuController mContextMenuController;
    private TaskbarController mTaskbarController;
    private DesktopTaskbarHost mTaskbarHost;
    private DesktopTaskbarRevealController mTaskbarRevealController;
    private AltTabController mAltTabController;
    private DesktopWorkspaceController mDesktopWorkspaceController;
    private AppTaskController mAppTasks;
    private DesktopTaskSnapshotController mTaskSnapshots;
    private DisplayDensityController mDisplayDensityController;
    private DesktopControlsController mDesktopControls;
    private MagicDeskSessionController mSessionController;
    private LauncherAppRepository mLauncherApps;
    private DesktopInputController mInputController;
    private DesktopSystemActionsController mSystemActions;
    private DesktopLaunchCoordinator mLaunchCoordinator;
    private final OnBackInvokedCallback mDesktopBackCallback =
            this::handleDesktopBack;
    private boolean mDesktopWindowFocusable = true;
    private boolean mDesktopHostReady;
    private boolean mDesktopBackCallbackRegistered;
    private int mInputFocusRefreshGeneration;
    private boolean mTaskbarVisible = true;
    private boolean mTaskbarAutoHide;
    private boolean mTaskbarImeHold;
    private boolean mTaskbarStartHold;
    private int mExpectedDisplayId = Display.INVALID_DISPLAY;
    private int mDesktopProfileDisplayId = Display.INVALID_DISPLAY;
    private String mDesktopProfileKey = "";
    private DesktopDisplayTarget.Kind mDesktopTargetKind;
    private DesktopDisplayTarget.ActivationSource mActivationSource =
            DesktopDisplayTarget.ActivationSource.UNKNOWN;
    private DesktopSessionPolicy mSessionPolicy = DesktopSessionPolicy.USER;
    private List<AppItem> mLastApps = Collections.emptyList();
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DesktopHomeStartupGuard.shouldDiscardStaleHomeLaunch(
                getIntent())) {
            finishAndRemoveTask();
            return;
        }
        // A newly selected HOME must not inherit an IME left visible by the
        // previous phone task. Applications can still show it normally later.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
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
            mActivationSource = parseActivationSource(source.getString(
                    savedInstanceState == null
                            ? EXTRA_ACTIVATION_SOURCE
                            : STATE_ACTIVATION_SOURCE,
                    ""));
            mSessionPolicy = DesktopSessionPolicy.parse(source.getString(
                    savedInstanceState == null
                            ? EXTRA_SESSION_POLICY : STATE_SESSION_POLICY,
                    ""));
        }
        if (mDesktopTargetKind == null) {
            DesktopDisplayTarget runtimeTarget =
                    DesktopRuntimeBridge.getDesktopTarget(displayId);
            if (runtimeTarget != null) {
                mSessionPolicy = DesktopRuntimeBridge
                        .getSessionSnapshot().policy();
            }
            if (runtimeTarget == null) {
                final DesktopHomeRoleLease.State homeLease =
                        DesktopHomeRoleLease.snapshot();
                if (homeLease != null
                        && homeLease.phase
                                == DesktopHomeRoleLease.Phase.ACTIVE
                        && homeLease.displayId == displayId) {
                    runtimeTarget = homeLease.target();
                    mSessionPolicy = homeLease.policy;
                }
            }
            if (runtimeTarget != null) {
                mDesktopProfileDisplayId = runtimeTarget.profileDisplayId;
                mDesktopProfileKey = runtimeTarget.profileKey;
                mDesktopTargetKind = runtimeTarget.kind;
                mActivationSource = runtimeTarget.activationSource;
            }
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
        if (!DeviceSetupManager.isRuntimeAuthorized()
                && !DesktopHomeRoleLease.isActiveForDisplay(
                        mExpectedDisplayId)) {
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
        if (mDesktopTargetKind != null) {
            DesktopRuntimeBridge.noteDesktopTarget(
                    DesktopDisplayTarget.restore(
                            mDesktopTargetKind,
                            mExpectedDisplayId,
                            mDesktopProfileDisplayId,
                            mDesktopProfileKey,
                            mActivationSource),
                    mSessionPolicy);
        }
        mUi = new DesktopUiFactory(this);
        mAutomationUi = new DesktopAutomationUiRegistry();
        mTaskbarHost = new DesktopTaskbarHost(
                getCurrentDisplayId(),
                bounds -> {
                    if (mOverlayPanelController != null) {
                        mOverlayPanelController
                                .setInteractionOwnerBounds(bounds);
                    }
                });
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
                    public void onImeVisibilityChanged(
                            final boolean visible) {
                        DesktopShellActivity.this
                                .onDesktopImeVisibilityChanged(visible);
                    }

                    @Override
                    public void onViewportChanged() {
                        hideAllPanels();
                        if (mTaskbarRevealController != null) {
                            mTaskbarRevealController.updateViewport();
                        }
                        MagicDeskRuntime.refreshDesktopTasks();
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
        refreshSettings();
        mAltTabController = new AltTabController(this);
        mDesktopWorkspaceController =
                new DesktopWorkspaceController(this, mUi);
        mAppTasks = new AppTaskController(this);
        mTaskSnapshots = new DesktopTaskSnapshotController(this);
        mDisplayDensityController = new DisplayDensityController(this);
        mDesktopControls = new DesktopControlsController(this, mUi);
        mSystemPanelController = new SystemPanelController(this, mUi);
        mSessionController = new MagicDeskSessionController(this);
        mLauncherApps = new LauncherAppRepository(this);
        mInputController = new DesktopInputController(this);
        mSystemActions = new DesktopSystemActionsController(this);
        mLaunchCoordinator = new DesktopLaunchCoordinator(
                new DesktopSessionLaunchContext(this));
        registerDesktopBackCallback();
        DesktopRuntimeBridge.registerDesktop(this);
        setDesktopWindowFocusable(true);
        setContentView(createDesktopContentView());
        mDesktopLayout.onWindowAttached();
        observeDesktopHomeReady();
        DesktopSelfTestHostObserver.observeNextFrame(this, "first-frame");
        mTaskbarRevealController.start();
        mNotifications.start();
        mDesktopControls.start();
        mDisplayProfiles.start();
        MagicDeskRuntime.start(this);
        if (ShellAccess.isReady()) {
            DesktopOperations.refreshHardwareKeyboardLayout();
        }
        renderApps();
        updateDesktopControls();
        handleLaunchAction(getIntent());
        ensurePreferredDesktopDensity();
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
        outState.putString(
                STATE_ACTIVATION_SOURCE,
                mActivationSource.name());
        outState.putString(STATE_SESSION_POLICY, mSessionPolicy.name());
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

    private static DesktopDisplayTarget.ActivationSource
            parseActivationSource(final String value) {
        if (value == null || value.isEmpty()) {
            return DesktopDisplayTarget.ActivationSource.UNKNOWN;
        }
        try {
            return DesktopDisplayTarget.ActivationSource.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DesktopDisplayTarget.ActivationSource.UNKNOWN;
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
        if (mTaskbarHost != null) {
            mTaskbarHost.release();
            mTaskbarHost = null;
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
        unregisterDesktopBackCallback();
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
        if (mDisplayDensityController != null) {
            mDisplayDensityController.close();
        }
        mLastApps = Collections.emptyList();
        if (mStartMenuController != null) {
            mStartMenuController.release();
        }
        mDesktopHostReady = false;
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

    boolean handleDesktopFileKey(final KeyEvent event) {
        return mDesktopWorkspaceController != null
                && mDesktopWorkspaceController.handleKeyboardCommand(
                        FileKeyboardCommand.fromEvent(event));
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

    DesktopTaskbarHost taskbarHost() {
        return mTaskbarHost;
    }

    private void onDesktopImeVisibilityChanged(final boolean visible) {
        mTaskbarImeHold = visible;
        updateTaskbarVisibilityHold();
    }

    private void onOverlayPanelVisibilityChanged(
            final View panel,
            final boolean visible) {
        if (mStartMenuController == null
                || !mStartMenuController.ownsPanel(panel)) {
            return;
        }
        mTaskbarStartHold = visible;
        updateTaskbarVisibilityHold();
    }

    private void updateTaskbarVisibilityHold() {
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.setForcedVisible(
                    mTaskbarImeHold || mTaskbarStartHold);
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

    /** Updates the taskbar indicator owned by platform-specific controls. */
    public void updatePlatformChargeSeparation(final boolean enabled) {
        mTaskbarController.updateChargeSeparation(enabled);
    }

    void scheduleDisplayProfileRefresh() {
        mDisplayProfiles.scheduleRefresh();
    }

    TaskRepository.Snapshot getTaskSnapshot() {
        return mTaskSnapshots.snapshot();
    }

    void clearInteractionVisibleTasks() {
        mAppTasks.clearInteractionStack();
    }

    TaskRepository.Snapshot setTaskSnapshot(
            final TaskRepository.Snapshot snapshot) {
        return mTaskSnapshots.setSnapshot(snapshot);
    }

    boolean isAltTabTaskSelected(final TaskRepository.TaskEntry task) {
        return mAltTabController.isSelected(task);
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
        MagicDeskRuntime.refreshNotification();
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
        ensurePreferredDesktopDensity();
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
            // Phone HOME stops when an application covers it. Panels belong
            // to the desktop surface and must not outlive that surface.
            hideAllPanels();
        }
        super.onStop();
    }

    @Override
    public void onMultiWindowModeChanged(
            final boolean inMultiWindowMode,
            final Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfig);
        DesktopSelfTestHostObserver.observeNextFrame(this, "mode-change");
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mDesktopWorkspaceController.handleActivityResult(
                requestCode, resultCode, data);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mDesktopLayout != null) {
            mDesktopLayout.onWindowAttached();
        }
        refreshDisplayProfile();
        setDesktopWindowFocusable(true);
    }

    void setDesktopWindowFocusable(final boolean focusable) {
        if (mDesktopWindowFocusable == focusable) {
            return;
        }
        mDesktopWindowFocusable = focusable;
        mInputFocusRefreshGeneration++;
        setWindowFocusable(getWindow(), focusable);
    }

    void refreshDesktopInputFocus() {
        refreshDesktopInputFocus(null);
    }

    void refreshDesktopInputFocus(final Runnable completion) {
        if (isActivityUnavailable()) {
            runIfPresent(completion);
            return;
        }
        final Window window = getWindow();
        final View decor = window.getDecorView();
        final int generation = ++mInputFocusRefreshGeneration;
        final boolean finalFocusable = mDesktopWindowFocusable;

        // A real window relayout makes Nubia WMS recompute the focused window;
        // task-level focus operations update only its activity-side state.
        // Pulse away from the requested final state so the same repair works
        // both when focus leaves the host and when it returns to the desktop.
        decor.getViewTreeObserver().registerFrameCommitCallback(() ->
                decor.post(() -> {
                    if (generation != mInputFocusRefreshGeneration
                            || isActivityUnavailable()
                            || mDesktopWindowFocusable != finalFocusable) {
                        runIfPresent(completion);
                        return;
                    }
                    setWindowFocusable(window, finalFocusable);
                    // Completion describes the final requested host relayout,
                    // not merely submission of its LayoutParams.
                    decor.getViewTreeObserver().registerFrameCommitCallback(
                            () -> decor.post(() ->
                                    runIfPresent(completion)));
                    decor.invalidate();
                }));
        setWindowFocusable(window, !finalFocusable);
        decor.invalidate();
    }

    private static void setWindowFocusable(
            final Window window, final boolean focusable) {
        if (focusable) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private static void runIfPresent(final Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    boolean isDesktopHostReady() {
        return mDesktopHostReady;
    }

    private void observeDesktopHomeReady() {
        final View root = mDesktopRoot;
        if (root == null) {
            return;
        }
        root.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        final ViewTreeObserver observer =
                                root.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        if (!isActivityUnavailable()
                                && getCurrentDisplayId() == mExpectedDisplayId) {
                            mDesktopHostReady = true;
                            onDesktopHostReady();
                        }
                        return true;
                    }
                });
    }

    private void onDesktopHostReady() {
        MagicDeskRuntime.onDesktopHostReadyForParkedTasks(
                getCurrentDisplayId());
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mDesktopLayout != null) {
            mDesktopLayout.onWindowFocusChanged(hasFocus);
        }
        if (hasFocus) {
            refreshDisplayProfile();
        }
        refreshTaskSnapshot();
    }

    private void registerDesktopBackCallback() {
        if (mDesktopBackCallbackRegistered) {
            return;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mDesktopBackCallback);
        mDesktopBackCallbackRegistered = true;
    }

    private void unregisterDesktopBackCallback() {
        if (!mDesktopBackCallbackRegistered) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mDesktopBackCallback);
        mDesktopBackCallbackRegistered = false;
    }

    private void handleDesktopBack() {
        // Back may be routed to the desktop Home while no app owns focus.
        // Finishing Home would leave its display without a workspace surface.
        if (hasVisiblePanel()) {
            resetAltTabState();
            hideTopPanel();
            return;
        }
        Log.i(TAG, "ignored Back on desktop host display="
                + getCurrentDisplayId());
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

    void setSystemDialogVisible(final boolean visible) {
        if (!mTaskSnapshots.setSystemDialogVisible(visible)) {
            return;
        }
        try {
            DesktopAutomationEventJournal.record(
                    "ui",
                    "system_dialog_focus_changed",
                    true,
                    "display=" + getCurrentDisplayId(),
                    new org.json.JSONObject()
                            .put("displayId", getCurrentDisplayId())
                            .put("visible", visible));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui",
                    "system_dialog_focus_changed",
                    true,
                    "display=" + getCurrentDisplayId());
        }
    }

    // GestureDetector routes confirmed taps through performClick().
    @SuppressLint("ClickableViewAccessibility")
    private View createDesktopContentView() {
        final FrameLayout root = new FrameLayout(this);
        mDesktopRoot = root;
        mOverlayPanelController = new OverlayPanelController(
                this,
                getCurrentDisplayId(),
                this::onOverlayPanelVisibilityChanged);
        root.setBackgroundColor(COLOR_BACKGROUND);

        final FrameLayout desktopViewport = new FrameLayout(this);

        final ImageView wallpaper = new ImageView(this);
        // The controller renders one display-sized frame. Keeping the image
        // matrix fixed prevents transient system bars from recropping it when
        // the HOME window loses focus to a freeform task.
        wallpaper.setScaleType(ImageView.ScaleType.MATRIX);
        wallpaper.setBackgroundColor(COLOR_BACKGROUND);
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mDesktopWallpaperController = new DesktopWallpaperController(
                this, wallpaper);
        mDesktopWallpaperController.start();

        // Android keeps the phone status bar transparent above freeform
        // tasks. Cover its reserved viewport inset so desktop wallpaper does
        // not reduce the contrast of the system icons.
        final View statusBarBackdrop = new View(this);
        statusBarBackdrop.setBackgroundColor(COLOR_PANEL);
        statusBarBackdrop.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(statusBarBackdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0));
        mDesktopLayout.attachDesktopViews(
                root, desktopViewport, statusBarBackdrop);

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
        registerAutomationUiElement(
                desktop, "desktop", "desktop", "Desktop");

        final DesktopGridLayout desktopIcons =
                mDesktopWorkspaceController.createGrid();
        final LinearLayout.LayoutParams iconsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        desktop.addView(desktopIcons, iconsParams);

        desktopViewport.addView(desktop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(desktopViewport, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mStartMenuController.create();
        final LinearLayout taskbar = mTaskbarController.create();
        if (!mDesktopLayout.attachTaskbar(taskbar, mTaskbarHost)) {
            setErrorStatus("TASKBAR-001",
                    getString(R.string.status_taskbar_unavailable));
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

    boolean showAltTabPanel() {
        return mTaskOverviewController.showAltTabPanel();
    }

    void registerContextTarget(final View view, final AppItem app,
            final TaskRepository.TaskEntry task) {
        mContextMenuController.registerTarget(view, app, task);
    }

    void registerDraggableDesktopAppContextTarget(
            final View view,
            final AppItem app,
            final DesktopFile file) {
        mContextMenuController.registerDraggableDesktopAppTarget(
                view, app, file);
    }

    void registerDraggableFileContextTarget(
            final View view,
            final DesktopFile file) {
        mContextMenuController.registerDraggableFileTarget(view, file);
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

    void showStartButtonContextMenu(final float x, final float y) {
        mContextMenuController.showStartButtonMenu(x, y);
    }

    void showTaskbarContextMenu(final float x, final float y) {
        mContextMenuController.showTaskbarMenu(x, y);
    }

    void showRegisteredContextMenu(final View view) {
        mContextMenuController.showForRegisteredView(view);
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
        refreshSettings();
        MagicDeskRuntime.refreshSettings();
        renderApps();
        updateDesktopControls();
    }

    void addDesktopWidget() {
        mDesktopWorkspaceController.addWidget();
    }

    boolean hasDesktopWidgets(final String packageName) {
        return mDesktopWorkspaceController.hasWidgets(packageName);
    }

    void addDesktopWidgets(final String packageName) {
        mDesktopWorkspaceController.addWidgets(packageName);
    }

    void chooseDesktopWallpaper() {
        hideAllPanels();
        final AppItem files = findOrLoadApp(
                mLastApps, FileManagerActivity.launchTarget(this));
        if (files == null) {
            setErrorStatus(
                    "FILES-003",
                    getString(R.string.status_desktop_folder_open_failed));
            return;
        }
        launchDefault(files);
    }

    void useSystemDesktopWallpaper() {
        hideAllPanels();
        mDesktopWallpaperController.useSystemWallpaper();
    }

    boolean isUsingCustomDesktopWallpaper() {
        return mDesktopWallpaperController != null
                && mDesktopWallpaperController.isUsingCustomWallpaper();
    }

    boolean isDesktopWallpaperRendered() {
        return mDesktopWallpaperController != null
                && mDesktopWallpaperController.isRendered();
    }

    boolean isUsingFallbackDesktopWallpaper() {
        return mDesktopWallpaperController != null
                && mDesktopWallpaperController.isUsingFallbackWallpaper();
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

    void openDesktopFileWith(final DesktopFile file) {
        mDesktopWorkspaceController.openFileWith(file);
    }

    void copyDesktopFile(final DesktopFile file, final boolean move) {
        mDesktopWorkspaceController.copyFile(file, move);
    }

    void pasteDesktopFiles() {
        hideAllPanels();
        mDesktopWorkspaceController.pasteFiles();
    }

    void openClipboardContent() {
        mDesktopWorkspaceController.openClipboardContent();
    }

    void shareClipboardContent() {
        mDesktopWorkspaceController.shareClipboardContent();
    }

    void copyDesktopFilePath(final DesktopFile file) {
        mDesktopWorkspaceController.copyFilePath(file);
    }

    void showDesktopFileProperties(final DesktopFile file) {
        mDesktopWorkspaceController.showFileProperties(file);
    }

    void showDesktopFileProperties(final ShellFileInfo file) {
        hideAllPanels();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(FilePropertiesFormatter.format(this, file))
                .setNeutralButton(
                        R.string.file_manager_copy_path,
                        (ignored, which) -> {
                            AndroidClipboardGateway.get(this).writeText(
                                    file.name, file.absolutePath, false);
                        })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        configureOverlayDialog(dialog);
        dialog.show();
    }

    void showDesktopFolderShortcutProperties(
            final ShellFileInfo file,
            final DesktopFolderShortcut shortcut) {
        hideAllPanels();
        final StringBuilder message = new StringBuilder(
                FilePropertiesFormatter.format(this, file));
        message.append('\n').append(getString(
                R.string.desktop_shortcut_target,
                shortcut.targetPath));
        if (!shortcut.available) {
            message.append('\n').append(getString(
                    R.string.desktop_shortcut_unavailable));
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(shortcut.name)
                .setMessage(message)
                .setNeutralButton(
                        R.string.file_manager_copy_path,
                        (ignored, which) -> {
                            AndroidClipboardGateway.get(this).writeText(
                                    shortcut.name,
                                    shortcut.targetPath,
                                    false);
                        })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        configureOverlayDialog(dialog);
        dialog.show();
    }

    void installDesktopApk(final DesktopFile file) {
        hideAllPanels();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_install_title)
                .setMessage(getString(
                        R.string.file_manager_install_message,
                        ShellDesktopDirectory.ABSOLUTE_PATH
                                + "/" + file.relativePath))
                .setPositiveButton(
                        R.string.file_manager_install_apk,
                        (ignored, which) ->
                                mDesktopWorkspaceController.installApk(file))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        configureOverlayDialog(dialog);
        dialog.show();
    }

    void runDesktopScript(final DesktopFile file) {
        mDesktopWorkspaceController.runScript(file);
    }

    void setDesktopWallpaperFromFile(final DesktopFile file) {
        mDesktopWorkspaceController.setWallpaper(file);
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

    void createCommandApplication() {
        hideAllPanels();
        DesktopCommandApplicationDialog.show(
                this,
                DesktopCommandApplicationDialog.InitialValues.empty(
                        ShellDesktopDirectory.ABSOLUTE_PATH,
                        DesktopExecBackend.SHELL),
                created -> refreshDesktopFolder(true));
    }

    void createCommandApplication(final DesktopFile file) {
        if (file == null || file.directory) {
            return;
        }
        hideAllPanels();
        final String absolutePath = ShellDesktopDirectory.ABSOLUTE_PATH
                + "/" + file.relativePath;
        DesktopCommandApplicationDialog.show(
                this,
                DesktopCommandApplicationDialog.InitialValues.fromFile(
                        file.name,
                        file.mimeType,
                        absolutePath,
                        false),
                created -> refreshDesktopFolder(true));
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
                        file.desktopEntry != null
                                ? R.string.delete_desktop_shortcut_message
                                : file.directory
                                        ? R.string.delete_desktop_folder_message
                                        : R.string.delete_desktop_file_message,
                        file.displayName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.action_delete,
                        (confirmedDialog, which) ->
                                mDesktopWorkspaceController.deleteFile(file))
                .create();
        configureOverlayDialog(dialog);
        dialog.show();
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

    TaskRepository.TaskEntry findFirstTask(final AppLaunchTarget target) {
        return mTaskSnapshots.findFirstTask(target);
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

    List<DesktopApplicationRepository.Entry> getDesktopApplications() {
        return mDesktopWorkspaceController == null
                ? java.util.Collections.emptyList()
                : mDesktopWorkspaceController.desktopApplications();
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

    void configureOverlayDialog(final AlertDialog dialog) {
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

    public void refreshTaskSnapshot() {
        mTaskSnapshots.refresh();
    }

    List<TaskRepository.TaskEntry> findTasks(final String packageName) {
        return mTaskSnapshots.findTasks(packageName);
    }

    boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return mTaskSnapshots.isTaskbarTask(task);
    }

    boolean isAltTabTask(final TaskRepository.TaskEntry task) {
        return isTaskbarTask(task)
                || (DesktopSelfTestController.isRunning()
                        && DesktopSelfTestComponents.isFixtureTask(task));
    }

    AppItem findOrLoadApp(final List<AppItem> apps, final String packageName) {
        return mLauncherApps.findOrLoad(
                apps, packageName, isUniversalFreeformEnabled());
    }

    AppItem findOrLoadApp(
            final List<AppItem> apps,
            final TaskRepository.TaskEntry task) {
        final BuiltInDesktopAppCatalog.Entry builtIn =
                BuiltInDesktopAppCatalog.find(task);
        return builtIn != null
                ? mLauncherApps.findOrLoad(
                        apps,
                        builtIn.launchTarget,
                        isUniversalFreeformEnabled())
                : findOrLoadApp(apps, task.packageName);
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

    void addDesktopShortcut(
            final AppItem app,
            final AppShortcutAction shortcut) {
        mDesktopWorkspaceController.addDesktopShortcut(app, shortcut);
    }

    void focusTask(final AppItem app, final TaskRepository.TaskEntry task) {
        mAppTasks.focusTask(app, task);
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final Runnable completion) {
        mAppTasks.focusTask(app, task, completion);
    }

    void toggleTaskbarTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        if (task != null && !task.active) {
            mAltTabController.activateTask(task.taskId);
            return;
        }
        mAppTasks.toggleTaskbarTask(app, task);
    }

    void advanceAltTab(final boolean reverse) {
        mAltTabController.advance(reverse);
    }

    void finishAltTab() {
        mAltTabController.finish();
    }

    void finishTaskbarActivation() {
        if (!PlatformDrivers.current().windowing()
                .requiresTaskActivationSurfaceFence()) {
            mAltTabController.finish();
            return;
        }
        final OverlayPanelController overlays = overlayPanels();
        if (overlays == null || !overlays.runAfterSurfaceTraversalFence(
                mAltTabController::finish)) {
            mAltTabController.finish();
        }
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

    void openSettings() {
        mSystemActions.openSettings();
    }

    void openFiles() {
        mSystemActions.openFiles();
    }

    void openConsole() {
        mSystemActions.openConsole();
    }

    void openTermuxConsole() {
        mSystemActions.openTermuxConsole();
    }

    void openConsole(
            final String directory,
            final String command,
            final String terminalId,
            final DesktopExecBackend backend) {
        android.content.Intent intent = command == null
                || command.isEmpty()
                ? CommandConsoleActivity.createIntentAtDirectory(
                        this, directory, backend)
                : CommandConsoleActivity.createPreparedCommandIntent(
                        this, command, directory, backend);
        if (terminalId != null && !terminalId.isEmpty()) {
            intent = CommandConsoleActivity.withTerminalId(intent, terminalId);
        }
        mSystemActions.openConsole(intent);
    }

    void openTaskManager() {
        mSystemActions.openTaskManager();
    }

    void launchInternalWindow(
            final android.content.Intent intent,
            final AppLaunchTarget target,
            final String label) {
        mAppTasks.launchInternalWindow(intent, target, label);
    }

    void toggleShortcutHelp() {
        mShortcutHelpController.toggle(
                mOverlayPanelController,
                mDesktopLayout.viewport().contentBounds(),
                getTaskbarHeight());
    }

    void hideAllPanels() {
        if (mTaskOverviewController != null) {
            mTaskOverviewController.cancelPendingShow();
        }
        if (mOverlayPanelController != null) {
            mOverlayPanelController.hideAll();
        }
    }

    void hideTopPanel() {
        if (mTaskOverviewController != null) {
            mTaskOverviewController.cancelPendingShow();
        }
        if (mOverlayPanelController != null) {
            mOverlayPanelController.hideTop();
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

    boolean isTaskbarAutoHideEnabled() {
        return mTaskbarAutoHide;
    }

    void toggleTaskbarAutoHide() {
        hideAllPanels();
        if (!MagicDeskSettings.setTaskbarAutoHide(!mTaskbarAutoHide)) {
            setStatus(R.string.settings_save_failed);
            return;
        }
        refreshSettings();
    }

    DesktopViewport getDesktopViewport() {
        return mDesktopLayout.viewport();
    }

    Rect getTaskbarBounds() {
        return mDesktopLayout.taskbarBounds();
    }

    DesktopUiSnapshot getAutomationUiSnapshot() {
        final OverlayPanelController overlays = mOverlayPanelController;
        return new DesktopUiSnapshot(
                !isActivityUnavailable() && isDesktopShell(),
                getCurrentDisplayId(),
                isTaskbarVisible(),
                getTaskbarBounds(),
                mStartMenuController != null
                        && mStartMenuController.isVisible(),
                overlays != null && overlays.hasVisiblePanel(),
                overlays == null ? null : overlays.visibleBounds(),
                overlays == null ? "" : overlays.visibleTitle(),
                isDesktopWallpaperRendered(),
                isUsingFallbackDesktopWallpaper());
    }

    DesktopAutomationUiRegistry.Snapshot getAutomationUiElements(
            final String query,
            final boolean includeHidden) throws org.json.JSONException {
        if (mAutomationUi == null || isActivityUnavailable()) {
            return DesktopAutomationUiRegistry.Snapshot.UNAVAILABLE;
        }
        return mAutomationUi.snapshot(
                getCurrentDisplayId(), query, includeHidden);
    }

    DesktopAutomationUiRegistry.ActionResult invokeAutomationUiAction(
            final String elementId,
            final String action) throws org.json.JSONException {
        if (mAutomationUi == null || isActivityUnavailable()) {
            return new DesktopAutomationUiRegistry.ActionResult(
                    false, "desktop UI is unavailable", null);
        }
        return mAutomationUi.invoke(elementId, action);
    }

    void registerAutomationUiElement(
            final View view,
            final String id,
            final String role,
            final CharSequence label) {
        if (mAutomationUi != null) {
            mAutomationUi.register(view, id, role, label);
        }
    }

    void registerAutomationUiElement(
            final View view,
            final String id,
            final String role,
            final CharSequence label,
            final String packageName,
            final int taskId) {
        if (mAutomationUi != null) {
            mAutomationUi.register(
                    view, id, role, label, packageName, taskId);
        }
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
        mDesktopWorkspaceController.resetDisplayProfile();
    }

    void launchDefault(final AppItem app) {
        if (mLaunchCoordinator.launchIntegratedDefault(app)) {
            return;
        }
        mAppTasks.launchDefault(app);
    }

    void invokeLaunchIntegrationAction(
            final AppItem app,
            final DesktopLaunchIntegrationAction action) {
        if (app == null || action == null) {
            return;
        }
        DesktopLaunchIntegrationRegistry.invokeAction(
                this,
                app.launchTarget,
                action.id,
                (success, message) -> runOnUiThread(() -> Toast.makeText(
                        this,
                        message,
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                        .show()));
    }

    void launchDefault(final AppItem app, final Runnable onPrepared) {
        mAppTasks.launchDefault(app, onPrepared);
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final Runnable onPrepared) {
        mAppTasks.launchForMode(app, mode, onPrepared);
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final RelativeWindowBounds preferredBounds,
            final Runnable onPrepared) {
        mAppTasks.launchForMode(
                app, mode, preferredBounds, onPrepared);
    }

    void launchFloating(final AppItem app) {
        mAppTasks.launchFloating(app);
    }

    void launchWindowed(final AppItem app) {
        mAppTasks.launchWindowed(app);
    }

    void launchNewWindow(final AppItem app) {
        mAppTasks.launchNewWindow(app);
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut) {
        mAppTasks.launchShortcut(app, shortcut);
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut,
            final DesktopLaunchMode launchMode) {
        mAppTasks.launchShortcut(app, shortcut, launchMode);
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut,
            final AppTaskController.LaunchCompletion completion) {
        mAppTasks.launchShortcut(
                app, shortcut, DesktopLaunchMode.AUTO, completion);
    }

    boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut) {
        return mLaunchCoordinator.launchShortcut(shortcut);
    }

    boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath) {
        return mLaunchCoordinator.launchShortcut(
                shortcut, arguments, desktopFilePath);
    }

    void launchResolvedAndroidIntent(
            final AppItem app,
            final String name,
            final Intent intent,
            final AppLaunchTarget taskTarget,
            final DesktopLaunchMode mode) {
        mAppTasks.launchIntent(app, name, intent, taskTarget, mode);
    }

    boolean launchDesktopWebShortcut(final DesktopWebShortcut shortcut) {
        if (shortcut == null) {
            return false;
        }
        final DesktopApplicationShortcut resolved =
                shortcut.resolveApplicationShortcut(getPackageManager());
        if (resolved != null && launchDesktopShortcut(resolved)) {
            return true;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getCurrentDisplayId());
        try {
            startActivity(shortcut.createViewIntent(), options.toBundle());
            return true;
        } catch (RuntimeException error) {
            setErrorStatus(
                    "APP-LAUNCH-003",
                    getString(R.string.status_launch_failed, shortcut.name),
                    "url=" + shortcut.url,
                    error);
            return false;
        }
    }

    boolean launchAutomationRequest(final DesktopLaunchRequest request) {
        return mLaunchCoordinator.launch(request);
    }

    void openFilesAt(final String path) {
        launchInternalWindow(
                FileManagerActivity.createIntent(this, path),
                BuiltInDesktopAppCatalog.filesTarget(),
                getString(R.string.file_manager_title));
    }

    void openAppInfo(final AppItem app) {
        mAppTasks.openAppInfo(app);
    }

    void launchFullscreen(final AppItem app) {
        mAppTasks.launchFullscreen(app);
    }

    void setTaskbarVisible(final boolean visible) {
        // Keep policy separate from presentation: auto-hide may collapse a
        // policy-visible taskbar to its reveal edge.
        final boolean changed = mTaskbarVisible != visible;
        mTaskbarVisible = visible;
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.setPolicyVisible(visible);
        } else {
            mTaskbarController.setVisible(visible);
        }
        if (changed) {
            DesktopSelfTestHostObserver.noteTaskbarVisibilityChanged(
                    getCurrentDisplayId(), visible);
            try {
                DesktopAutomationEventJournal.record(
                        "ui",
                        visible ? "taskbar_shown" : "taskbar_hidden",
                        true,
                        "display=" + getCurrentDisplayId(),
                        new org.json.JSONObject()
                                .put("displayId", getCurrentDisplayId())
                                .put("visible", visible));
            } catch (org.json.JSONException ignored) {
                DesktopAutomationEventJournal.record(
                        "ui",
                        visible ? "taskbar_shown" : "taskbar_hidden",
                        true,
                        "display=" + getCurrentDisplayId());
            }
        }
    }

    boolean isTaskbarVisible() {
        return mTaskbarVisible;
    }

    void refreshSettings() {
        final MagicDeskSettings.Values settings = MagicDeskSettings.load();
        mTaskbarAutoHide = settings.taskbarAutoHide;
        if (mTaskbarRevealController != null) {
            mTaskbarRevealController.setAutoHide(mTaskbarAutoHide);
        }
        if (mDesktopWorkspaceController != null) {
            mDesktopWorkspaceController.refreshSettings(settings);
        }
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

    private void ensurePreferredDesktopDensity() {
        mDisplayDensityController.ensurePreferred();
    }

    void showLaunchFailure(final Exception e) {
        Log.w(TAG, "launch failed", e);
        final String message = conciseLaunchFailure(e);
        final String userMessage = getString(
                R.string.status_launch_failed, message);
        CompatibilityDiagnostics.record(
                "APP-LAUNCH-001",
                userMessage,
                "activityDisplay=" + getCurrentDisplayId()
                        + ", "
                        + CompatibilityDiagnostics
                                .desktopTaskRuntimeDetail(),
                e);
        Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show();
    }

    private static String conciseLaunchFailure(final Throwable error) {
        String message = ShellAccess.usefulMessage(error).trim();
        final int remoteStack = message.indexOf(": Remote stack trace:");
        if (remoteStack >= 0) {
            message = message.substring(0, remoteStack);
        }
        final int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline).trim();
    }

    void openDiagnostics() {
        mSystemActions.openDiagnostics();
    }

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

    public void setStatus(final int stringResId) {
        setStatus(getString(stringResId));
    }

    public void setErrorStatus(final String code, final String message) {
        setErrorStatus(code, message, "", null);
    }

    public void setErrorStatus(final String code, final String message,
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
