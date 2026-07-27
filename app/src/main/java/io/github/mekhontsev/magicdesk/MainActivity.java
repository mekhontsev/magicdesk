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
    private static final String WM = "/system/bin/wm";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String AM = "/system/bin/am";
    private static final String TOOLS_KEYBOARD_WATCHER_SERVICE =
            "io.github.mekhontsev.magicdesk/.KeyboardWatcherService";
    private static final String HARDWARE_LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    private static final String HARDWARE_LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    private static final String HARDWARE_LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    private static final String PHONE_SCREEN_OFF_STATE = "nubia_screen_off_tp";
    static final String EXTRA_ACTION = "magicdesk_action";
    private static final String ACTION_SHOW_START = "show_start";
    static final String ACTION_RESTORE_WINDOWS = "restore_windows";
    static final String BROADCAST_SHOW_START =
            "io.github.mekhontsev.magicdesk.action.SHOW_START";
    private static final long SHORTCUT_RESTART_DELAY_MILLIS = 800;
    private static final int REQUEST_DESKTOP_FOLDER = 1001;
    private static final int MAX_DESKTOP_FILES = 30;
    static final int TASKBAR_HEIGHT_DP = 64;
    private static final int COMPACT_TASKBAR_HEIGHT_DP = 52;
    private static WeakReference<MainActivity> sDesktopInstance =
            new WeakReference<>(null);
    private static final Set<String> DENSITY_APPLY_KEYS =
            Collections.synchronizedSet(new HashSet<String>());

    private LinearLayout mContent;
    private FrameLayout mDesktopRoot;
    private GridLayout mDesktopIcons;
    private LinearLayout mTaskbar;
    private LinearLayout mTaskbarPins;
    private TextView mKeyboardLayoutIndicator;
    private TextView mBatteryStatus;
    private ImageButton mConsoleButton;
    private ImageButton mPhoneScreenButton;
    private Button mPhoneScreenAction;
    private TextView mToolsStatus;
    private TextView mToolsActivityStatus;
    private ContentObserver mConsoleSettingsObserver;
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
    private LauncherAppRepository mLauncherApps;
    private DesktopFileRepository mDesktopFileRepository;
    private TaskRepository.Snapshot mTaskSnapshot = new TaskRepository.Snapshot(
            Collections.<TaskRepository.TaskEntry>emptyList(), false, "not loaded");
    private List<TaskRepository.TaskEntry> mInteractionVisibleTasks =
            Collections.emptyList();
    private BroadcastReceiver mBatteryReceiver;
    private final Set<Button> mConsoleModeActions = Collections.newSetFromMap(
            new WeakHashMap<Button, Boolean>());
    private boolean mDesktopMode;
    private boolean mConsoleDensityApplyStarted;
    private boolean mPanelBackDown;
    private boolean mContextButtonDown;
    private boolean mContextButtonTouchSequence;
    private boolean mDesktopWindowFocusable = true;
    private boolean mAltTabActive;
    private boolean mAltTabLoadInProgress;
    private boolean mAltTabCommitPending;
    private boolean mExitInProgress;
    private int mAltTabPendingOffset;
    private int mAltTabSelectedIndex = -1;
    private int mTaskRefreshGeneration;
    private int mFolderLoadGeneration;
    private float mLastPointerX;
    private float mLastPointerY;
    private String mLastStatusText;
    private String mLoadedFolderUri;
    private List<AppItem> mLastApps = Collections.emptyList();
    private List<DesktopFile> mDesktopFiles = Collections.emptyList();
    private List<TaskRepository.TaskEntry> mAltTabTasks = Collections.emptyList();
    private boolean mWorkspaceRestoreAttempted;
    private boolean mWorkspaceBoundsRestorePending;
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
        mLauncherApps = new LauncherAppRepository(this);
        mDesktopFileRepository =
                new DesktopFileRepository(getContentResolver(), MAX_DESKTOP_FILES);
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
        registerBatteryReceiver();
        registerConsoleSettingsObserver();
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
        mTaskbar = null;
    }

    @Override
    protected void onDestroy() {
        if (mNotifications != null) {
            mNotifications.stop();
        }
        mTaskRefreshGeneration++;
        mFolderLoadGeneration++;
        if (mDisplayProfiles != null) {
            mDisplayProfiles.stop();
        }
        releaseDesktopOverlays();
        if (sDesktopInstance.get() == this) {
            sDesktopInstance.clear();
        }
        if (mConsoleSettingsObserver != null) {
            getContentResolver().unregisterContentObserver(mConsoleSettingsObserver);
            mConsoleSettingsObserver = null;
        }
        if (mBatteryReceiver != null) {
            try {
                unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already be detached during process teardown.
            }
            mBatteryReceiver = null;
        }
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

    private boolean handleDesktopMouseTouchEvent(final MotionEvent event,
            final boolean useRawCoordinates) {
        if (!mDesktopMode || event == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !hasVisiblePanel()) {
            mInteractionVisibleTasks = captureVisibleFreeformTasks();
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

    private boolean handleDesktopMouseGenericEvent(final MotionEvent event,
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

    private boolean hasVisiblePanel() {
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

    void setTaskSnapshot(final TaskRepository.Snapshot snapshot) {
        mTaskSnapshot = snapshot;
    }

    boolean isAltTabTaskSelected(final TaskRepository.TaskEntry task) {
        return mAltTabActive
                && mAltTabSelectedIndex >= 0
                && mAltTabSelectedIndex < mAltTabTasks.size()
                && mAltTabTasks.get(mAltTabSelectedIndex).taskId == task.taskId;
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
        if (requestCode != REQUEST_DESKTOP_FOLDER || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri treeUri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot persist desktop folder permission for " + treeUri, e);
            }
        }
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.folderUri = treeUri.toString();
        saveWorkspaceProfile();
        refreshDesktopFolder(true);
        setStatus(R.string.status_desktop_folder_selected);
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
            final boolean firstWorkspaceRestore = !activity.mWorkspaceRestoreAttempted;
            activity.restoreWorkspaceAppOnce(snapshot);
            if (!firstWorkspaceRestore) {
                activity.updateWorkspaceBounds(snapshot);
            }
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
                        mInteractionVisibleTasks = Collections.emptyList();
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
                mInteractionVisibleTasks = Collections.emptyList();
            }
            return handled;
        });
        desktop.setOnClickListener(view -> { });

        mDesktopIcons = new GridLayout(this);
        mDesktopIcons.setColumnCount(getDesktopColumnCount());
        mDesktopIcons.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        mDesktopIcons.setOnDragListener((view, event) ->
                handleDesktopGridDrop(event));
        final LinearLayout.LayoutParams iconsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        desktop.addView(mDesktopIcons, iconsParams);

        root.addView(desktop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mStartMenuController.create();
        mTaskbar = createTaskbar();
        final int taskbarHeight = getTaskbarHeight();
        final int taskbarWidth = Math.max(1, getDesktopAreaWidth());
        final int taskbarTop = getTaskbarTop(getDesktopAreaHeight());
        if (!mOverlayPanelController.attachPersistent(mTaskbar,
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

    private LinearLayout createTaskbar() {
        final LinearLayout taskbar = new LinearLayout(this) {
            private final int mTouchSlop = ViewConfiguration.get(
                    MainActivity.this).getScaledTouchSlop();
            private float mBlankDownX;
            private float mBlankDownY;
            private boolean mBlankLongPressPending;
            private final Runnable mBlankLongPress = () -> {
                if (!mBlankLongPressPending) {
                    return;
                }
                mBlankLongPressPending = false;
                captureInteractionStackForPanel();
                showDesktopContextMenu(mBlankDownX, mBlankDownY);
            };

            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (handleDesktopMouseTouchEvent(event, true)) {
                    cancelBlankLongPress();
                    return true;
                }
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelBlankLongPress();
                    if (!isTaskbarActionAt(event.getX(), event.getY())) {
                        hideAllPanels();
                        mInteractionVisibleTasks = Collections.emptyList();
                        mBlankDownX = event.getRawX();
                        mBlankDownY = event.getRawY();
                        mBlankLongPressPending = true;
                        postDelayed(mBlankLongPress,
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

            private void cancelBlankLongPress() {
                mBlankLongPressPending = false;
                removeCallbacks(mBlankLongPress);
            }

            @Override
            public boolean dispatchGenericMotionEvent(final MotionEvent event) {
                if (handleDesktopMouseGenericEvent(event, true)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }
        };
        taskbar.setOrientation(LinearLayout.HORIZONTAL);
        taskbar.setGravity(Gravity.CENTER_VERTICAL);
        taskbar.setPadding(desktopDp(10, 4), desktopDp(8, 4),
                desktopDp(10, 4), desktopDp(8, 4));
        taskbar.setBackground(rounded(COLOR_PANEL, 0, COLOR_PANEL_ALT));

        final Button start = createActionButton(R.string.action_start, COLOR_CYAN);
        start.setTextSize(14);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setOnClickListener(view -> toggleStartMenu());
        taskbar.addView(start, new LinearLayout.LayoutParams(desktopDp(108, 72),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final HorizontalScrollView taskScroll = new HorizontalScrollView(this);
        taskScroll.setHorizontalScrollBarEnabled(false);
        taskScroll.setFillViewport(true);

        mTaskbarPins = new LinearLayout(this);
        mTaskbarPins.setOrientation(LinearLayout.HORIZONTAL);
        mTaskbarPins.setGravity(Gravity.CENTER_VERTICAL);
        taskScroll.addView(mTaskbarPins, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        final LinearLayout.LayoutParams pinsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        pinsParams.setMargins(desktopDp(10, 4), 0, desktopDp(10, 4), 0);
        taskbar.addView(taskScroll, pinsParams);

        final ImageButton showDesktop = createTaskbarIconButton(
                R.drawable.ic_show_desktop,
                R.string.action_show_desktop);
        showDesktop.setOnClickListener(view -> toggleDesktopWorkspace());
        taskbar.addView(showDesktop, new LinearLayout.LayoutParams(
                desktopDp(46, 38), LinearLayout.LayoutParams.MATCH_PARENT));

        final ImageButton taskOverview = createTaskbarIconButton(
                android.R.drawable.ic_menu_recent_history,
                R.string.action_open_tasks);
        taskOverview.setOnClickListener(view -> toggleTaskOverview());
        taskbar.addView(taskOverview, new LinearLayout.LayoutParams(
                desktopDp(46, 38), LinearLayout.LayoutParams.MATCH_PARENT));

        taskbar.addView(
                mNotifications.createTaskbarButton(
                        isCompactDesktopPreview()),
                new LinearLayout.LayoutParams(
                        desktopDp(46, 38),
                        LinearLayout.LayoutParams.MATCH_PARENT));

        mKeyboardLayoutIndicator = new TextView(this);
        mKeyboardLayoutIndicator.setTextColor(COLOR_TEXT);
        mKeyboardLayoutIndicator.setTextSize(isCompactDesktopPreview() ? 11 : 13);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mKeyboardLayoutIndicator.setAutoSizeTextTypeUniformWithConfiguration(
                    8, isCompactDesktopPreview() ? 11 : 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        mKeyboardLayoutIndicator.setTypeface(Typeface.DEFAULT_BOLD);
        mKeyboardLayoutIndicator.setGravity(Gravity.CENTER);
        mKeyboardLayoutIndicator.setClickable(true);
        mKeyboardLayoutIndicator.setFocusable(true);
        mKeyboardLayoutIndicator.setBackground(rounded(
                COLOR_PANEL_ALT, desktopDp(8, 6), COLOR_PANEL_ALT));
        mKeyboardLayoutIndicator.setOnClickListener(
                view -> ConsoleModeSwitcher.toggleHardwareKeyboardLayout());
        mKeyboardLayoutIndicator.setEnabled(
                RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT));
        taskbar.addView(mKeyboardLayoutIndicator, new LinearLayout.LayoutParams(
                desktopDp(48, 38), LinearLayout.LayoutParams.MATCH_PARENT));

        mPhoneScreenButton = createTaskbarIconButton(
                R.drawable.ic_phone_screen_off,
                R.string.tooltip_phone_screen);
        mPhoneScreenButton.setOnClickListener(view -> togglePhoneScreen());
        mPhoneScreenButton.setEnabled(
                RuntimeAccess.has(RuntimeAccess.Capability.PHONE_SCREEN_CONTROL));
        taskbar.addView(mPhoneScreenButton, new LinearLayout.LayoutParams(
                desktopDp(46, 38), LinearLayout.LayoutParams.MATCH_PARENT));

        mConsoleButton = createTaskbarIconButton(
                android.R.drawable.ic_menu_manage,
                R.string.tooltip_tools);
        mConsoleButton.setOnClickListener(view -> toggleToolsMenu());
        taskbar.addView(mConsoleButton, new LinearLayout.LayoutParams(
                desktopDp(46, 38), LinearLayout.LayoutParams.MATCH_PARENT));

        mBatteryStatus = new TextView(this);
        mBatteryStatus.setTextColor(COLOR_MUTED);
        mBatteryStatus.setTextSize(isCompactDesktopPreview() ? 10 : 12);
        mBatteryStatus.setGravity(Gravity.CENTER);
        mBatteryStatus.setSingleLine(true);
        taskbar.addView(mBatteryStatus, new LinearLayout.LayoutParams(
                desktopDp(58, 44), LinearLayout.LayoutParams.MATCH_PARENT));

        final TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(COLOR_TEXT);
        clock.setTextSize(isCompactDesktopPreview() ? 12 : 16);
        clock.setGravity(Gravity.CENTER);
        clock.setClickable(true);
        clock.setFocusable(true);
        clock.setBackground(rounded(
                COLOR_PANEL_ALT, desktopDp(8, 6), COLOR_PANEL_ALT));
        clock.setContentDescription(getString(R.string.action_calendar));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            clock.setTooltipText(getString(R.string.action_calendar));
        }
        clock.setOnClickListener(view -> toggleCalendarPanel());
        taskbar.addView(clock, new LinearLayout.LayoutParams(desktopDp(72, 50),
                LinearLayout.LayoutParams.MATCH_PARENT));
        return taskbar;
    }

    private boolean isTaskbarActionAt(final float localX, final float localY) {
        if (mTaskbar == null) {
            return false;
        }
        for (int index = 0; index < mTaskbar.getChildCount(); index++) {
            if (isActionViewAt(mTaskbar, mTaskbar.getChildAt(index),
                    localX, localY)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActionViewAt(final ViewGroup parent, final View view,
            final float parentX, final float parentY) {
        if (view == null || !view.isShown() || !view.isEnabled()) {
            return false;
        }
        final float localX = parentX + parent.getScrollX() - view.getLeft();
        final float localY = parentY + parent.getScrollY() - view.getTop();
        if (localX < 0 || localY < 0
                || localX >= view.getWidth() || localY >= view.getHeight()) {
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
            if (isActionViewAt(group, group.getChildAt(index), localX, localY)) {
                return true;
            }
        }
        return false;
    }

    private void toggleCalendarPanel() {
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


    private void toggleTaskOverview() {
        mTaskOverviewController.toggle();
    }

    void showTaskOverview() {
        mTaskOverviewController.show();
    }

    private void populateTaskOverview(final TaskRepository.Snapshot snapshot) {
        mTaskOverviewController.populate(snapshot);
    }

    private boolean showTaskOverviewPanel() {
        return mTaskOverviewController.showPanel();
    }

    void registerContextTarget(final View view, final AppItem app,
            final TaskRepository.TaskEntry task) {
        mContextMenuController.registerTarget(view, app, task);
    }

    private void showDesktopContextMenu(final float x, final float y) {
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

    private static TaskRepository.TaskEntry findTask(
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

    private void renderDesktopIcons(final List<AppItem> apps) {
        if (mDesktopIcons == null) {
            return;
        }
        mDesktopIcons.removeAllViews();
        mDesktopIcons.setColumnCount(getDesktopColumnCount());
        final int capacity = getDesktopItemCapacity();
        int rendered = 0;
        for (final String packageName : getWorkspaceProfile().desktopPackages) {
            final AppItem app = LauncherAppRepository.find(apps, packageName);
            if (app == null) {
                continue;
            }
            mDesktopIcons.addView(createDesktopIcon(app), createDesktopItemParams());
            rendered++;
            if (rendered >= capacity) {
                return;
            }
        }
        for (final DesktopFile file : mDesktopFiles) {
            mDesktopIcons.addView(createDesktopFileIcon(file), createDesktopItemParams());
            rendered++;
            if (rendered >= capacity) {
                return;
            }
        }
    }

    private View createDesktopIcon(final AppItem app) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (app.packageName.equals(getWorkspaceProfile().workspacePackage)) {
            item.setBackground(rounded(0x55172033, dp(8), COLOR_AMBER));
        }
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            hideAllPanels();
            launchDefault(app);
        });
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] dragDown = new float[2];
        final boolean[] dragging = new boolean[1];
        item.setOnTouchListener((view, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragDown[0] = event.getX();
                dragDown[1] = event.getY();
                dragging[0] = false;
            } else if (action == MotionEvent.ACTION_MOVE && !dragging[0]
                    && (Math.abs(event.getX() - dragDown[0]) > touchSlop
                            || Math.abs(event.getY() - dragDown[1]) > touchSlop)) {
                dragging[0] = startDesktopShortcutDrag(view, app);
                return dragging[0];
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                dragging[0] = false;
            }
            return false;
        });
        item.setOnDragListener((view, event) -> handleDesktopShortcutDrop(event,
                app.packageName));
        registerContextTarget(item, app, null);

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        item.addView(icon, new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34)));

        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(isCompactDesktopPreview() ? 10 : 12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private boolean startDesktopShortcutDrag(final View view, final AppItem app) {
        final ClipData data = ClipData.newPlainText(
                getString(R.string.desktop_drag_label), app.packageName);
        final View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return view.startDragAndDrop(data, shadow, app.packageName, 0);
        }
        return view.startDrag(data, shadow, app.packageName, 0);
    }

    private View createDesktopFileIcon(final DesktopFile file) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> openDesktopFile(file));

        final ImageView icon = new ImageView(this);
        icon.setImageResource(file.directory
                ? android.R.drawable.ic_menu_agenda
                : desktopFileIcon(file.mimeType));
        icon.setColorFilter(file.directory ? COLOR_AMBER : COLOR_CYAN);
        item.addView(icon, new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34)));

        final TextView label = new TextView(this);
        label.setText(file.name);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(isCompactDesktopPreview() ? 10 : 12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private int desktopFileIcon(final String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return android.R.drawable.ic_menu_gallery;
        }
        if (mimeType != null && (mimeType.startsWith("audio/")
                || mimeType.startsWith("video/"))) {
            return android.R.drawable.ic_media_play;
        }
        return android.R.drawable.ic_menu_save;
    }

    private GridLayout.LayoutParams createDesktopItemParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = desktopDp(104, 78);
        params.height = desktopDp(94, 74);
        params.setMargins(desktopDp(4, 2), desktopDp(4, 2),
                desktopDp(4, 2), desktopDp(4, 2));
        return params;
    }

    private int getDesktopColumnCount() {
        final int availableDp = Math.max(1,
                getResources().getConfiguration().screenWidthDp
                        - (isCompactDesktopPreview() ? 20 : 48));
        final int cellDp = isCompactDesktopPreview() ? 82 : 112;
        return Math.max(1, Math.min(12, availableDp / cellDp));
    }

    private int getDesktopItemCapacity() {
        final int heightDp = getResources().getConfiguration().screenHeightDp;
        final int reservedDp = isCompactDesktopPreview() ? 116 : 158;
        final int cellDp = isCompactDesktopPreview() ? 78 : 102;
        final int rows = Math.max(1, (heightDp - reservedDp) / cellDp);
        return getDesktopColumnCount() * rows;
    }

    private boolean handleDesktopShortcutDrop(final DragEvent event,
            final String targetPackage) {
        if (event.getAction() != DragEvent.ACTION_DROP) {
            return event.getAction() == DragEvent.ACTION_DRAG_STARTED
                    && event.getLocalState() instanceof String;
        }
        final Object state = event.getLocalState();
        if (!(state instanceof String)) {
            return false;
        }
        reorderDesktopShortcut((String) state, targetPackage);
        return true;
    }

    private boolean handleDesktopGridDrop(final DragEvent event) {
        final Object state = event.getLocalState();
        if (!(state instanceof String)) {
            return false;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            final int cellWidth = desktopDp(112, 82);
            final int cellHeight = desktopDp(102, 78);
            final int column = Math.max(0, Math.min(
                    getDesktopColumnCount() - 1,
                    Math.round(event.getX()) / Math.max(1, cellWidth)));
            final int row = Math.max(0,
                    Math.round(event.getY()) / Math.max(1, cellHeight));
            moveDesktopShortcut((String) state,
                    row * getDesktopColumnCount() + column);
        }
        return true;
    }

    private void reorderDesktopShortcut(final String sourcePackage,
            final String targetPackage) {
        final List<String> packages = getWorkspaceProfile().desktopPackages;
        final int sourceIndex = packages.indexOf(sourcePackage);
        final int targetIndex = packages.indexOf(targetPackage);
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) {
            return;
        }
        moveDesktopShortcut(sourcePackage, targetIndex);
    }

    private void moveDesktopShortcut(final String sourcePackage, final int requestedIndex) {
        final List<String> packages = getWorkspaceProfile().desktopPackages;
        final int sourceIndex = packages.indexOf(sourcePackage);
        if (sourceIndex < 0) {
            return;
        }
        packages.remove(sourceIndex);
        final int targetIndex = Math.max(0, Math.min(requestedIndex, packages.size()));
        packages.add(targetIndex, sourcePackage);
        saveWorkspaceProfile();
        renderDesktopIcons(mLastApps);
    }

    void chooseDesktopFolder() {
        hideAllPanels();
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        final String currentUri = getWorkspaceProfile().folderUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && currentUri != null && currentUri.length() > 0) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(currentUri));
        }
        try {
            startActivityForResult(intent, REQUEST_DESKTOP_FOLDER);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open desktop folder picker", e);
            setErrorStatus(
                    "FILES-001",
                    getString(R.string.status_desktop_folder_failed, e.getMessage()),
                    "",
                    e);
        }
    }

    void clearDesktopFolder() {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        final String previous = profile.folderUri;
        profile.folderUri = null;
        saveWorkspaceProfile();
        mLoadedFolderUri = null;
        mDesktopFiles = Collections.emptyList();
        if (previous != null) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The provider may already have revoked the grant.
            }
        }
        renderDesktopIcons(mLastApps);
        setStatus(R.string.status_desktop_folder_hidden);
    }

    void refreshDesktopFolder(final boolean force) {
        if (!mDesktopMode) {
            return;
        }
        final String folderUri = getWorkspaceProfile().folderUri;
        if (folderUri == null || folderUri.length() == 0) {
            if (!mDesktopFiles.isEmpty()) {
                mDesktopFiles = Collections.emptyList();
                renderDesktopIcons(mLastApps);
            }
            mLoadedFolderUri = null;
            return;
        }
        if (!force && folderUri.equals(mLoadedFolderUri)) {
            return;
        }
        mLoadedFolderUri = folderUri;
        final int generation = ++mFolderLoadGeneration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<DesktopFile> files;
                try {
                    files = mDesktopFileRepository.load(Uri.parse(folderUri));
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cannot load desktop folder " + folderUri, e);
                    runOnUiThread(() -> {
                        if (generation == mFolderLoadGeneration) {
                            mDesktopFiles = Collections.emptyList();
                            renderDesktopIcons(mLastApps);
                            setErrorStatus(
                                    "FILES-002",
                                    getString(R.string.status_desktop_folder_failed,
                                            e.getMessage()),
                                    "",
                                    e);
                        }
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (generation != mFolderLoadGeneration || isFinishing()
                            || isDestroyed()) {
                        return;
                    }
                    mDesktopFiles = files;
                    renderDesktopIcons(mLastApps);
                });
            }
        }, "MagicDeskDesktopFolder").start();
    }

    private void openDesktopFile(final DesktopFile file) {
        hideAllPanels();
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(file.uri, file.mimeType == null ? "*/*" : file.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        invokeIntOption(options, "setLaunchDisplayId", getCurrentDisplayId());
        try {
            startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open desktop file " + file.uri, e);
            setErrorStatus(
                    "FILES-003",
                    getString(R.string.status_desktop_file_failed, file.name),
                    "mime=" + file.mimeType,
                    e);
        }
    }

    private void refreshTaskSnapshot() {
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
                        final boolean firstWorkspaceRestore = !mWorkspaceRestoreAttempted;
                        restoreWorkspaceAppOnce(snapshot);
                        if (!firstWorkspaceRestore) {
                            updateWorkspaceBounds(snapshot);
                        }
                        renderTaskbarPins(mLastApps);
                        updateConsoleControls();
                    }
                });
            }
        });
    }

    private List<TaskRepository.TaskEntry> findTasks(final String packageName) {
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

    private List<String> getOrderedPinnedPackages(final List<AppItem> apps,
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
        return new LinkedHashSet<>(getWorkspaceProfile().taskbarPackages);
    }

    private Set<String> getLegacyPinnedPackages() {
        return DesktopPreferences.legacyPinnedPackages(this);
    }

    void togglePinned(final AppItem app) {
        final Set<String> pinned = getPinnedPackages();
        final boolean nowPinned;
        if (pinned.contains(app.packageName)) {
            pinned.remove(app.packageName);
            nowPinned = false;
        } else {
            pinned.add(app.packageName);
            nowPinned = true;
        }
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.taskbarPackages.clear();
        profile.taskbarPackages.addAll(pinned);
        saveWorkspaceProfile();
        DesktopPreferences.saveLegacyPinnedPackages(this, pinned);
        renderTaskbarPins(mLastApps);
        renderStartMenuContent();
        setStatus(getString(nowPinned
                ? R.string.status_app_pinned : R.string.status_app_unpinned,
                app.label));
    }

    boolean isDesktopShortcut(final String packageName) {
        return getWorkspaceProfile().desktopPackages.contains(packageName);
    }

    void toggleDesktopShortcut(final AppItem app) {
        final List<String> shortcuts = getWorkspaceProfile().desktopPackages;
        final boolean added;
        if (shortcuts.remove(app.packageName)) {
            added = false;
        } else {
            shortcuts.add(app.packageName);
            added = true;
        }
        saveWorkspaceProfile();
        renderDesktopIcons(mLastApps);
        setStatus(getString(added
                ? R.string.status_desktop_shortcut_added
                : R.string.status_desktop_shortcut_removed, app.label));
    }

    void setWorkspaceApp(final AppItem app,
            final TaskRepository.TaskEntry task, final boolean keep) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        if (!keep) {
            profile.workspacePackage = null;
            profile.workspaceBounds.setEmpty();
            mWorkspaceBoundsRestorePending = false;
            saveWorkspaceProfile();
            renderDesktopIcons(mLastApps);
            renderTaskbarPins(mLastApps);
            setStatus(getString(R.string.status_workspace_app_removed, app.label));
            return;
        }

        profile.workspacePackage = app.packageName;
        profile.workspaceBounds.setEmpty();
        if (task != null && task.isFreeform() && !task.bounds.isEmpty()) {
            profile.workspaceBounds.set(task.bounds);
        }
        saveWorkspaceProfile();
        renderDesktopIcons(mLastApps);
        renderTaskbarPins(mLastApps);
        setStatus(getString(R.string.status_workspace_app_kept, app.label));
        if (task == null || !task.isFreeform()) {
            mWorkspaceBoundsRestorePending = !profile.workspaceBounds.isEmpty();
            launchFloating(app);
        }
    }

    private TaskRepository.TaskEntry findWorkspaceTask(
            final TaskRepository.Snapshot snapshot) {
        final String workspacePackage = getWorkspaceProfile().workspacePackage;
        if (workspacePackage == null || snapshot == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (isTaskbarTask(task) && workspacePackage.equals(task.packageName)) {
                return task;
            }
        }
        return null;
    }

    private void updateWorkspaceBounds(final TaskRepository.Snapshot snapshot) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        final TaskRepository.TaskEntry task = findWorkspaceTask(snapshot);
        if (task == null || !task.isFreeform() || task.bounds.isEmpty()) {
            return;
        }
        if (mWorkspaceBoundsRestorePending && !profile.workspaceBounds.isEmpty()) {
            final Rect desiredBounds = new Rect(profile.workspaceBounds);
            mWorkspaceBoundsRestorePending = false;
            if (!desiredBounds.equals(task.bounds)) {
                TaskRepository.resizeTaskBounds(task, desiredBounds,
                        result -> runOnUiThread(this::refreshTaskSnapshot));
                return;
            }
        }
        if (!profile.workspaceBounds.equals(task.bounds)) {
            profile.workspaceBounds.set(task.bounds);
            saveWorkspaceProfile();
        }
    }

    private void restoreWorkspaceAppOnce(final TaskRepository.Snapshot snapshot) {
        if (mWorkspaceRestoreAttempted) {
            return;
        }
        mWorkspaceRestoreAttempted = true;
        restoreWorkspaceApp(snapshot, false);
    }

    private void restoreWorkspaceApp(final TaskRepository.Snapshot snapshot,
            final boolean bringToFront) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        if (profile.workspacePackage == null || profile.workspacePackage.length() == 0) {
            return;
        }
        final AppItem app = findOrLoadApp(mLastApps, profile.workspacePackage);
        if (app == null) {
            return;
        }
        final TaskRepository.TaskEntry task = findWorkspaceTask(snapshot);
        if (task == null || !task.isFreeform()) {
            mWorkspaceBoundsRestorePending = !profile.workspaceBounds.isEmpty();
            launchFloating(app);
            return;
        }
        if (!profile.workspaceBounds.isEmpty()
                && !profile.workspaceBounds.equals(task.bounds)) {
            final Rect bounds = new Rect(profile.workspaceBounds);
            TaskRepository.resizeTaskBounds(task, bounds, result -> runOnUiThread(() -> {
                if (bringToFront) {
                    focusTask(app, task);
                }
                refreshTaskSnapshot();
            }));
            return;
        }
        if (bringToFront && !task.visible) {
            focusTask(app, task);
        }
    }

    void focusTask(final AppItem app, final TaskRepository.TaskEntry task) {
        setStatus(getString(R.string.status_switching_to, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks = takeInteractionVisibleTasks();
        final int displayId = getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || displayId != getCurrentDisplayId()) {
                return;
            }
            if (!snapshot.rootAvailable) {
                setStatus(getString(R.string.status_switch_failed,
                        snapshot.error.length() == 0 ? app.label : snapshot.error));
                return;
            }
            mTaskSnapshot = snapshot;
            final TaskRepository.TaskEntry currentTask =
                    findTask(snapshot, task.taskId);
            if (currentTask == null) {
                setStatus(getString(R.string.status_switch_failed, app.label));
                refreshTaskSnapshot();
                return;
            }
            DesktopTaskController.focusStack(
                    visibleTasks, currentTask, result -> runOnUiThread(() -> {
                        if (!result.success) {
                            setStatus(getString(R.string.status_switch_failed,
                                    result.message.length() == 0
                                            ? app.label : result.message));
                            return;
                        }
                        setTaskbarVisible(currentTask.isFreeform());
                        refreshTaskSnapshot();
                    }));
        }));
    }

    private void advanceAltTab(final boolean reverse) {
        final int offset = reverse ? -1 : 1;
        if (mAltTabActive) {
            if (mAltTabLoadInProgress) {
                mAltTabPendingOffset += offset;
            } else {
                selectAltTabOffset(offset);
                populateTaskOverview(mTaskSnapshot);
            }
            return;
        }

        mAltTabActive = true;
        mAltTabLoadInProgress = true;
        mAltTabCommitPending = false;
        mAltTabPendingOffset = offset;
        mAltTabSelectedIndex = -1;
        mAltTabTasks = Collections.emptyList();
        captureInteractionStackForPanel();
        hideAllPanels();

        final int displayId = getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot -> runOnUiThread(() -> {
            if (!mAltTabActive || isFinishing() || isDestroyed()
                    || displayId != getCurrentDisplayId()) {
                return;
            }
            mAltTabLoadInProgress = false;
            if (!snapshot.rootAvailable) {
                resetAltTabState();
                setStatus(getString(R.string.status_switch_failed,
                        snapshot.error.length() == 0
                                ? "task snapshot" : snapshot.error));
                return;
            }

            mTaskSnapshot = snapshot;
            final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (isTaskbarTask(task)) {
                    tasks.add(task);
                }
            }
            if (tasks.isEmpty()) {
                resetAltTabState();
                return;
            }
            mAltTabTasks = tasks;
            int activeIndex = -1;
            for (int index = 0; index < tasks.size(); index++) {
                if (tasks.get(index).active) {
                    activeIndex = index;
                    break;
                }
            }
            if (activeIndex < 0) {
                activeIndex = mAltTabPendingOffset < 0 ? 0 : -1;
            }
            mAltTabSelectedIndex = Math.floorMod(
                    activeIndex + mAltTabPendingOffset, tasks.size());
            mAltTabPendingOffset = 0;
            populateTaskOverview(snapshot);
            if (mAltTabCommitPending) {
                finishAltTab();
            } else if (!showTaskOverviewPanel()) {
                resetAltTabState();
            }
        }));
    }

    private void selectAltTabOffset(final int offset) {
        if (mAltTabTasks.isEmpty()) {
            return;
        }
        final int current = mAltTabSelectedIndex < 0 ? 0 : mAltTabSelectedIndex;
        mAltTabSelectedIndex = Math.floorMod(
                current + offset, mAltTabTasks.size());
    }

    private void finishAltTab() {
        if (!mAltTabActive) {
            return;
        }
        if (mAltTabLoadInProgress) {
            mAltTabCommitPending = true;
            return;
        }
        if (mAltTabSelectedIndex < 0
                || mAltTabSelectedIndex >= mAltTabTasks.size()) {
            resetAltTabState();
            hideAllPanels();
            return;
        }

        final TaskRepository.TaskEntry target =
                mAltTabTasks.get(mAltTabSelectedIndex);
        final AppItem app = findOrLoadApp(mLastApps, target.packageName);
        resetAltTabState();
        hideAllPanels();
        if (app == null) {
            mInteractionVisibleTasks = Collections.emptyList();
            setStatus(getString(R.string.status_switch_failed, target.packageName));
            return;
        }
        focusTask(app, target);
    }

    void resetAltTabState() {
        mAltTabActive = false;
        mAltTabLoadInProgress = false;
        mAltTabCommitPending = false;
        mAltTabPendingOffset = 0;
        mAltTabSelectedIndex = -1;
        mAltTabTasks = Collections.emptyList();
    }

    void openTaskFullscreen(final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int displayId = beginFullscreenTransition(task.taskId);
        setStatus(getString(R.string.status_launching_fullscreen, app.label));
        TaskRepository.setFullscreen(task, result -> {
            DesktopTaskController.finishFullscreenTransition(
                    displayId, result.success);
            runOnUiThread(() -> {
                if (result.success) {
                    setTaskbarVisible(false);
                }
                setStatus(getString(result.success
                                ? R.string.status_switch_done : R.string.status_switch_failed,
                        result.success ? app.label
                                : (result.message.length() == 0 ? app.label : result.message)));
                refreshTaskSnapshot();
            });
        });
    }

    void closeTask(final AppItem app, final TaskRepository.TaskEntry task) {
        hideAllPanels();
        setStatus(getString(R.string.status_closing_window, app.label));
        TaskRepository.closeTask(task, result -> runOnUiThread(() -> {
            setStatus(getString(result.success
                    ? R.string.status_window_closed : R.string.status_close_window_failed,
                    result.success ? app.label : result.message));
            refreshTaskSnapshot();
        }));
    }

    private void forceStopApp(final AppItem app) {
        hideAllPanels();
        setStatus(getString(R.string.status_force_stopping, app.label));
        TaskRepository.forceStop(app.packageName, result -> runOnUiThread(() -> {
            setStatus(getString(result.success
                    ? R.string.status_app_force_stopped : R.string.status_force_stop_failed,
                    result.success ? app.label : result.message));
            refreshTaskSnapshot();
        }));
    }

    void toggleDesktopWorkspace() {
        hideAllPanels();
        ConsoleModeSwitcher.showMagicDesk();
    }

    private int beginFullscreenTransition(final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> visibleTasks = takeInteractionVisibleTasks();
        final int displayId = getCurrentDisplayId();
        DesktopTaskController.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private int beginFullscreenTransition(final String packageName) {
        final List<TaskRepository.TaskEntry> visibleTasks = takeInteractionVisibleTasks();
        int excludedTaskId = -1;
        for (final TaskRepository.TaskEntry task : visibleTasks) {
            if (packageName.equals(task.packageName)) {
                excludedTaskId = task.taskId;
                break;
            }
        }
        final int displayId = getCurrentDisplayId();
        DesktopTaskController.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private void restoreLastVisibleWindows() {
        hideAllPanels();
        setTaskbarVisible(true);
        mInteractionVisibleTasks = Collections.emptyList();
        final int displayId = getCurrentDisplayId();
        final List<TaskRepository.TaskEntry> savedTasks =
                DesktopTaskController.getLastVisibleFreeformTasks(displayId);
        if (savedTasks.isEmpty()) {
            setStatus(R.string.status_desktop_visible);
            TaskRepository.load(displayId, snapshot -> runOnUiThread(() ->
                    restoreWorkspaceApp(snapshot, true)));
            return;
        }
        setStatus(R.string.status_restoring_windows);
        TaskRepository.restoreFreeformStack(displayId, savedTasks,
                result -> runOnUiThread(() -> {
                    setStatus(result.success
                            ? getString(R.string.status_windows_restored)
                            : getString(R.string.status_switch_failed,
                                    result.message.length() == 0
                                            ? getString(R.string.status_restoring_windows)
                                            : result.message));
                    refreshTaskSnapshot();
                    TaskRepository.load(displayId, snapshot -> runOnUiThread(() ->
                            restoreWorkspaceApp(snapshot, false)));
                }));
    }

    private List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.rootAvailable) {
            return Collections.emptyList();
        }
        final List<TaskRepository.TaskEntry> visibleTasks = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.visible && task.isFreeform() && !task.home
                    && !getPackageName().equals(task.packageName)) {
                visibleTasks.add(task);
            }
        }
        return visibleTasks;
    }

    private List<TaskRepository.TaskEntry> captureVisibleFreeformTasks() {
        final List<TaskRepository.TaskEntry> watchedTasks =
                DesktopTaskController.getVisibleFreeformTasks(getCurrentDisplayId());
        return watchedTasks == null
                ? getVisibleFreeformTasks(mTaskSnapshot) : watchedTasks;
    }

    void captureInteractionStackForPanel() {
        if (!hasVisiblePanel()) {
            mInteractionVisibleTasks = captureVisibleFreeformTasks();
        }
    }

    private List<TaskRepository.TaskEntry> takeInteractionVisibleTasks() {
        final List<TaskRepository.TaskEntry> visibleTasks = mInteractionVisibleTasks.isEmpty()
                ? captureVisibleFreeformTasks()
                : new ArrayList<>(mInteractionVisibleTasks);
        mInteractionVisibleTasks = Collections.emptyList();
        return visibleTasks;
    }

    private static int[] getTaskIds(final List<TaskRepository.TaskEntry> tasks) {
        final int[] taskIds = new int[tasks == null ? 0 : tasks.size()];
        for (int index = 0; index < taskIds.length; index++) {
            taskIds[index] = tasks.get(index).taskId;
        }
        return taskIds;
    }

    private void renderTaskbarPins(final List<AppItem> apps) {
        if (mTaskbarPins == null) {
            return;
        }
        mTaskbarPins.removeAllViews();
        final Set<String> pinnedPackages = getPinnedPackages();
        final String workspacePackage = getWorkspaceProfile().workspacePackage;
        if (workspacePackage != null) {
            pinnedPackages.add(workspacePackage);
        }
        final Set<Integer> renderedTaskIds = new HashSet<>();

        for (final String packageName : getOrderedPinnedPackages(apps, pinnedPackages)) {
            final AppItem app = LauncherAppRepository.find(apps, packageName);
            if (app == null) {
                continue;
            }
            final List<TaskRepository.TaskEntry> packageTasks = findTasks(packageName);
            if (packageTasks.isEmpty()) {
                addTaskbarPin(app, null, true);
                continue;
            }
            for (final TaskRepository.TaskEntry task : packageTasks) {
                addTaskbarPin(app, task, true);
                renderedTaskIds.add(Integer.valueOf(task.taskId));
            }
        }

        for (final TaskRepository.TaskEntry task : mTaskSnapshot.tasks) {
            if (!isTaskbarTask(task)
                    || renderedTaskIds.contains(Integer.valueOf(task.taskId))) {
                continue;
            }
            final AppItem app = findOrLoadApp(apps, task.packageName);
            if (app != null) {
                addTaskbarPin(app, task, false);
            }
        }
    }

    private void addTaskbarPin(final AppItem app, final TaskRepository.TaskEntry task,
            final boolean pinned) {
        mTaskbarPins.addView(createTaskbarPin(app, task, pinned),
                new LinearLayout.LayoutParams(
                        desktopDp(48, 36), LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private View createTaskbarPin(final AppItem app,
            final TaskRepository.TaskEntry task, final boolean pinned) {
        final FrameLayout item = new FrameLayout(this);
        final boolean workspaceApp = app.packageName.equals(
                getWorkspaceProfile().workspacePackage);
        final int borderColor = workspaceApp ? COLOR_AMBER
                : (task == null ? COLOR_PANEL_ALT
                        : (task.active ? COLOR_AMBER : COLOR_CYAN));
        item.setBackground(rounded(COLOR_PANEL_ALT, desktopDp(10, 8), borderColor));
        item.setClickable(true);
        item.setFocusable(true);

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setPadding(desktopDp(7, 5), desktopDp(7, 5),
                desktopDp(7, 5), desktopDp(7, 5));
        item.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (task != null) {
            final View running = new View(this);
            running.setBackgroundColor(task.active ? COLOR_AMBER : COLOR_CYAN);
            final FrameLayout.LayoutParams runningParams = new FrameLayout.LayoutParams(
                    desktopDp(20, 14), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            runningParams.setMargins(0, 0, 0, dp(2));
            item.addView(running, runningParams);
        }

        final String description = task == null ? app.label
                : getString(R.string.taskbar_running_description,
                        app.label, Integer.valueOf(task.taskId));
        item.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            item.setTooltipText(description);
        }
        item.setOnClickListener(view -> {
            hideAllPanels();
            if (task == null) {
                launchDefault(app);
            } else {
                focusTask(app, task);
            }
        });
        registerContextTarget(item, app, task);
        return item;
    }

    private void renderStartMenuContent() {
        mStartMenuController.render();
    }

    private void toggleStartMenu() {
        mStartMenuController.toggle();
    }

    private void setStartMenuVisible(final boolean visible) {
        mStartMenuController.setVisible(visible);
    }

    private void toggleToolsMenu() {
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
        final String layout = Settings.Global.getString(
                getContentResolver(), HARDWARE_LAYOUT_STATE);
        if (mKeyboardLayoutIndicator != null) {
            String layoutLabel = Settings.Global.getString(
                    getContentResolver(), HARDWARE_LAYOUT_LABEL_STATE);
            if (layoutLabel == null || layoutLabel.isEmpty()) {
                layoutLabel = "russian".equals(layout) ? "RU"
                        : ("english".equals(layout) ? "EN" : "??");
            }
            final String layoutName = Settings.Global.getString(
                    getContentResolver(), HARDWARE_LAYOUT_NAME_STATE);
            mKeyboardLayoutIndicator.setText(layoutLabel);
            final String description = getString(
                    R.string.keyboard_layout_description,
                    layoutName == null || layoutName.isEmpty() ? layoutLabel : layoutName);
            mKeyboardLayoutIndicator.setContentDescription(description);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mKeyboardLayoutIndicator.setTooltipText(description);
            }
        }

        final boolean phoneScreenOff = isPhoneScreenOff();
        final boolean phoneScreenControl =
                RuntimeAccess.has(RuntimeAccess.Capability.PHONE_SCREEN_CONTROL);
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        if (mPhoneScreenButton != null) {
            mPhoneScreenButton.setImageResource(phoneScreenOff
                    ? R.drawable.ic_phone_screen_on
                    : R.drawable.ic_phone_screen_off);
            mPhoneScreenButton.setColorFilter(phoneScreenOff ? COLOR_CYAN : COLOR_TEXT);
            mPhoneScreenButton.setContentDescription(getString(actionResId));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mPhoneScreenButton.setTooltipText(getString(actionResId));
            }
            mPhoneScreenButton.setEnabled(phoneScreenControl);
            mPhoneScreenButton.setAlpha(phoneScreenControl ? 1f : 0.45f);
        }
        if (mPhoneScreenAction != null) {
            mPhoneScreenAction.setText(actionResId);
            mPhoneScreenAction.setEnabled(phoneScreenControl);
        }
        if (mToolsStatus != null) {
            mToolsStatus.setText(getString(R.string.tools_status_full,
                    Integer.valueOf(getCurrentDisplayId()),
                    Integer.valueOf(getResources().getDisplayMetrics().densityDpi),
                    getString(phoneScreenOff ? R.string.state_off : R.string.state_on),
                    RuntimeAccess.backendName(),
                    getString(RootKeyboardShortcutWatcher.isRunning()
                            ? R.string.state_ready : R.string.state_unavailable),
                    getMonitorProfileLabel()));
        }
        final boolean consoleModeActive = isConsoleModeActive();
        final boolean consoleControl =
                RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL);
        for (final Button action : mConsoleModeActions) {
            action.setText(consoleModeActive
                    ? R.string.action_switch_to_mirror
                    : R.string.action_start_console_mode);
            action.setEnabled(consoleControl);
        }
        updateSystemStatusIndicator();
    }

    private void updateSystemStatusIndicator() {
        if (mConsoleButton == null) {
            return;
        }
        final boolean runtimeTaskControl =
                RuntimeAccess.has(RuntimeAccess.Capability.TASK_CONTROL);
        final boolean console = isConsoleModeActive();
        final boolean bridge = RootKeyboardShortcutWatcher.isRunning();
        final int color = runtimeTaskControl && console && bridge ? COLOR_CYAN
                : (runtimeTaskControl ? COLOR_AMBER : COLOR_MUTED);
        final String description = getString(R.string.system_status_description,
                RuntimeAccess.backendName(),
                getString(console ? R.string.state_ready : R.string.state_unavailable),
                getString(bridge ? R.string.state_ready : R.string.state_unavailable));
        mConsoleButton.setColorFilter(color);
        mConsoleButton.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mConsoleButton.setTooltipText(description);
        }
    }

    private boolean isConsoleModeActive() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(), "app_mirror_displayid", -1) > 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read Console Mode state", e);
            return false;
        }
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                updateBatteryStatus(intent);
            }
        };
        final Intent battery = registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            updateBatteryStatus(battery);
        }
    }

    private void updateBatteryStatus(final Intent battery) {
        if (mBatteryStatus == null || battery == null) {
            return;
        }
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1 : Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        final int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        final boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        mBatteryStatus.setText(percent < 0
                ? getString(R.string.battery_compact_unknown)
                : getString(charging
                        ? R.string.battery_compact_charging : R.string.battery_compact,
                        Integer.valueOf(percent)));
        mBatteryStatus.setTextColor(charging ? COLOR_CYAN : COLOR_TEXT);
        final String state = getString(charging
                ? R.string.battery_state_charging
                : (full ? R.string.battery_state_full : R.string.battery_state_discharging));
        final String description = percent < 0
                ? getString(R.string.battery_status_unknown)
                : getString(R.string.battery_status_description,
                        Integer.valueOf(percent), state);
        mBatteryStatus.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBatteryStatus.setTooltipText(description);
        }
    }

    private void registerConsoleSettingsObserver() {
        mConsoleSettingsObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(final boolean selfChange) {
                updateConsoleControls();
                mDisplayProfiles.scheduleRefresh();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HARDWARE_LAYOUT_STATE),
                false, mConsoleSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HARDWARE_LAYOUT_LABEL_STATE),
                false, mConsoleSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HARDWARE_LAYOUT_NAME_STATE),
                false, mConsoleSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(PHONE_SCREEN_OFF_STATE),
                false, mConsoleSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("app_mirror_displayid"),
                false, mConsoleSettingsObserver);
    }

    private boolean isPhoneScreenOff() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(), PHONE_SCREEN_OFF_STATE, 0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read phone screen state", e);
            return false;
        }
    }

    private void togglePhoneScreen() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
            return;
        }
        final boolean screenOff = !isPhoneScreenOff();
        if (mPhoneScreenButton != null) {
            mPhoneScreenButton.setEnabled(false);
        }
        if (mPhoneScreenAction != null) {
            mPhoneScreenAction.setEnabled(false);
        }
        setStatus(R.string.status_phone_screen_applying);
        ConsoleModeSwitcher.setPhoneScreenOff(screenOff,
                new ConsoleModeSwitcher.ResultCallback() {
                    @Override
                    public void onComplete(final boolean success) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateConsoleControls();
                                final int resultResId;
                                if (!success) {
                                    resultResId = R.string.status_phone_screen_failed;
                                } else if (screenOff) {
                                    resultResId = R.string.status_phone_screen_off;
                                } else {
                                    resultResId = R.string.status_phone_screen_on;
                                }
                                if (success) {
                                    setStatus(resultResId);
                                } else {
                                    setErrorStatus(
                                            "NUBIA-SCREEN-001",
                                            getString(resultResId));
                                }
                            }
                        });
                    }
                });
    }

    private void toggleConsoleMode() {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            return;
        }
        if (!isConsoleModeActive()) {
            setStatus(R.string.status_console_starting);
            ConsoleModeSwitcher.showMagicDesk();
            return;
        }

        for (final Button action : mConsoleModeActions) {
            action.setEnabled(false);
        }
        setStatus(R.string.status_mirror_switching);
        ConsoleModeSwitcher.switchToMirror(new ConsoleModeSwitcher.ResultCallback() {
            @Override
            public void onComplete(final boolean success) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateConsoleControls();
                        final int result = success
                                ? R.string.status_mirror_active
                                : R.string.status_mirror_failed;
                        if (success) {
                            setStatus(result);
                        } else {
                            setErrorStatus(
                                    "NUBIA-CONSOLE-001",
                                    getString(result));
                        }
                    }
                });
            }
        });
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

    void populateToolsControls(final LinearLayout parent, final int spacing) {
        final TextView dpiLabel = new TextView(this);
        dpiLabel.setText(R.string.dpi_label);
        dpiLabel.setTextColor(COLOR_TEXT);
        dpiLabel.setTextSize(14);
        final LinearLayout.LayoutParams dpiLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dpiLabelParams.setMargins(0, 0, 0, dp(6));
        parent.addView(dpiLabel, dpiLabelParams);

        final GridLayout dpiGrid = new GridLayout(this);
        dpiGrid.setColumnCount(5);
        addDpiButton(dpiGrid, 160);
        addDpiButton(dpiGrid, 192);
        addDpiButton(dpiGrid, 240);
        addDpiButton(dpiGrid, 320);
        addDpiResetButton(dpiGrid);
        parent.addView(dpiGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mToolsStatus = new TextView(this);
        mToolsStatus.setTextColor(COLOR_MUTED);
        mToolsStatus.setTextSize(13);
        final LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, spacing, 0, 0);
        parent.addView(mToolsStatus, statusParams);

        mToolsActivityStatus = new TextView(this);
        mToolsActivityStatus.setTextColor(COLOR_TEXT);
        mToolsActivityStatus.setTextSize(13);
        if (!TextUtils.isEmpty(mLastStatusText)) {
            mToolsActivityStatus.setText(mLastStatusText);
        }
        final LinearLayout.LayoutParams activityStatusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        activityStatusParams.setMargins(0, spacing, 0, 0);
        parent.addView(mToolsActivityStatus, activityStatusParams);

        final GridLayout actionGrid = new GridLayout(this);
        actionGrid.setColumnCount(2);

        mPhoneScreenAction = createActionButton(
                R.string.action_phone_screen_off, COLOR_CYAN);
        mPhoneScreenAction.setOnClickListener(view -> togglePhoneScreen());
        addToolsActionButton(actionGrid, mPhoneScreenAction, false);

        final Button consoleMode = createActionButton(
                R.string.action_switch_to_mirror, COLOR_CYAN);
        mConsoleModeActions.add(consoleMode);
        consoleMode.setOnClickListener(view -> toggleConsoleMode());
        addToolsActionButton(actionGrid, consoleMode, false);

        final Button restartShortcuts = createActionButton(
                R.string.action_restart_shortcuts, COLOR_AMBER);
        restartShortcuts.setOnClickListener(view -> restartConsoleShortcuts());
        restartShortcuts.setEnabled(
                RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT));
        addToolsActionButton(actionGrid, restartShortcuts, false);

        final Button deviceSetup = createActionButton(
                R.string.action_device_setup, COLOR_CYAN);
        deviceSetup.setOnClickListener(view -> {
            hideAllPanels();
            startActivity(DeviceSetupActivity.createManualIntent(this));
        });
        addToolsActionButton(actionGrid, deviceSetup, false);

        final Button diagnostics = createActionButton(
                R.string.action_diagnostics, COLOR_CYAN);
        diagnostics.setOnClickListener(view -> openDiagnostics());
        addToolsActionButton(actionGrid, diagnostics, false);

        if (RuntimeAccess.has(RuntimeAccess.Capability.KERNEL_FIXES)
                && KernelFixesIntegration.isAvailable(this)) {
            addToolsActionButton(actionGrid, createKernelFixesAction(), false);
        }

        final Button exit = createActionButton(R.string.action_exit, COLOR_RED);
        exit.setOnClickListener(view -> exitMagicDesk());
        addToolsActionButton(actionGrid, exit, false);

        final LinearLayout.LayoutParams actionGridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionGridParams.setMargins(0, spacing, 0, 0);
        parent.addView(actionGrid, actionGridParams);
        updateConsoleControls();
    }

    private void addToolsActionButton(final GridLayout grid, final Button button,
            final boolean fullRow) {
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setGravity(Gravity.CENTER);
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = fullRow
                ? GridLayout.spec(0, 2, 1f)
                : GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private void addDpiButton(final GridLayout grid, final int dpi) {
        final Button button = createActionButton(getString(R.string.dpi_value, Integer.valueOf(dpi)),
                COLOR_CYAN);
        button.setOnClickListener(view -> applyDensity(dpi));
        button.setEnabled(
                RuntimeAccess.has(RuntimeAccess.Capability.DISPLAY_OVERRIDES));
        grid.addView(button, createDpiButtonParams());
    }

    private void addDpiResetButton(final GridLayout grid) {
        final Button button = createActionButton(R.string.action_dpi_reset, COLOR_RED);
        button.setOnClickListener(view -> resetDensity());
        button.setEnabled(
                RuntimeAccess.has(RuntimeAccess.Capability.DISPLAY_OVERRIDES));
        grid.addView(button, createDpiButtonParams());
    }

    private GridLayout.LayoutParams createDpiButtonParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
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

    private int getPreferredDesktopDpi() {
        return getWorkspaceProfile().dpi;
    }

    private void setPreferredDesktopDpi(final int dpi) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.dpi = dpi;
        saveWorkspaceProfile();
        DesktopPreferences.saveLegacyDesktopDpi(this, dpi);
    }

    private WorkspaceProfileStore.Profile getWorkspaceProfile() {
        return mDisplayProfiles.getProfile();
    }

    private void refreshWorkspaceProfileForDisplay() {
        mDisplayProfiles.refreshForDisplay();
    }

    private void resolveMonitorIdentityAsync() {
        mDisplayProfiles.resolveMonitorIdentityAsync();
    }

    private String getMonitorProfileLabel() {
        return mDisplayProfiles.getMonitorLabel();
    }

    private void saveWorkspaceProfile() {
        mDisplayProfiles.save();
    }

    void onWorkspaceProfileReset() {
        mWorkspaceRestoreAttempted = false;
        mWorkspaceBoundsRestorePending = false;
        mLoadedFolderUri = null;
        mDesktopFiles = Collections.emptyList();
    }

    void onMonitorProfileResolved(
            final int previousDpi, final int resolvedDpi) {
        onWorkspaceProfileReset();
        renderApps();
        refreshDesktopFolder(true);
        updateConsoleControls();
        if (resolvedDpi != previousDpi) {
            mConsoleDensityApplyStarted = false;
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
        Log.i(TAG, "launch default package=" + app.packageName
                + " canFloat=" + app.canFloat
                + " fullscreenReason=" + app.fullscreenReason
                + " display=" + getCurrentDisplayId());
        if (app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(app.fullscreenReason)) {
            launchFloating(app);
        } else {
            launchFullscreen(app);
        }
    }

    void launchFloating(final AppItem app) {
        launchFloating(app, false);
    }

    private void launchFloating(final AppItem app, final boolean rootColdLaunch) {
        Log.i(TAG, "launch floating package=" + app.packageName
                + " display=" + getCurrentDisplayId());
        setTaskbarVisible(true);
        setStatus(getString(R.string.status_launching_window, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks = takeInteractionVisibleTasks();
        try {
            final Intent intent = FreeformLauncherActivity.createIntent(this,
                    app.packageName, getTaskIds(visibleTasks), rootColdLaunch);
            final ActivityOptions options = ActivityOptions.makeBasic();
            invokeIntOption(options, "setLaunchDisplayId", getCurrentDisplayId());
            startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            TaskRepository.bringStackToFront(visibleTasks, null, null);
            showLaunchFailure(e);
        }
    }

    void launchFullscreen(final AppItem app) {
        Log.i(TAG, "launch fullscreen package=" + app.packageName
                + " display=" + getCurrentDisplayId());
        final int displayId = beginFullscreenTransition(app.packageName);
        setTaskbarVisible(false);
        setStatus(getString(R.string.status_launching_fullscreen, app.label));
        try {
            if (RuntimeAccess.has(RuntimeAccess.Capability.TASK_CONTROL)) {
                final ExistingTaskController.ReuseResult reuseResult =
                        ExistingTaskController.reuseIfExists(app.packageName,
                                getCurrentDisplayId(), false);
                if (reuseResult.found) {
                    DesktopTaskController.finishFullscreenTransition(displayId, true);
                    Log.i(TAG, "reused fullscreen package=" + app.packageName);
                    setStatus(getString(R.string.status_switch_done, app.label));
                    return;
                }
            }

            Log.i(TAG, "fresh fullscreen launch package=" + app.packageName);
            final Intent launchIntent = getPackageManager()
                    .getLaunchIntentForPackage(app.packageName);
            if (launchIntent == null) {
                DesktopTaskController.finishFullscreenTransition(displayId, false);
                setTaskbarVisible(true);
                setErrorStatus(
                        "APP-LAUNCH-002",
                        getString(R.string.status_launch_failed, "no launcher activity"),
                        "package=" + app.packageName,
                        null);
                return;
            }
            launchIntent.addFlags(getFullscreenLaunchFlags());
            final ActivityOptions options = ActivityOptions.makeBasic();
            invokeIntOption(options, "setLaunchDisplayId", getCurrentDisplayId());
            startActivity(launchIntent, options.toBundle());
            DesktopTaskController.finishFullscreenTransition(displayId, true);
        } catch (IOException e) {
            DesktopTaskController.finishFullscreenTransition(displayId, false);
            setTaskbarVisible(true);
            setErrorStatus(
                    "TASK-FULLSCREEN-001",
                    getString(R.string.status_switch_failed, e.getMessage()),
                    "package=" + app.packageName + " display=" + displayId,
                    e);
        } catch (RuntimeException e) {
            DesktopTaskController.finishFullscreenTransition(displayId, false);
            setTaskbarVisible(true);
            showLaunchFailure(e);
        }
    }

    private void setTaskbarVisible(final boolean visible) {
        if (!mDesktopMode || mTaskbar == null || mOverlayPanelController == null) {
            return;
        }
        mOverlayPanelController.setPersistentVisible(visible);
    }

    private static int getFullscreenLaunchFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
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

    private Button createKernelFixesAction() {
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

    private void exitMagicDesk() {
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

    private void applyDensity(final int dpi) {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = getCurrentDisplayId();
        if (displayId > 0) {
            setPreferredDesktopDpi(dpi);
        }
        setStatus(getString(R.string.status_dpi_applying,
                Integer.valueOf(dpi), Integer.valueOf(displayId)));
        runRootAction(WM + " density " + dpi + " -d " + displayId,
                getString(R.string.status_dpi_applied,
                        Integer.valueOf(dpi), Integer.valueOf(displayId)));
    }

    private void resetDensity() {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = getCurrentDisplayId();
        final String command;
        if (displayId > 0) {
            setPreferredDesktopDpi(DesktopPreferences.DEFAULT_DESKTOP_DPI);
            command = WM + " density " + DesktopPreferences.DEFAULT_DESKTOP_DPI
                    + " -d " + displayId;
        } else {
            command = WM + " density reset -d " + displayId;
        }
        setStatus(getString(R.string.status_dpi_resetting,
                Integer.valueOf(displayId)));
        runRootAction(command,
                getString(R.string.status_dpi_reset, Integer.valueOf(displayId)));
    }

    private void ensurePreferredConsoleDensity() {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = getCurrentDisplayId();
        if (mConsoleDensityApplyStarted || displayId <= 0) {
            return;
        }
        final int targetDpi = getPreferredDesktopDpi();
        final int currentDpi = getResources().getDisplayMetrics().densityDpi;
        if (currentDpi == targetDpi) {
            return;
        }
        final String applyKey = displayId + ":" + targetDpi;
        if (!DENSITY_APPLY_KEYS.add(applyKey)) {
            return;
        }
        mConsoleDensityApplyStarted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int mirrorDisplayId = getMirrorDisplayId();
                    if (mirrorDisplayId != displayId) {
                        Log.i(TAG, "skip default DPI for non-mirror display " + displayId);
                        return;
                    }
                    final int configuredDpi = getConfiguredDisplayDensity(displayId);
                    if (configuredDpi == targetDpi) {
                        Log.i(TAG, "Console display DPI already configured display="
                                + displayId + " dpi=" + targetDpi);
                        return;
                    }
                    runOnUiThread(() -> setStatus(getString(
                            R.string.status_dpi_desktop_applying,
                            Integer.valueOf(targetDpi),
                            Integer.valueOf(displayId),
                            Integer.valueOf(configuredDpi > 0
                                    ? configuredDpi : currentDpi))));
                    runRootCommand(WM + " density " + targetDpi + " -d "
                            + displayId);
                    runOnUiThread(() -> setStatus(getString(
                            R.string.status_dpi_desktop_applied,
                            Integer.valueOf(targetDpi),
                            Integer.valueOf(displayId))));
                } catch (IOException e) {
                    Log.w(TAG, "desktop DPI failed", e);
                    runOnUiThread(() -> setErrorStatus(
                            "DISPLAY-DPI-001",
                            getString(R.string.status_dpi_desktop_failed, e.getMessage()),
                            "display=" + displayId + " targetDpi=" + targetDpi,
                            e));
                } finally {
                    DENSITY_APPLY_KEYS.remove(applyKey);
                    mConsoleDensityApplyStarted = false;
                }
            }
        }, "MagicDeskDesktopDpi").start();
    }

    private static int getConfiguredDisplayDensity(final int displayId)
            throws IOException {
        final String output = runRootCommand(WM + " density -d " + displayId);
        int physicalDensity = -1;
        for (final String line : output.split("\\r?\\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("Override density:")) {
                return parsePositiveInt(trimmed.substring("Override density:".length()));
            }
            if (trimmed.startsWith("Physical density:")) {
                physicalDensity = parsePositiveInt(
                        trimmed.substring("Physical density:".length()));
            }
        }
        return physicalDensity;
    }

    private static int parsePositiveInt(final String value) {
        try {
            final int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void runRootAction(final String command, final String successStatus) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runRootCommand(command);
                    runOnUiThread(() -> {
                        renderApps();
                        setStatus(successStatus);
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> setErrorStatus(
                            "ROOT-ACTION-001",
                            getString(R.string.status_root_failed, e.getMessage()),
                            "",
                            e));
                }
            }
        }, "MagicDeskRootAction").start();
    }

    private static String runRootCommand(final String command) throws IOException {
        return PrivilegedCommandRunner.run(command);
    }

    private static void runRootCommandBestEffort(final String command) {
        try {
            runRootCommand(command);
        } catch (IOException e) {
            Log.w(TAG, "best-effort root command failed: " + command, e);
        }
    }

    private static int getMirrorDisplayId() throws IOException {
        final String output = runRootCommand(SETTINGS + " get global app_mirror_displayid");
        final String trimmed = output == null ? "" : output.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String getDensityStatus() {
        return getString(R.string.density_status,
                Integer.valueOf(getResources().getDisplayMetrics().densityDpi));
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

    private void openDiagnostics() {
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

    private static void invokeIntOption(final ActivityOptions options, final String methodName,
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

    private void setStatus(final int stringResId) {
        setStatus(getString(stringResId));
    }

    void setErrorStatus(final String code, final String message) {
        setErrorStatus(code, message, "", null);
    }

    private void setErrorStatus(final String code, final String message,
            final String technicalDetail, final Throwable error) {
        CompatibilityDiagnostics.record(code, message, technicalDetail, error);
        setStatus(message + " [" + code + "]");
    }

    private void setStatus(final String text) {
        mLastStatusText = text;
        if (mStatus != null) {
            mStatus.setText(text);
        }
        if (mToolsActivityStatus != null) {
            mToolsActivityStatus.setText(text);
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
