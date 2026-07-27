package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_AMBER;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_RED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class MainActivity extends Activity {
    private static final String TAG = "MagicDesk";
    private static final String AM = "/system/bin/am";
    private static final String TOOLS_KEYBOARD_WATCHER_SERVICE =
            "io.github.mekhontsev.magicdesk/.KeyboardWatcherService";
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
    private static final long SHORTCUT_RESTART_DELAY_MILLIS = 800;
    private static final int MAX_DESKTOP_FILES = 30;
    static final int TASKBAR_HEIGHT_DP = 64;
    private static final int COMPACT_TASKBAR_HEIGHT_DP = 52;
    private static WeakReference<MainActivity> sDesktopInstance =
            new WeakReference<>(null);

    private LinearLayout mContent;
    private FrameLayout mDesktopRoot;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
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
    private DisplayDensityController mDisplayDensityController;
    private ConsoleControlsController mConsoleControls;
    private LauncherAppRepository mLauncherApps;
    private DesktopFileRepository mDesktopFileRepository;
    private DesktopItemsController mDesktopItemsController;
    private TaskRepository.Snapshot mTaskSnapshot = new TaskRepository.Snapshot(
            Collections.<TaskRepository.TaskEntry>emptyList(), false, "not loaded");
    private boolean mDesktopMode;
    private boolean mPanelBackDown;
    private boolean mContextButtonDown;
    private boolean mContextButtonTouchSequence;
    private boolean mDesktopWindowFocusable = true;
    private boolean mExitInProgress;
    private int mTaskRefreshGeneration;
    private float mLastPointerX;
    private float mLastPointerY;
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
        mUi = new DesktopUiFactory(this);
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
        mDisplayDensityController = new DisplayDensityController(this);
        mConsoleControls = new ConsoleControlsController(this, mUi);
        mLauncherApps = new LauncherAppRepository(this);
        mDesktopFileRepository =
                new DesktopFileRepository(getContentResolver(), MAX_DESKTOP_FILES);
        mDesktopItemsController = new DesktopItemsController(
                this, mUi, mDesktopFileRepository);
        mDesktopMode = isDesktopMode();
        if (mDesktopMode) {
            replaceDesktopInstance();
            sDesktopInstance = new WeakReference<>(this);
            setDesktopWindowFocusable(true);
        }
        setContentView(createContentView());
        if (mDesktopMode) {
            mNotifications.start();
        }
        mConsoleControls.start();
        mDisplayProfiles.start();
        if (RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT)) {
            KeyboardWatcherService.start(this);
            ConsoleModeSwitcher.refreshHardwareKeyboardLayout();
        } else {
            KeyboardWatcherService.stop(this);
            RootKeyboardShortcutWatcher.stop();
            ConsoleModeSwitcher.closeRootShell();
        }
        renderApps();
        updateConsoleControls();
        handleLaunchAction(getIntent());
        ensurePreferredConsoleDensity();
    }

    private void replaceDesktopInstance() {
        final MainActivity previous = sDesktopInstance.get();
        if (previous == null || previous == this) {
            return;
        }
        // Nubia may migrate the phone task before the dedicated Console HOME starts.
        Log.i(TAG, "replacing desktop shell task=" + previous.getTaskId()
                + " with task=" + getTaskId());
        previous.releaseDesktopOverlays();
        if (previous.getTaskId() != getTaskId() && !previous.isFinishing()) {
            previous.finishAndRemoveTask();
        }
    }

    private void releaseDesktopOverlays() {
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
        mTaskRefreshGeneration++;
        mDesktopItemsController.cancel();
        if (mDisplayProfiles != null) {
            mDisplayProfiles.stop();
        }
        releaseDesktopOverlays();
        if (sDesktopInstance.get() == this) {
            sDesktopInstance.clear();
        }
        mConsoleControls.stop();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (handleDesktopMouseTouchEvent(event, false)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(final MotionEvent event) {
        if (handleDesktopMouseGenericEvent(event, false)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    boolean handleDesktopMouseTouchEvent(final MotionEvent event,
            final boolean useRawCoordinates) {
        if (!mDesktopMode || event == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !hasVisiblePanel()) {
            mAppTasks.captureInteractionStackForPanel();
        }
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        updateLastPointer(event, useRawCoordinates);
        final int action = event.getActionMasked();
        final boolean contextButtonDown = hasContextButtonState(event);

        // A missing ACTION_UP must not consume the next primary click.
        if (mContextButtonTouchSequence && action == MotionEvent.ACTION_DOWN
                && !contextButtonDown) {
            resetContextButtonState();
        }
        if (action == MotionEvent.ACTION_DOWN && contextButtonDown) {
            mContextButtonTouchSequence = true;
            beginContextButtonClick();
            return true;
        }
        if (mContextButtonTouchSequence) {
            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                resetContextButtonState();
            }
            return true;
        }
        return false;
    }

    boolean handleDesktopMouseGenericEvent(final MotionEvent event,
            final boolean useRawCoordinates) {
        if (!mDesktopMode || event == null || !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        updateLastPointer(event, useRawCoordinates);

        final int action = event.getActionMasked();
        final boolean contextButtonState = hasContextButtonState(event);
        final boolean contextPress = (action == MotionEvent.ACTION_BUTTON_PRESS
                && isContextActionButton(event))
                || (action == MotionEvent.ACTION_DOWN && contextButtonState)
                || (contextButtonState && !mContextButtonDown);
        if (contextPress) {
            beginContextButtonClick();
            return true;
        }
        if ((action == MotionEvent.ACTION_BUTTON_RELEASE
                && isContextActionButton(event))
                || (action == MotionEvent.ACTION_UP && mContextButtonDown)
                || (mContextButtonDown && !contextButtonState)) {
            if (!mContextButtonTouchSequence) {
                mContextButtonDown = false;
            }
            return true;
        }
        return false;
    }

    private void updateLastPointer(final MotionEvent event, final boolean useRawCoordinates) {
        mLastPointerX = useRawCoordinates ? event.getRawX() : event.getX();
        mLastPointerY = useRawCoordinates ? event.getRawY() : event.getY();
    }

    private boolean hasContextButtonState(final MotionEvent event) {
        return (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
    }

    private boolean isContextActionButton(final MotionEvent event) {
        return event.getActionButton() == MotionEvent.BUTTON_SECONDARY;
    }

    private void beginContextButtonClick() {
        if (mContextButtonDown) {
            return;
        }
        mContextButtonDown = true;
        captureInteractionStackForPanel();
        handleSecondaryClick(mLastPointerX, mLastPointerY);
    }

    private void resetContextButtonState() {
        mContextButtonTouchSequence = false;
        mContextButtonDown = false;
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (mDesktopMode && (keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && !RootKeyboardShortcutWatcher.isRunning()) {
                captureInteractionStackForPanel();
                toggleStartMenu();
            }
            return true;
        }
        if (mDesktopMode && keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                captureInteractionStackForPanel();
                showDesktopContextMenu(
                        getResources().getDisplayMetrics().widthPixels / 2f,
                        getResources().getDisplayMetrics().heightPixels / 2f);
            }
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                && (hasVisiblePanel() || mPanelBackDown)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && hasVisiblePanel()) {
                mPanelBackDown = true;
                resetAltTabState();
                hideAllPanels();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && mPanelBackDown) {
                mPanelBackDown = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleSecondaryClick(final float x, final float y) {
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
        return mDesktopMode;
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
        return mTaskSnapshot;
    }

    String getWorkspacePackage() {
        return getWorkspaceProfile().workspacePackage;
    }

    void clearInteractionVisibleTasks() {
        mAppTasks.clearInteractionStack();
    }

    void setTaskSnapshot(final TaskRepository.Snapshot snapshot) {
        mTaskSnapshot = snapshot;
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
        refreshWorkspaceProfileForDisplay();
        resolveMonitorIdentityAsync();
        setDesktopWindowFocusable(true);
        setTaskbarVisible(true);
        renderApps();
        refreshDesktopFolder(true);
        updateConsoleControls();
        if (mDesktopMode) {
            mNotifications.refresh();
        }
        ensurePreferredConsoleDensity();
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

    private void setDesktopWindowFocusable(final boolean focusable) {
        if (!mDesktopMode || mDesktopWindowFocusable == focusable) {
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
        if (mDesktopMode) {
            refreshTaskSnapshot();
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchAction(intent);
    }

    private void handleLaunchAction(final Intent intent) {
        if (intent == null || !mDesktopMode) {
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

    static Intent createLaunchIntent(final android.content.Context context) {
        return DeviceSetupActivity.createLaunchIntent(context);
    }

    static Intent createShowStartIntent(final Context context) {
        return createLaunchIntent(context).putExtra(EXTRA_ACTION, ACTION_SHOW_START);
    }

    static boolean showStartOverlayIfRunning() {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode || activity.mOverlayPanelController == null) {
            return false;
        }
        activity.runOnUiThread(() -> {
            activity.captureInteractionStackForPanel();
            activity.setStartMenuVisible(true);
        });
        return true;
    }

    static boolean advanceAltTabIfRunning(final boolean reverse) {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode || activity.mOverlayPanelController == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.advanceAltTab(reverse));
        return true;
    }

    static boolean finishAltTabIfRunning() {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode) {
            return false;
        }
        activity.runOnUiThread(activity::finishAltTab);
        return true;
    }

    static boolean cancelAltTabIfRunning() {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode) {
            return false;
        }
        activity.runOnUiThread(() -> {
            activity.resetAltTabState();
            if (activity.mTaskOverviewController.isVisible()) {
                activity.hideAllPanels();
            }
        });
        return true;
    }

    static boolean toggleShortcutHelpIfRunning() {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode || activity.mOverlayPanelController == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleShortcutHelp);
        return true;
    }

    static boolean toggleNotificationCenterIfRunning() {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode || activity.mOverlayPanelController == null) {
            return false;
        }
        activity.runOnUiThread(activity.mNotifications::toggle);
        return true;
    }

    static boolean isDesktopReadyOnDisplay(final int displayId) {
        final MainActivity activity = sDesktopInstance.get();
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                        || !activity.isDestroyed())
                && activity.mDesktopMode
                && activity.getCurrentDisplayId() == displayId
                && !activity.isInMultiWindowMode();
    }

    static void syncTaskbarWithSnapshot(final int displayId,
            final TaskRepository.Snapshot snapshot) {
        final MainActivity activity = sDesktopInstance.get();
        if (activity == null || snapshot == null || !snapshot.rootAvailable
                || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())
                || !activity.mDesktopMode || activity.getCurrentDisplayId() != displayId) {
            return;
        }

        TaskRepository.TaskEntry activeTask = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.active) {
                activeTask = task;
                break;
            }
        }
        final boolean visible = activeTask == null || activeTask.isFreeform()
                || activity.getPackageName().equals(activeTask.packageName);
        final boolean hasActiveTask = activeTask != null;
        final boolean desktopActive = hasActiveTask
                && activity.getPackageName().equals(activeTask.packageName);
        activity.runOnUiThread(() -> {
            activity.mTaskSnapshot = snapshot;
            activity.mWorkspaceController.syncSnapshot(snapshot);
            activity.renderTaskbarPins(activity.mLastApps);
            activity.setTaskbarVisible(visible);
            if (hasActiveTask) {
                activity.setDesktopWindowFocusable(desktopActive);
            }
        });
    }

    private View createContentView() {
        return mDesktopMode ? createDesktopContentView() : createPhoneContentView();
    }

    private View createDesktopContentView() {
        final FrameLayout root = new FrameLayout(this);
        mDesktopRoot = root;
        mOverlayPanelController = new OverlayPanelController(
                this, getCurrentDisplayId());
        root.setBackgroundColor(COLOR_BACKGROUND);

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
        final int taskbarHeight = getTaskbarHeight();
        final int taskbarWidth = Math.max(1, getDesktopAreaWidth());
        final int taskbarTop = getTaskbarTop(getDesktopAreaHeight());
        if (!mOverlayPanelController.attachPersistent(taskbar,
                0, taskbarTop, taskbarWidth, taskbarHeight,
                "MagicDesk taskbar")) {
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
                getDesktopAreaWidth(),
                getDesktopAreaHeight(),
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
        for (final TaskRepository.TaskEntry task : mTaskSnapshot.tasks) {
            if (isTaskbarTask(task) && packageName.equals(task.packageName)) {
                return task;
            }
        }
        return null;
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

    private View createPhoneContentView() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        final ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_magicdesk);
        header.addView(appIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);
        final TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(this);
        mStatus.setText(R.string.status_loading);
        mStatus.setTextColor(COLOR_MUTED);
        mStatus.setTextSize(13);
        titleBlock.addView(mStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        header.addView(titleBlock, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button desktopMode = new Button(this);
        desktopMode.setText(R.string.action_layout_desktop);
        desktopMode.setAllCaps(false);
        desktopMode.setTextColor(Color.WHITE);
        desktopMode.setSingleLine(true);
        desktopMode.setEllipsize(TextUtils.TruncateAt.END);
        desktopMode.setBackground(rounded(COLOR_PANEL_ALT, dp(10), COLOR_CYAN));
        desktopMode.setOnClickListener(
                view -> setLayoutMode(DesktopPreferences.LAYOUT_DESKTOP));
        final LinearLayout.LayoutParams desktopModeParams = new LinearLayout.LayoutParams(
                dp(112), LinearLayout.LayoutParams.WRAP_CONTENT);
        desktopModeParams.setMargins(0, 0, dp(8), 0);
        header.addView(desktopMode, desktopModeParams);

        final Button refresh = new Button(this);
        refresh.setText(R.string.action_refresh);
        refresh.setAllCaps(false);
        refresh.setTextColor(Color.WHITE);
        refresh.setBackground(rounded(COLOR_PANEL_ALT, dp(10), COLOR_CYAN));
        refresh.setOnClickListener(view -> renderApps());
        header.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(16), 0, dp(12));

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mContent, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    void renderApps() {
        final List<AppItem> apps =
                mLauncherApps.load(isUniversalFreeformEnabled());
        mLastApps = apps;
        final List<AppItem> floating = new ArrayList<>();
        final List<AppItem> fullscreen = new ArrayList<>();
        for (final AppItem app : apps) {
            if (app.canFloat) {
                floating.add(app);
            }
            fullscreen.add(app);
        }

        if (apps.isEmpty()) {
            setStatus(R.string.status_no_apps);
            return;
        }

        setStatus(getString(R.string.status_ready,
                Integer.valueOf(floating.size()),
                Integer.valueOf(fullscreen.size()),
                Integer.valueOf(getCurrentDisplayId())));
        if (mDesktopMode) {
            renderDesktop(apps);
            return;
        }
        if (mContent == null) {
            return;
        }
        mContent.removeAllViews();
        addDock(apps);
        addTools();
        addSection(R.string.section_floating, floating, true);
        addSection(R.string.section_fullscreen, fullscreen, false);
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
        final int generation = ++mTaskRefreshGeneration;
        final int displayId = getCurrentDisplayId();
        TaskRepository.load(displayId, new TaskRepository.SnapshotCallback() {
            @Override
            public void onLoaded(final TaskRepository.Snapshot snapshot) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != mTaskRefreshGeneration || isFinishing()
                                || isDestroyed()) {
                            return;
                        }
                        mTaskSnapshot = snapshot;
                        mWorkspaceController.syncSnapshot(snapshot);
                        renderTaskbarPins(mLastApps);
                        updateConsoleControls();
                    }
                });
            }
        });
    }

    List<TaskRepository.TaskEntry> findTasks(final String packageName) {
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : mTaskSnapshot.tasks) {
            if (isTaskbarTask(task) && packageName.equals(task.packageName)) {
                result.add(task);
            }
        }
        return result;
    }

    boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return task != null && !task.home && task.packageName != null
                && !getPackageName().equals(task.packageName);
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

    private void advanceAltTab(final boolean reverse) {
        mAltTabController.advance(reverse);
    }

    private void finishAltTab() {
        mAltTabController.finish();
    }

    void resetAltTabState() {
        mAltTabController.reset();
    }

    void openTaskFullscreen(final AppItem app,
            final TaskRepository.TaskEntry task) {
        mAppTasks.openTaskFullscreen(app, task);
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

    private void restoreLastVisibleWindows() {
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

    private void setStartMenuVisible(final boolean visible) {
        mStartMenuController.setVisible(visible);
    }

    void toggleToolsMenu() {
        mStartMenuController.toggleTools();
    }

    private void toggleShortcutHelp() {
        mShortcutHelpController.toggle(
                mOverlayPanelController,
                getDesktopAreaWidth(),
                getDesktopAreaHeight(),
                getTaskbarHeight());
    }

    void hideAllPanels() {
        if (mOverlayPanelController != null) {
            mOverlayPanelController.hideAll();
        }
    }

    int getDesktopAreaWidth() {
        return mDesktopRoot != null && mDesktopRoot.getWidth() > 0
                ? mDesktopRoot.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
    }

    int getDesktopAreaHeight() {
        return mDesktopRoot != null && mDesktopRoot.getHeight() > 0
                ? mDesktopRoot.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
    }

    int getTaskbarHeight() {
        return desktopDp(TASKBAR_HEIGHT_DP, COMPACT_TASKBAR_HEIGHT_DP);
    }

    private int getTaskbarTop(final int areaHeight) {
        return Math.max(0, areaHeight - getTaskbarHeight());
    }

    private void updateConsoleControls() {
        mConsoleControls.update();
    }

    void togglePhoneScreen() {
        mConsoleControls.togglePhoneScreen();
    }

    private void addDock(final List<AppItem> apps) {
        final List<AppItem> favorites = new ArrayList<>();
        for (final String packageName : DesktopPreferences.favoritePackages()) {
            final AppItem app = LauncherAppRepository.find(apps, packageName);
            if (app != null) {
                favorites.add(app);
            }
        }
        if (favorites.isEmpty()) {
            return;
        }

        final TextView title = sectionTitle(R.string.section_dock);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(8));
        mContent.addView(title, titleParams);

        final LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setBackground(rounded(COLOR_PANEL, dp(16), COLOR_PANEL_ALT));

        for (final AppItem app : favorites) {
            dock.addView(createDockItem(app), new LinearLayout.LayoutParams(
                    0, dp(82), 1));
        }
        mContent.addView(dock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View createDockItem(final AppItem app) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(6), dp(6), dp(6), dp(4));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> launchDefault(app));

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        item.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private void addTools() {
        final TextView title = sectionTitle(R.string.section_tools);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(16), 0, dp(8));
        mContent.addView(title, titleParams);

        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(rounded(COLOR_PANEL, dp(16), COLOR_PANEL_ALT));

        populateToolsControls(panel, dp(8));

        mContent.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void populateToolsControls(
            final LinearLayout parent,
            final int spacing) {
        mConsoleControls.populate(parent, spacing);
    }

    private void addSection(final int titleResId, final List<AppItem> apps,
            final boolean floating) {
        if (apps.isEmpty()) {
            return;
        }

        final TextView title = sectionTitle(titleResId);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(12), 0, dp(8));
        mContent.addView(title, titleParams);

        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(getColumnCount());
        grid.setUseDefaultMargins(false);
        final int tileWidth = getTileWidth(grid.getColumnCount());
        for (final AppItem app : apps) {
            grid.addView(createAppTile(app, floating), createTileParams(tileWidth));
        }

        mContent.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View createAppTile(final AppItem app, final boolean floating) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(12), dp(10), dp(10));
        tile.setBackground(rounded(COLOR_PANEL, dp(14),
                floating ? COLOR_CYAN : COLOR_PANEL_ALT));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            if (floating) {
                launchFloating(app);
            } else {
                launchFullscreen(app);
            }
        });

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        tile.addView(label, labelParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final Button primary = createSmallButton(
                floating ? R.string.badge_window : R.string.action_open,
                floating ? COLOR_CYAN : COLOR_PANEL_ALT);
        primary.setOnClickListener(view -> {
            if (floating) {
                launchFloating(app);
            } else {
                launchFullscreen(app);
            }
        });
        actions.addView(primary, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (floating) {
            final Button fullscreenButton = createSmallButton(
                    R.string.action_fullscreen_short, COLOR_PANEL_ALT);
            fullscreenButton.setOnClickListener(view -> launchFullscreen(app));
            final LinearLayout.LayoutParams fullscreenParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            fullscreenParams.setMargins(dp(6), 0, 0, 0);
            actions.addView(fullscreenButton, fullscreenParams);
        }
        final LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(8), 0, 0);
        tile.addView(actions, actionParams);

        return tile;
    }

    private GridLayout.LayoutParams createTileParams(final int width) {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = width;
        params.height = dp(154);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private boolean isDesktopMode() {
        final SessionProfile.DisplayTarget displayTarget =
                RuntimeAccess.profile().displayTarget;
        if (displayTarget == SessionProfile.DisplayTarget.PRIMARY
                || displayTarget == SessionProfile.DisplayTarget.EXTERNAL) {
            return true;
        }
        final int override = getLayoutMode();
        if (override == DesktopPreferences.LAYOUT_DESKTOP) {
            return true;
        }
        if (override == DesktopPreferences.LAYOUT_PHONE) {
            return false;
        }
        return getResources().getConfiguration().screenWidthDp >= 700;
    }

    private int getLayoutMode() {
        return getWorkspaceProfile().layoutMode;
    }

    void setLayoutMode(final int mode) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.layoutMode = mode;
        saveWorkspaceProfile();
        DesktopPreferences.saveLegacyLayoutMode(this, mode);
        recreate();
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

    private int getColumnCount() {
        final int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 1100) {
            return 7;
        }
        if (widthDp >= 840) {
            return 5;
        }
        if (widthDp >= 600) {
            return 4;
        }
        return 2;
    }

    private int getTileWidth(final int columns) {
        final int available = getResources().getDisplayMetrics().widthPixels
                - dp(36) - columns * dp(10);
        return Math.max(dp(128), available / Math.max(columns, 1));
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
        if (mDesktopMode) {
            mTaskbarController.setVisible(visible);
        }
    }

    void restartConsoleShortcuts() {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT)) {
            return;
        }
        setStatus(R.string.status_restarting_shortcuts);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runRootCommandBestEffort(AM + " stopservice -n "
                            + TOOLS_KEYBOARD_WATCHER_SERVICE);
                    Thread.sleep(SHORTCUT_RESTART_DELAY_MILLIS);
                    runRootCommand(AM + " start-foreground-service -n "
                            + TOOLS_KEYBOARD_WATCHER_SERVICE);
                    runOnUiThread(() -> {
                        setStatus(R.string.status_shortcuts_restarted);
                        updateConsoleControls();
                        refreshTaskSnapshot();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> setErrorStatus(
                            "SHORTCUTS-001",
                            getString(R.string.status_root_failed, e.getMessage()),
                            "",
                            e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    runOnUiThread(() -> setErrorStatus(
                            "SHORTCUTS-002",
                            getString(R.string.status_root_failed, "interrupted"),
                            "",
                            e));
                }
            }
        }, "MagicDeskRestartShortcuts").start();
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
        if (mExitInProgress) {
            return;
        }
        mExitInProgress = true;
        Log.i(TAG, "full MagicDesk exit requested");
        setStatus(R.string.status_exiting);
        if (!RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            finishUnprivilegedExit();
            return;
        }
        ConsoleModeSwitcher.setPhoneScreenOff(false,
                new ConsoleModeSwitcher.ResultCallback() {
            @Override
            public void onComplete(final boolean success) {
                if (!success) {
                    abortMagicDeskExit("EXIT-001", "Could not restore the phone screen");
                    return;
                }
                ConsoleModeSwitcher.returnConsoleTasksToPhone(
                        new ConsoleModeSwitcher.ResultCallback() {
                    @Override
                    public void onComplete(final boolean tasksReturned) {
                        if (!tasksReturned) {
                            abortMagicDeskExit(
                                    "EXIT-002",
                                    "Could not return Console tasks to the phone");
                            return;
                        }
                        ConsoleModeSwitcher.switchToMirror(
                                new ConsoleModeSwitcher.ResultCallback() {
                            @Override
                            public void onComplete(final boolean mirrorActive) {
                                if (!mirrorActive) {
                                    abortMagicDeskExit(
                                            "EXIT-003",
                                            "Could not restore mirror mode");
                                    return;
                                }
                                finishMagicDeskExit();
                            }
                        });
                    }
                });
            }
        });
    }

    private void finishUnprivilegedExit() {
        RootKeyboardShortcutWatcher.stop();
        KeyboardWatcherService.stop(this);
        ConsoleModeSwitcher.closeRootShell();
        DeviceSetupManager.revokeRuntimeAuthorization();
        releaseDesktopOverlays();
        mExitInProgress = false;
        finishAndRemoveTask();
    }

    private void finishMagicDeskExit() {
        RootKeyboardShortcutWatcher.stop();
        KeyboardWatcherService.stop(this);
        runRootCommandBestEffort(AM + " stop-service -n "
                + TOOLS_KEYBOARD_WATCHER_SERVICE);
        try {
            runRootCommand(AM + " start --display 0"
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.HOME");
            runRootCommand(AM + " force-stop --user 0 " + getPackageName());
        } catch (IOException e) {
            Log.w(TAG, "full MagicDesk exit failed", e);
            abortMagicDeskExit(
                    "EXIT-004",
                    getString(R.string.status_root_failed, e.getMessage()),
                    e);
        }
    }

    private void abortMagicDeskExit(final String code, final String message) {
        abortMagicDeskExit(code, message, null);
    }

    private void abortMagicDeskExit(final String code, final String message,
            final Throwable error) {
        Log.w(TAG, "MagicDesk exit aborted: " + message, error);
        runOnUiThread(() -> {
            mExitInProgress = false;
            setErrorStatus(code, message, "", error);
        });
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

    private String getDensityStatus() {
        return mDisplayDensityController.getStatus();
    }

    private static String runRootCommand(final String command)
            throws IOException {
        return PrivilegedCommandRunner.run(command);
    }

    private static void runRootCommandBestEffort(final String command) {
        try {
            runRootCommand(command);
        } catch (IOException e) {
            Log.w(TAG,
                    "best-effort root command failed: " + command,
                    e);
        }
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

    private TextView sectionTitle(final int titleResId) {
        return mUi.sectionTitle(titleResId);
    }

    private Button createActionButton(final int textResId, final int accentColor) {
        return mUi.actionButton(textResId, accentColor);
    }

    private Button createActionButton(final String text, final int accentColor) {
        return mUi.actionButton(text, accentColor);
    }

    private Button createSmallButton(final int textResId, final int accentColor) {
        return mUi.smallButton(textResId, accentColor);
    }

    private Button createSmallButton(final String text, final int accentColor) {
        return mUi.smallButton(text, accentColor);
    }

    private ImageButton createTaskbarIconButton(final int drawableResId,
            final int descriptionResId) {
        return mUi.taskbarIconButton(
                drawableResId, descriptionResId, isCompactDesktopPreview());
    }

    private GradientDrawable rounded(final int color, final int radius, final int strokeColor) {
        return mUi.rounded(color, radius, strokeColor);
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
        if (mStatus != null) {
            mStatus.setText(text);
        }
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
        return mDesktopMode && getResources().getConfiguration().screenWidthDp < 700;
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

}
