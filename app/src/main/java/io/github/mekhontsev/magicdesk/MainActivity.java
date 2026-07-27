package io.github.mekhontsev.magicdesk;

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
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
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
import android.widget.CalendarView;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final int RESIZE_MODE_UNRESIZEABLE = 0;
    private static final String FULLSCREEN_REASON_NONE = "none";
    private static final String FULLSCREEN_REASON_IMMERSIVE = "immersive";
    private static final String FULLSCREEN_REASON_UNRESIZEABLE = "unresizable";
    private static final String FULLSCREEN_REASON_GAME = "game";
    private static final String[] FAVORITE_PACKAGES = {
            "com.termux",
            "com.android.chrome",
            "org.telegram.messenger",
            "com.google.android.gm",
            "com.openai.chatgpt"
    };
    private static Field sResizeModeField;
    private static boolean sResizeModeFieldResolved;

    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL = 0xFF111827;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_RED = 0xFFF43F5E;
    private static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int MENU_FLOATING = 0;
    private static final int MENU_FULLSCREEN = 1;
    private static final int MENU_TOOLS = 2;
    private static final int MENU_PINNED = 3;
    private static final String PREFS = "magicdesk";
    private static final String PREF_LAYOUT_MODE = "layout_mode";
    private static final String PREF_DESKTOP_DPI = "desktop_dpi";
    private static final String PREF_PINNED_PACKAGES = "pinned_packages";
    private static final int LAYOUT_AUTO = 0;
    private static final int LAYOUT_DESKTOP = 1;
    private static final int LAYOUT_PHONE = 2;
    private static final int DEFAULT_DESKTOP_DPI = 192;
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
    private LinearLayout mStartMenu;
    private LinearLayout mStartMenuContent;
    private LinearLayout mStartMenuBody;
    private LinearLayout mContextMenu;
    private LinearLayout mTaskOverview;
    private LinearLayout mNotificationCenter;
    private LinearLayout mNotificationList;
    private LinearLayout mCalendarPanel;
    private CalendarView mCalendarView;
    private LinearLayout mShortcutHelp;
    private EditText mStartSearch;
    private TextView mKeyboardLayoutIndicator;
    private TextView mBatteryStatus;
    private TextView mNotificationBadge;
    private ImageButton mNotificationButton;
    private ImageButton mConsoleButton;
    private ImageButton mPhoneScreenButton;
    private Button mPhoneScreenAction;
    private TextView mToolsStatus;
    private TextView mToolsActivityStatus;
    private ContentObserver mConsoleSettingsObserver;
    private DisplayManager.DisplayListener mProfileDisplayListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDisplayProfileRefresh = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && isDestroyed())) {
                return;
            }
            refreshWorkspaceProfileForDisplay();
            resolveMonitorIdentityAsync();
        }
    };
    private TextView mStatus;
    private DesktopWallpaperController mDesktopWallpaperController;
    private OverlayPanelController mOverlayPanelController;
    private TaskRepository.Snapshot mTaskSnapshot = new TaskRepository.Snapshot(
            Collections.<TaskRepository.TaskEntry>emptyList(), false, "not loaded");
    private DesktopNotificationListenerService.Snapshot mNotificationSnapshot =
            DesktopNotificationListenerService.getSnapshot();
    private List<TaskRepository.TaskEntry> mInteractionVisibleTasks =
            Collections.emptyList();
    private BroadcastReceiver mBatteryReceiver;
    private final Map<View, ContextTarget> mContextTargets = new WeakHashMap<>();
    private final Set<Button> mConsoleModeActions = Collections.newSetFromMap(
            new WeakHashMap<Button, Boolean>());
    private View mHoveredContextTargetView;
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
    private boolean mStartMenuFocusable = true;
    private int mAltTabPendingOffset;
    private int mAltTabSelectedIndex = -1;
    private int mMenuMode = MENU_FLOATING;
    private int mMenuPage;
    private int mSearchSelection;
    private int mTaskRefreshGeneration;
    private int mFolderLoadGeneration;
    private float mLastPointerX;
    private float mLastPointerY;
    private String mSearchQuery = "";
    private String mLastStatusText;
    private String mLoadedFolderUri;
    private List<AppItem> mLastApps = Collections.emptyList();
    private List<DesktopFile> mDesktopFiles = Collections.emptyList();
    private List<TaskRepository.TaskEntry> mAltTabTasks = Collections.emptyList();
    private String mProfileDisplayKey;
    private String mMonitorProfileKey;
    private WorkspaceProfileStore.Profile mWorkspaceProfile;
    private boolean mWorkspaceRestoreAttempted;
    private boolean mWorkspaceBoundsRestorePending;
    private boolean mMonitorIdentityRequested;
    private final DesktopNotificationListenerService.Listener mNotificationListener =
            new DesktopNotificationListenerService.Listener() {
                @Override
                public void onNotificationsChanged(
                        final DesktopNotificationListenerService.Snapshot snapshot) {
                    runOnUiThread(() -> handleNotificationSnapshot(snapshot));
                }

                @Override
                public void onNotificationPopup(
                        final DesktopNotificationListenerService.Entry entry) {
                    runOnUiThread(() -> showNotificationPopup(entry));
                }
            };

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
        mDesktopMode = isDesktopMode();
        if (mDesktopMode) {
            replaceDesktopInstance();
            sDesktopInstance = new WeakReference<>(this);
            setDesktopWindowFocusable(true);
        }
        setContentView(createContentView());
        if (mDesktopMode) {
            DesktopNotificationListenerService.addListener(mNotificationListener);
            DesktopNotificationListenerService.requestRebindIfGranted(this);
        }
        registerBatteryReceiver();
        registerConsoleSettingsObserver();
        registerProfileDisplayListener();
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
        DesktopNotificationListenerService.removeListener(mNotificationListener);
        mTaskRefreshGeneration++;
        mFolderLoadGeneration++;
        mMainHandler.removeCallbacks(mDisplayProfileRefresh);
        final DisplayManager displayManager = getSystemService(DisplayManager.class);
        if (displayManager != null && mProfileDisplayListener != null) {
            displayManager.unregisterDisplayListener(mProfileDisplayListener);
            mProfileDisplayListener = null;
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
        ContextTarget target = findHoveredContextTarget();
        if (target == null) {
            target = findContextTargetAt(x, y);
        }
        if (target != null) {
            showAppContextMenu(x, y, target.app, target.task);
            return;
        }
        if (isPointInsideVisiblePanel(x, y)) {
            return;
        }
        showDesktopContextMenu(x, y);
    }

    private ContextTarget findHoveredContextTarget() {
        final View view = mHoveredContextTargetView;
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) {
            mHoveredContextTargetView = null;
            return null;
        }
        return mContextTargets.get(view);
    }

    private ContextTarget findContextTargetAt(final float x, final float y) {
        for (final Map.Entry<View, ContextTarget> entry : mContextTargets.entrySet()) {
            final View view = entry.getKey();
            if (view != null && view.isShown() && isPointInside(view, x, y)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isPointInsideVisiblePanel(final float x, final float y) {
        return mOverlayPanelController != null
                && mOverlayPanelController.contains(x, y);
    }

    private boolean hasVisiblePanel() {
        return mOverlayPanelController != null
                && mOverlayPanelController.hasVisiblePanel();
    }

    private boolean isPointInside(final View view, final float x, final float y) {
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
            DesktopNotificationListenerService.requestRebindIfGranted(this);
            handleNotificationSnapshot(DesktopNotificationListenerService.getSnapshot());
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
            if (activity.mOverlayPanelController != null
                    && activity.mOverlayPanelController.isVisible(
                            activity.mTaskOverview)) {
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
        activity.runOnUiThread(activity::toggleNotificationCenter);
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

        mStartMenu = createStartMenu();
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

        mContextMenu = createContextMenu();
        mTaskOverview = createTaskOverviewPanel();
        mNotificationCenter = createNotificationCenterPanel();
        mCalendarPanel = createCalendarPanel();
        mShortcutHelp = createShortcutHelpPanel();
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

        final FrameLayout notificationButton = new FrameLayout(this);
        mNotificationButton = createTaskbarIconButton(
                R.drawable.ic_notifications,
                R.string.action_notifications);
        mNotificationButton.setOnClickListener(view -> toggleNotificationCenter());
        notificationButton.addView(mNotificationButton, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mNotificationBadge = new TextView(this);
        mNotificationBadge.setTextColor(Color.WHITE);
        mNotificationBadge.setTextSize(9);
        mNotificationBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mNotificationBadge.setGravity(Gravity.CENTER);
        mNotificationBadge.setMinWidth(dp(17));
        mNotificationBadge.setMinHeight(dp(17));
        mNotificationBadge.setPadding(dp(3), 0, dp(3), 0);
        mNotificationBadge.setBackground(rounded(COLOR_RED, dp(9), COLOR_RED));
        mNotificationBadge.setVisibility(View.GONE);
        final FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(17),
                Gravity.TOP | Gravity.END);
        badgeParams.setMargins(0, 0, dp(1), 0);
        notificationButton.addView(mNotificationBadge, badgeParams);
        taskbar.addView(notificationButton, new LinearLayout.LayoutParams(
                desktopDp(46, 38), LinearLayout.LayoutParams.MATCH_PARENT));

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
        updateNotificationBadge();
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

    private LinearLayout createStartMenu() {
        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(14), dp(14), dp(14), dp(12));
        menu.setBackground(rounded(COLOR_PANEL, dp(18), COLOR_CYAN));
        menu.setVisibility(View.GONE);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(this);
        title.setText(R.string.action_start);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button close = createSmallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> setStartMenuVisible(false));
        header.addView(close, new LinearLayout.LayoutParams(dp(86),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        menu.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mStartSearch = new EditText(this);
        mStartSearch.setHint(R.string.search_apps_hint);
        mStartSearch.setHintTextColor(COLOR_MUTED);
        mStartSearch.setTextColor(COLOR_TEXT);
        mStartSearch.setTextSize(14);
        mStartSearch.setSingleLine(true);
        mStartSearch.setShowSoftInputOnFocus(false);
        mStartSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        mStartSearch.setBackground(rounded(COLOR_PANEL_ALT, dp(8), COLOR_PANEL_ALT));
        mStartSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence text, final int start,
                    final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence text, final int start,
                    final int before, final int count) {
                mSearchQuery = text == null ? "" : text.toString();
                mSearchSelection = 0;
                renderStartMenuBody();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mStartSearch.setOnKeyListener((view, keyCode, event) ->
                handleSearchKey(keyCode, event));

        mStartMenuContent = new LinearLayout(this);
        mStartMenuContent.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.setMargins(0, dp(10), 0, 0);
        menu.addView(mStartMenuContent, contentParams);
        return menu;
    }

    private LinearLayout createContextMenu() {
        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(10), dp(10), dp(10));
        menu.setBackground(rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        menu.setVisibility(View.GONE);
        menu.setClickable(true);
        menu.setFocusable(true);
        return menu;
    }

    private LinearLayout createTaskOverviewPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setFocusable(true);
        return panel;
    }

    private LinearLayout createNotificationCenterPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setFocusable(true);
        return panel;
    }

    private LinearLayout createCalendarPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.calendar_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close = createSmallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> hideAllPanels());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mCalendarView = new CalendarView(this);
        mCalendarView.setDate(System.currentTimeMillis(), false, true);
        final LinearLayout.LayoutParams calendarParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        calendarParams.setMargins(0, dp(8), 0, dp(8));
        panel.addView(mCalendarView, calendarParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final Button today = createSmallButton(R.string.action_today, COLOR_PANEL_ALT);
        today.setOnClickListener(view -> mCalendarView.setDate(
                System.currentTimeMillis(), true, true));
        actions.addView(today, new LinearLayout.LayoutParams(
                0, dp(42), 1));
        final Button open = createSmallButton(R.string.action_open_calendar, COLOR_CYAN);
        open.setOnClickListener(view -> openCalendarApplication());
        final LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                0, dp(42), 1);
        openParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(open, openParams);
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private void toggleCalendarPanel() {
        if (mOverlayPanelController == null || mCalendarPanel == null) {
            return;
        }
        if (mOverlayPanelController.isVisible(mCalendarPanel)) {
            hideAllPanels();
            return;
        }
        captureInteractionStackForPanel();
        final int areaWidth = getDesktopAreaWidth();
        final int areaHeight = getDesktopAreaHeight();
        final int width = Math.max(1,
                Math.min(dp(380), areaWidth - dp(16)));
        final int availableHeight = Math.max(1,
                areaHeight - getTaskbarHeight() - dp(16));
        final int height = Math.min(dp(430), availableHeight);
        final int left = Math.max(0, areaWidth - width - dp(8));
        final int top = Math.max(0,
                areaHeight - getTaskbarHeight() - height);
        if (!mOverlayPanelController.show(
                mCalendarPanel, left, top, width, height,
                false, "MagicDesk calendar")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
        }
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

        final AppItem app = findApp(mLastApps, resolved.activityInfo.packageName);
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

    private void handleNotificationSnapshot(
            final DesktopNotificationListenerService.Snapshot snapshot) {
        if (snapshot == null || isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && isDestroyed())) {
            return;
        }
        mNotificationSnapshot = snapshot;
        updateNotificationBadge();
        if (mOverlayPanelController != null
                && mOverlayPanelController.isVisible(mNotificationCenter)) {
            renderNotificationCenter();
        }
    }

    private void updateNotificationBadge() {
        if (mNotificationBadge == null) {
            return;
        }
        final int unread = mNotificationSnapshot == null
                ? 0 : mNotificationSnapshot.unreadCount;
        mNotificationBadge.setText(unread > 99 ? "99+" : Integer.toString(unread));
        mNotificationBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        if (mNotificationButton != null) {
            final String description = unread > 0
                    ? getString(R.string.notification_count_description,
                            Integer.valueOf(unread))
                    : getString(R.string.action_notifications);
            mNotificationButton.setContentDescription(description);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mNotificationButton.setTooltipText(description);
            }
        }
    }

    private void toggleNotificationCenter() {
        if (mOverlayPanelController == null || mNotificationCenter == null) {
            return;
        }
        if (mOverlayPanelController.isVisible(mNotificationCenter)) {
            hideAllPanels();
            return;
        }
        captureInteractionStackForPanel();
        DesktopNotificationListenerService.markAllRead();
        renderNotificationCenter();

        final int areaWidth = getDesktopAreaWidth();
        final int areaHeight = getDesktopAreaHeight();
        final int width = Math.min(dp(420), Math.max(dp(280), areaWidth - dp(16)));
        final int height = Math.max(dp(180),
                areaHeight - getTaskbarHeight() - dp(16));
        final int left = Math.max(0, areaWidth - width - dp(8));
        final int top = dp(8);
        if (!mOverlayPanelController.show(mNotificationCenter,
                left, top, width, height, false, "MagicDesk notifications")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
        }
    }

    private void renderNotificationCenter() {
        if (mNotificationCenter == null) {
            return;
        }
        mNotificationCenter.removeAllViews();

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final int count = mNotificationSnapshot == null
                ? 0 : mNotificationSnapshot.entries.size();
        final TextView title = new TextView(this);
        title.setText(getString(R.string.notifications_title, Integer.valueOf(count)));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button clear = createSmallButton(
                R.string.action_clear_notifications, COLOR_PANEL_ALT);
        clear.setEnabled(hasClearableNotifications());
        clear.setOnClickListener(view -> {
            if (!DesktopNotificationListenerService.clearAllNotifications()) {
                setErrorStatus("NOTIFICATIONS-002",
                        getString(R.string.status_notifications_unavailable));
            }
        });
        header.addView(clear, new LinearLayout.LayoutParams(
                dp(82), LinearLayout.LayoutParams.WRAP_CONTENT));

        final Button close = createSmallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> hideAllPanels());
        final LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                dp(72), LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(dp(8), 0, 0, 0);
        header.addView(close, closeParams);
        mNotificationCenter.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!DesktopNotificationListenerService.isAccessGranted(this)) {
            addNotificationAccessState(
                    R.string.notification_access_off, true);
            return;
        }
        if (mNotificationSnapshot == null || !mNotificationSnapshot.connected) {
            if (mNotificationSnapshot != null
                    && !TextUtils.isEmpty(mNotificationSnapshot.connectionIssueCode)) {
                addNotificationAccessState(
                        getString(R.string.notification_listener_reconnect_failed,
                                mNotificationSnapshot.connectionIssueCode),
                        false);
            } else {
                addNotificationAccessState(
                        R.string.notification_listener_connecting, false);
            }
            return;
        }
        if (mNotificationSnapshot.entries.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.notifications_empty);
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mNotificationCenter.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(10), 0, 0);
        mNotificationList = new LinearLayout(this);
        mNotificationList.setOrientation(LinearLayout.VERTICAL);
        for (final DesktopNotificationListenerService.Entry entry
                : mNotificationSnapshot.entries) {
            final View item = createNotificationItem(entry, false);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(8));
            mNotificationList.addView(item, params);
        }
        scroll.addView(mNotificationList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mNotificationCenter.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean hasClearableNotifications() {
        if (mNotificationSnapshot == null || !mNotificationSnapshot.connected) {
            return false;
        }
        for (final DesktopNotificationListenerService.Entry entry
                : mNotificationSnapshot.entries) {
            if (entry.clearable) {
                return true;
            }
        }
        return false;
    }

    private void addNotificationAccessState(final int messageResId,
            final boolean showSettings) {
        addNotificationAccessState(getString(messageResId), showSettings);
    }

    private void addNotificationAccessState(final CharSequence messageText,
            final boolean showSettings) {
        final LinearLayout state = new LinearLayout(this);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(16), dp(20), dp(16), dp(20));
        final TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(COLOR_MUTED);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        state.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (showSettings) {
            final Button settings = createActionButton(
                    R.string.action_notification_access, COLOR_CYAN);
            settings.setOnClickListener(view -> openNotificationAccessSettings());
            final LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            buttonParams.setMargins(0, dp(14), 0, 0);
            state.addView(settings, buttonParams);
        }
        mNotificationCenter.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private View createNotificationItem(
            final DesktopNotificationListenerService.Entry entry,
            final boolean popup) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(10), dp(10), dp(10));
        final int borderColor = entry.importance >= NotificationManager.IMPORTANCE_HIGH
                ? COLOR_CYAN : COLOR_PANEL_ALT;
        item.setBackground(rounded(COLOR_PANEL_ALT, dp(8), borderColor));
        item.setClickable(true);
        item.setFocusable(true);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(loadNotificationIcon(entry));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        final TextView app = new TextView(this);
        app.setText(entry.appName);
        app.setTextColor(COLOR_MUTED);
        app.setTextSize(12);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        appParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(app, appParams);

        final TextView time = new TextView(this);
        time.setText(DateFormat.getTimeFormat(this).format(new Date(entry.postTime)));
        time.setTextColor(COLOR_MUTED);
        time.setTextSize(11);
        time.setSingleLine(true);
        header.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (entry.clearable) {
            final ImageButton dismiss = createTaskbarIconButton(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.action_dismiss_notification);
            dismiss.setPadding(dp(7), dp(7), dp(7), dp(7));
            dismiss.setOnClickListener(view -> {
                if (popup && mOverlayPanelController != null) {
                    mOverlayPanelController.hideTransient();
                }
                DesktopNotificationListenerService.dismissNotification(entry.key);
            });
            final LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(
                    dp(34), dp(34));
            dismissParams.setMargins(dp(6), 0, 0, 0);
            header.addView(dismiss, dismissParams);
        }
        item.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(entry.title)) {
            final TextView title = new TextView(this);
            title.setText(entry.title);
            title.setTextColor(COLOR_TEXT);
            title.setTextSize(14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(popup ? 1 : 2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            titleParams.setMargins(0, dp(7), 0, 0);
            item.addView(title, titleParams);
        }

        if (!TextUtils.isEmpty(entry.text)) {
            final TextView text = new TextView(this);
            text.setText(entry.text);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setMaxLines(popup ? 2 : 5);
            text.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, dp(4), 0, 0);
            item.addView(text, textParams);
        }

        if (!entry.actions.isEmpty()) {
            final LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            final int actionCount = Math.min(popup ? 1 : 3, entry.actions.size());
            for (int index = 0; index < actionCount; index++) {
                final DesktopNotificationListenerService.ActionEntry action =
                        entry.actions.get(index);
                final Button button = createSmallButton(action.title, COLOR_CYAN);
                button.setOnClickListener(view -> invokeNotificationAction(entry, action));
                final LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                if (index > 0) {
                    actionParams.setMargins(dp(6), 0, 0, 0);
                }
                actions.addView(button, actionParams);
            }
            final LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            actionsParams.setMargins(0, dp(8), 0, 0);
            item.addView(actions, actionsParams);
        }

        item.setOnClickListener(view -> openDesktopNotification(entry));
        item.setContentDescription(getString(R.string.notification_item_description,
                entry.appName, TextUtils.isEmpty(entry.title) ? entry.text : entry.title));
        return item;
    }

    private Drawable loadNotificationIcon(
            final DesktopNotificationListenerService.Entry entry) {
        if (entry.icon != null) {
            try {
                final Drawable icon = entry.icon.loadDrawable(this);
                if (icon != null) {
                    return icon;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to load notification icon for "
                        + entry.packageName, e);
            }
        }
        try {
            return getPackageManager().getApplicationIcon(entry.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private void showNotificationPopup(
            final DesktopNotificationListenerService.Entry entry) {
        if (!mDesktopMode || entry == null || mOverlayPanelController == null
                || isDeviceLocked()) {
            return;
        }
        if (mOverlayPanelController.isVisible(mNotificationCenter)) {
            DesktopNotificationListenerService.markRead(entry.key);
            return;
        }

        final View popup = createNotificationItem(entry, true);
        final int areaWidth = getDesktopAreaWidth();
        final int areaHeight = getDesktopAreaHeight();
        final int width = Math.min(dp(380), areaWidth - dp(24));
        popup.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(dp(100), areaHeight - getTaskbarHeight() - dp(24)),
                        View.MeasureSpec.AT_MOST));
        final int height = Math.max(dp(92), popup.getMeasuredHeight());
        final int left = Math.max(0, areaWidth - width - dp(12));
        final int top = Math.max(dp(12),
                areaHeight - getTaskbarHeight() - height - dp(12));
        if (!mOverlayPanelController.showTransient(
                popup, left, top, width, height, 7000L,
                "MagicDesk notification")) {
            Log.w(TAG, "notification popup overlay unavailable");
        }
    }

    private boolean isDeviceLocked() {
        final KeyguardManager keyguardManager =
                getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isDeviceLocked();
    }

    private void openDesktopNotification(
            final DesktopNotificationListenerService.Entry entry) {
        if (entry == null) {
            return;
        }
        hideAllPanels();
        if (entry.hasContentIntent
                && DesktopNotificationListenerService.openNotification(
                        this, entry.key, getCurrentDisplayId())) {
            return;
        }
        final AppItem app = findApp(mLastApps, entry.packageName);
        if (app != null) {
            launchDefault(app);
        } else {
            setErrorStatus("NOTIFICATIONS-003",
                    getString(R.string.status_notification_open_failed));
        }
    }

    private void invokeNotificationAction(
            final DesktopNotificationListenerService.Entry entry,
            final DesktopNotificationListenerService.ActionEntry action) {
        hideAllPanels();
        if (!DesktopNotificationListenerService.invokeAction(
                this, entry.key, action.index, getCurrentDisplayId())) {
            setErrorStatus("NOTIFICATIONS-004",
                    getString(R.string.status_notification_action_failed));
        }
    }

    private void openNotificationAccessSettings() {
        hideAllPanels();
        final String component = DesktopNotificationListenerService
                .getComponentName(this).flattenToString();
        final Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getCurrentDisplayId());
        try {
            startActivity(intent, options.toBundle());
        } catch (RuntimeException detailFailure) {
            Log.w(TAG, "notification listener detail settings unavailable",
                    detailFailure);
            final Intent fallback =
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(fallback, options.toBundle());
            } catch (RuntimeException e) {
                showLaunchFailure(e);
            }
        }
    }

    private LinearLayout createShortcutHelpPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.shortcuts_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close = createSmallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> hideAllPanels());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addShortcutHelpRow(panel, R.string.shortcut_maximize,
                R.string.shortcut_maximize_action);
        addShortcutHelpRow(panel, R.string.shortcut_restore, R.string.shortcut_restore_action);
        addShortcutHelpRow(panel, R.string.shortcut_snap_left,
                R.string.shortcut_snap_left_action);
        addShortcutHelpRow(panel, R.string.shortcut_snap_right,
                R.string.shortcut_snap_right_action);
        addShortcutHelpRow(panel, R.string.shortcut_close,
                R.string.shortcut_close_action);
        addShortcutHelpRow(panel, R.string.shortcut_back,
                R.string.shortcut_back_action);
        addShortcutHelpRow(panel, R.string.shortcut_lock,
                R.string.shortcut_lock_action);
        addShortcutHelpRow(panel, R.string.shortcut_notifications,
                R.string.shortcut_notifications_action);
        addShortcutHelpRow(panel, R.string.shortcut_screenshot,
                R.string.shortcut_screenshot_action);
        addShortcutHelpRow(panel, R.string.shortcut_desktop,
                R.string.shortcut_desktop_action);
        addShortcutHelpRow(panel, R.string.shortcut_help, R.string.shortcut_help_action);
        addShortcutHelpRow(panel, R.string.shortcut_layout, R.string.shortcut_layout_action);
        addShortcutHelpRow(panel, R.string.shortcut_previous, R.string.shortcut_previous_action);
        addShortcutHelpRow(panel, R.string.shortcut_next, R.string.shortcut_next_action);
        return panel;
    }

    private void addShortcutHelpRow(final LinearLayout panel, final int keysResId,
            final int actionResId) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        final TextView keys = new TextView(this);
        keys.setText(keysResId);
        keys.setTextColor(COLOR_CYAN);
        keys.setTextSize(14);
        keys.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(keys, new LinearLayout.LayoutParams(dp(150),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView action = new TextView(this);
        action.setText(actionResId);
        action.setTextColor(COLOR_TEXT);
        action.setTextSize(14);
        row.addView(action, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void toggleTaskOverview() {
        resetAltTabState();
        if (mOverlayPanelController != null
                && mOverlayPanelController.isVisible(mTaskOverview)) {
            hideAllPanels();
            return;
        }
        showTaskOverview();
    }

    private void showTaskOverview() {
        resetAltTabState();
        captureInteractionStackForPanel();
        hideAllPanels();
        final int displayId = getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || displayId != getCurrentDisplayId()) {
                return;
            }
            mTaskSnapshot = snapshot;
            populateTaskOverview(snapshot);
            showTaskOverviewPanel();
        }));
    }

    private boolean showTaskOverviewPanel() {
        final int areaWidth = getDesktopAreaWidth();
        final int areaHeight = getDesktopAreaHeight();
        final int width = Math.min(dp(760), areaWidth - dp(32));
        final int height = Math.min(dp(520),
                areaHeight - getTaskbarHeight() - dp(32));
        final int left = Math.max(0, (areaWidth - width) / 2);
        final int top = Math.max(0,
                (areaHeight - getTaskbarHeight() - height) / 2);
        if (mOverlayPanelController.show(mTaskOverview, left, top,
                width, height, true, "MagicDesk open tasks")) {
            return true;
        }
        setErrorStatus("OVERLAY-001",
                getString(R.string.status_overlay_panel_unavailable));
        return false;
    }

    private void populateTaskOverview(final TaskRepository.Snapshot snapshot) {
        if (mTaskOverview == null) {
            return;
        }
        mTaskOverview.removeAllViews();

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (isTaskbarTask(task)) {
                tasks.add(task);
            }
        }
        title.setText(getString(R.string.open_tasks_title,
                Integer.valueOf(tasks.size())));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button showDesktop = createSmallButton(
                R.string.action_show_desktop, COLOR_PANEL_ALT);
        showDesktop.setOnClickListener(view -> toggleDesktopWorkspace());
        header.addView(showDesktop, new LinearLayout.LayoutParams(
                dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        final Button close = createSmallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> hideAllPanels());
        final LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                dp(82), LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(dp(8), 0, 0, 0);
        header.addView(close, closeParams);
        mTaskOverview.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (tasks.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.open_tasks_empty);
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mTaskOverview.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final GridLayout grid = new GridLayout(this);
        final int columns = getResources().getConfiguration().screenWidthDp >= 900 ? 4 : 3;
        grid.setColumnCount(columns);
        for (final TaskRepository.TaskEntry task : tasks) {
            final AppItem app = findOrLoadApp(mLastApps, task.packageName);
            if (app == null) {
                continue;
            }
            grid.addView(createTaskOverviewTile(app, task,
                            mAltTabActive && mAltTabSelectedIndex >= 0
                                    && mAltTabSelectedIndex < mAltTabTasks.size()
                                    && mAltTabTasks.get(mAltTabSelectedIndex).taskId
                                            == task.taskId),
                    createTaskOverviewTileParams());
        }
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(12), 0, 0);
        mTaskOverview.addView(scroll, scrollParams);
    }

    private View createTaskOverviewTile(final AppItem app,
            final TaskRepository.TaskEntry task, final boolean selected) {
        final FrameLayout tile = new FrameLayout(this);
        final boolean workspaceApp = app.packageName.equals(
                getWorkspaceProfile().workspacePackage);
        tile.setBackground(rounded(COLOR_PANEL_ALT, dp(6),
                selected || workspaceApp
                        ? COLOR_AMBER
                        : (task.active ? COLOR_CYAN : COLOR_PANEL_ALT)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            resetAltTabState();
            hideAllPanels();
            focusTask(app, task);
        });
        registerContextTarget(tile, app, task);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(8), dp(8), dp(8), dp(6));
        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        content.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(5), 0, 0);
        content.addView(label, labelParams);
        final TextView state = new TextView(this);
        state.setText(getString(R.string.context_task_status,
                Integer.valueOf(task.taskId), getString(task.isFreeform()
                        ? R.string.badge_window : R.string.badge_fullscreen)));
        state.setTextColor(COLOR_MUTED);
        state.setTextSize(10);
        state.setGravity(Gravity.CENTER);
        content.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tile.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final ImageButton close = new ImageButton(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(COLOR_MUTED);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(dp(5), dp(5), dp(5), dp(5));
        close.setContentDescription(getString(R.string.action_close_window));
        close.setOnClickListener(view -> closeTask(app, task));
        final FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(32), dp(32), Gravity.TOP | Gravity.END);
        tile.addView(close, closeParams);
        return tile;
    }

    private GridLayout.LayoutParams createTaskOverviewTileParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(112);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private void registerContextTarget(final View view, final AppItem app,
            final TaskRepository.TaskEntry task) {
        if (view != null && app != null) {
            mContextTargets.put(view, new ContextTarget(app, task));
            view.setHapticFeedbackEnabled(false);
            view.setOnLongClickListener(target -> {
                captureInteractionStackForPanel();
                showAppContextMenuForView(target, app, task);
                return true;
            });
            view.setOnHoverListener((hoveredView, event) -> {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_HOVER_MOVE) {
                    mHoveredContextTargetView = hoveredView;
                } else if (action == MotionEvent.ACTION_HOVER_EXIT
                        && mHoveredContextTargetView == hoveredView) {
                    mHoveredContextTargetView = null;
                }
                return false;
            });
        }
    }

    private void showAppContextMenuForView(final View view, final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        showAppContextMenu(
                location[0] + view.getWidth() / 2f,
                location[1] + view.getHeight() / 2f,
                app,
                task);
    }

    private void showAppContextMenu(final float x, final float y,
            final AppItem app, final TaskRepository.TaskEntry exactTask) {
        if (mContextMenu == null || mOverlayPanelController == null) {
            return;
        }
        mOverlayPanelController.hide(mContextMenu);
        mContextMenu.removeAllViews();

        final TextView title = new TextView(this);
        title.setText(app.label);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        mContextMenu.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TaskRepository.TaskEntry task = exactTask != null
                ? exactTask : findFirstTask(app.packageName);
        if (task != null) {
            final TextView taskInfo = new TextView(this);
            taskInfo.setText(getString(R.string.context_task_status,
                    Integer.valueOf(task.taskId),
                    getString(task.isFreeform()
                            ? R.string.badge_window : R.string.badge_fullscreen)));
            taskInfo.setTextColor(COLOR_MUTED);
            taskInfo.setTextSize(12);
            final LinearLayout.LayoutParams taskInfoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            taskInfoParams.setMargins(0, dp(2), 0, dp(6));
            mContextMenu.addView(taskInfo, taskInfoParams);
        }

        addContextAction(task == null ? R.string.action_open : R.string.action_switch_to,
                COLOR_CYAN, true, view -> {
                    hideAllPanels();
                    if (task == null) {
                        launchDefault(app);
                    } else {
                        focusTask(app, task);
                    }
                });
        if (app.canFloat) {
            addContextAction(R.string.action_open_floating, COLOR_PANEL_ALT, true, view -> {
                hideAllPanels();
                launchFloating(app);
            });
        }
        addContextAction(R.string.action_open_fullscreen, COLOR_PANEL_ALT, true, view -> {
            hideAllPanels();
            if (task == null) {
                launchFullscreen(app);
            } else {
                openTaskFullscreen(app, task);
            }
        });

        final boolean pinned = getPinnedPackages().contains(app.packageName);
        addContextAction(pinned ? R.string.action_unpin : R.string.action_pin,
                COLOR_PANEL_ALT, true, view -> {
                    hideAllPanels();
                    togglePinned(app);
                });
        final boolean desktopShortcut = isDesktopShortcut(app.packageName);
        addContextAction(desktopShortcut
                        ? R.string.action_remove_from_desktop
                        : R.string.action_add_to_desktop,
                COLOR_PANEL_ALT, true, view -> {
                    hideAllPanels();
                    toggleDesktopShortcut(app);
                });
        final boolean workspaceApp = app.packageName.equals(
                getWorkspaceProfile().workspacePackage);
        addContextAction(workspaceApp
                        ? R.string.action_remove_from_workspace
                        : R.string.action_keep_in_workspace,
                workspaceApp ? COLOR_AMBER : COLOR_PANEL_ALT,
                workspaceApp || app.canFloat, view -> {
                    hideAllPanels();
                    setWorkspaceApp(app, task, !workspaceApp);
                });
        addContextAction(R.string.action_close_window, COLOR_AMBER, task != null,
                view -> closeTask(app, task));
        addContextAction(R.string.action_force_stop, COLOR_RED, true,
                view -> confirmForceStop(app));
        positionAndShowContextMenu(x, y);
    }

    private void showDesktopContextMenu(final float x, final float y) {
        if (mContextMenu == null || mOverlayPanelController == null) {
            return;
        }
        mOverlayPanelController.hide(mContextMenu);
        mContextMenu.removeAllViews();

        final TextView title = new TextView(this);
        title.setText(R.string.context_desktop);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(6));
        mContextMenu.addView(title, titleParams);

        addContextAction(R.string.action_refresh, COLOR_CYAN, true, view -> {
            hideAllPanels();
            renderApps();
            refreshDesktopFolder(true);
        });
        addContextAction(R.string.action_open_tasks, COLOR_PANEL_ALT, true,
                view -> showTaskOverview());
        addContextAction(R.string.action_choose_desktop_folder, COLOR_PANEL_ALT, true,
                view -> chooseDesktopFolder());
        addContextAction(R.string.action_hide_desktop_folder, COLOR_PANEL_ALT,
                getWorkspaceProfile().folderUri != null, view -> {
                    hideAllPanels();
                    clearDesktopFolder();
                });
        addContextAction(R.string.section_tools, COLOR_PANEL_ALT, true, view ->
                showStartSection(MENU_TOOLS, false));
        addContextAction(R.string.action_manage_taskbar, COLOR_PANEL_ALT, true, view ->
                showStartSection(MENU_PINNED));
        addContextAction(R.string.action_layout_auto, COLOR_PANEL_ALT, true, view ->
                setLayoutMode(LAYOUT_AUTO));
        addContextAction(R.string.action_layout_desktop, COLOR_PANEL_ALT, true, view ->
                setLayoutMode(LAYOUT_DESKTOP));
        addContextAction(R.string.action_restart_shortcuts, COLOR_AMBER, true, view -> {
            hideAllPanels();
            restartConsoleShortcuts();
        });
        positionAndShowContextMenu(x, y);
    }

    private void addContextAction(final int textResId, final int color,
            final boolean enabled, final View.OnClickListener listener) {
        final Button button = createActionButton(textResId, color);
        button.setEnabled(enabled);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, 0);
        mContextMenu.addView(button, params);
    }

    private void positionAndShowContextMenu(final float pointerX, final float pointerY) {
        final int width = getContextMenuWidth();
        final int maxHeight = mDesktopRoot == null
                ? getResources().getDisplayMetrics().heightPixels : mDesktopRoot.getHeight();
        mContextMenu.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, maxHeight - dp(16)),
                        View.MeasureSpec.AT_MOST));
        final int menuHeight = mContextMenu.getMeasuredHeight();
        final int rootWidth = mDesktopRoot == null
                ? getResources().getDisplayMetrics().widthPixels : mDesktopRoot.getWidth();
        int left = Math.round(pointerX) + dp(8);
        int top = Math.round(pointerY) + dp(8);
        if (left + width > rootWidth - dp(8)) {
            left = Math.round(pointerX) - width - dp(8);
        }
        if (top + menuHeight > maxHeight - dp(8)) {
            top = Math.round(pointerY) - menuHeight - dp(8);
        }
        left = Math.max(dp(8), Math.min(left, rootWidth - width - dp(8)));
        top = Math.max(dp(8), Math.min(top, maxHeight - menuHeight - dp(8)));

        if (!mOverlayPanelController.show(mContextMenu, left, top, width, menuHeight,
                false, "MagicDesk context menu")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
        }
    }

    private void showStartSection(final int mode) {
        showStartSection(mode, true);
    }

    private void showStartSection(final int mode, final boolean focusable) {
        mMenuMode = mode;
        mMenuPage = 0;
        mSearchQuery = "";
        if (mStartSearch != null && mStartSearch.length() > 0) {
            mStartSearch.setText("");
        }
        setStartMenuVisible(true, focusable);
    }

    private void confirmForceStop(final AppItem app) {
        hideAllPanels();
        new AlertDialog.Builder(this)
                .setTitle(R.string.force_stop_title)
                .setMessage(getString(R.string.force_stop_message, app.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_force_stop,
                        (dialog, which) -> forceStopApp(app))
                .show();
    }

    private TaskRepository.TaskEntry findFirstTask(final String packageName) {
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
        desktopMode.setOnClickListener(view -> setLayoutMode(LAYOUT_DESKTOP));
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

    private void renderApps() {
        final List<AppItem> apps = loadLauncherApps();
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
            final AppItem app = findApp(apps, packageName);
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

    private void chooseDesktopFolder() {
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

    private void clearDesktopFolder() {
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

    private void refreshDesktopFolder(final boolean force) {
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
                    files = loadDesktopFiles(Uri.parse(folderUri));
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

    private List<DesktopFile> loadDesktopFiles(final Uri treeUri) {
        final String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, treeDocumentId);
        final String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        final List<DesktopFile> files = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(
                childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return files;
            }
            while (cursor.moveToNext()) {
                final String documentId = cursor.getString(0);
                final String name = cursor.getString(1);
                final String mimeType = cursor.getString(2);
                final long modified = cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (documentId == null || name == null) {
                    continue;
                }
                files.add(new DesktopFile(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name, mimeType, modified,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)));
            }
        }
        Collections.sort(files, new Comparator<DesktopFile>() {
            @Override
            public int compare(final DesktopFile left, final DesktopFile right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                if (left.modified != right.modified) {
                    return Long.compare(right.modified, left.modified);
                }
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        if (files.size() > MAX_DESKTOP_FILES) {
            return new ArrayList<>(files.subList(0, MAX_DESKTOP_FILES));
        }
        return files;
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

    private boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return task != null && !task.home && task.packageName != null
                && !getPackageName().equals(task.packageName);
    }

    private AppItem findOrLoadApp(final List<AppItem> apps, final String packageName) {
        final AppItem known = findApp(apps, packageName);
        if (known != null) {
            return known;
        }
        try {
            final PackageManager packageManager = getPackageManager();
            final ApplicationInfo info = packageManager
                    .getApplicationInfo(packageName, 0);
            final ActivityInfo activityInfo =
                    resolveLauncherActivityInfo(packageManager, packageName);
            final CharSequence label = info.loadLabel(packageManager);
            return new AppItem(label == null ? packageName : label.toString(),
                    packageName, isUniversalFreeformEnabled(),
                    getFullscreenPreference(activityInfo, info),
                    info.loadIcon(packageManager));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Task package is not installed: " + packageName, e);
            return null;
        }
    }

    private List<String> getOrderedPinnedPackages(final List<AppItem> apps,
            final Set<String> pinnedPackages) {
        final List<String> ordered = new ArrayList<>();
        for (final String packageName : FAVORITE_PACKAGES) {
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

    private Set<String> getPinnedPackages() {
        return new LinkedHashSet<>(getWorkspaceProfile().taskbarPackages);
    }

    private Set<String> getLegacyPinnedPackages() {
        final SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.contains(PREF_PINNED_PACKAGES)) {
            final Set<String> defaults = new LinkedHashSet<>();
            Collections.addAll(defaults, FAVORITE_PACKAGES);
            return defaults;
        }
        final Set<String> stored = preferences.getStringSet(
                PREF_PINNED_PACKAGES, Collections.<String>emptySet());
        return stored == null ? new LinkedHashSet<String>()
                : new LinkedHashSet<>(stored);
    }

    private void togglePinned(final AppItem app) {
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(PREF_PINNED_PACKAGES, pinned)
                .apply();
        renderTaskbarPins(mLastApps);
        renderStartMenuContent();
        setStatus(getString(nowPinned
                ? R.string.status_app_pinned : R.string.status_app_unpinned,
                app.label));
    }

    private boolean isDesktopShortcut(final String packageName) {
        return getWorkspaceProfile().desktopPackages.contains(packageName);
    }

    private void toggleDesktopShortcut(final AppItem app) {
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

    private void setWorkspaceApp(final AppItem app,
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

    private void focusTask(final AppItem app, final TaskRepository.TaskEntry task) {
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

    private void resetAltTabState() {
        mAltTabActive = false;
        mAltTabLoadInProgress = false;
        mAltTabCommitPending = false;
        mAltTabPendingOffset = 0;
        mAltTabSelectedIndex = -1;
        mAltTabTasks = Collections.emptyList();
    }

    private void openTaskFullscreen(final AppItem app,
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

    private void closeTask(final AppItem app, final TaskRepository.TaskEntry task) {
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

    private void toggleDesktopWorkspace() {
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

    private void captureInteractionStackForPanel() {
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
            final AppItem app = findApp(apps, packageName);
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
        if (mStartMenuContent == null) {
            return;
        }
        mStartMenuContent.removeAllViews();
        mStartMenuBody = null;

        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.addView(createMenuTab(R.string.section_floating, MENU_FLOATING),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final LinearLayout.LayoutParams fullscreenTabParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        fullscreenTabParams.setMargins(dp(8), 0, dp(8), 0);
        tabs.addView(createMenuTab(R.string.section_fullscreen, MENU_FULLSCREEN),
                fullscreenTabParams);
        final LinearLayout.LayoutParams toolsTabParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        toolsTabParams.setMargins(0, 0, dp(8), 0);
        tabs.addView(createMenuTab(R.string.section_tools, MENU_TOOLS), toolsTabParams);
        tabs.addView(createMenuTab(R.string.section_pinned, MENU_PINNED),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mStartMenuContent.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (mMenuMode == MENU_TOOLS) {
            addStartMenuTools();
            return;
        }

        if (mStartSearch != null) {
            final LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(0, dp(10), 0, 0);
            mStartMenuContent.addView(mStartSearch, searchParams);
        }

        mStartMenuBody = new LinearLayout(this);
        mStartMenuBody.setOrientation(LinearLayout.VERTICAL);
        mStartMenuContent.addView(mStartMenuBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        renderStartMenuBody();
    }

    private void renderStartMenuBody() {
        if (mStartMenuBody == null) {
            return;
        }
        mStartMenuBody.removeAllViews();

        if (!mSearchQuery.trim().isEmpty()) {
            renderSearchResults();
            return;
        }

        final List<AppItem> menuApps = getMenuApps();
        final int pageSize = getMenuPageSize();
        final int pageCount = Math.max(1, (menuApps.size() + pageSize - 1) / pageSize);
        if (mMenuPage >= pageCount) {
            mMenuPage = pageCount - 1;
        }
        if (mMenuPage < 0) {
            mMenuPage = 0;
        }

        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(getMenuColumnCount());
        final int start = mMenuPage * pageSize;
        final int end = Math.min(menuApps.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            grid.addView(createStartMenuAppTile(menuApps.get(i), false),
                    createMenuTileParams());
        }
        final LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        gridParams.setMargins(0, dp(12), 0, dp(8));
        mStartMenuBody.addView(grid, gridParams);
        addMenuPager(pageCount);
    }

    private Button createMenuTab(final int textResId, final int mode) {
        final Button button = createActionButton(textResId,
                mMenuMode == mode ? COLOR_CYAN : COLOR_PANEL_ALT);
        button.setTextSize(12);
        button.setOnClickListener(view -> {
            mMenuMode = mode;
            mMenuPage = 0;
            if (mode == MENU_TOOLS) {
                mSearchQuery = "";
                if (mStartSearch != null && mStartSearch.length() > 0) {
                    mStartSearch.setText("");
                }
            }
            if (!mStartMenuFocusable && mode != MENU_TOOLS) {
                mStartMenu.post(() -> setStartMenuVisible(true, true));
                return;
            }
            renderStartMenuContent();
        });
        return button;
    }

    private void addStartMenuTools() {
        final LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(0, dp(14), 0, 0);
        populateToolsControls(tools, dp(10));

        mStartMenuContent.addView(tools, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private View createStartMenuAppTile(final AppItem app, final boolean selected) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(5), dp(6), dp(5));
        tile.setBackground(rounded(COLOR_PANEL_ALT, dp(12), selected
                ? COLOR_AMBER : (app.canFloat ? COLOR_CYAN : COLOR_PANEL_ALT)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            hideAllPanels();
            if (mMenuMode == MENU_FULLSCREEN) {
                launchFullscreen(app);
            } else {
                launchDefault(app);
            }
        });
        registerContextTarget(tile, app, null);

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

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
        labelParams.setMargins(0, dp(4), 0, 0);
        tile.addView(label, labelParams);

        return tile;
    }

    private GridLayout.LayoutParams createMenuTileParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(104);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private void addMenuPager(final int pageCount) {
        final LinearLayout pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);

        final Button previous = createActionButton(R.string.action_previous, COLOR_PANEL_ALT);
        previous.setEnabled(mMenuPage > 0);
        previous.setOnClickListener(view -> {
            if (mMenuPage > 0) {
                mMenuPage--;
                renderStartMenuBody();
            }
        });
        pager.addView(previous, new LinearLayout.LayoutParams(dp(108),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView page = new TextView(this);
        page.setText(getString(R.string.page_status,
                Integer.valueOf(mMenuPage + 1), Integer.valueOf(pageCount)));
        page.setTextColor(COLOR_MUTED);
        page.setTextSize(13);
        page.setGravity(Gravity.CENTER);
        pager.addView(page, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button next = createActionButton(R.string.action_next, COLOR_PANEL_ALT);
        next.setEnabled(mMenuPage + 1 < pageCount);
        next.setOnClickListener(view -> {
            if (mMenuPage + 1 < pageCount) {
                mMenuPage++;
                renderStartMenuBody();
            }
        });
        pager.addView(next, new LinearLayout.LayoutParams(dp(108),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mStartMenuBody.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private List<AppItem> getMenuApps() {
        final List<AppItem> result = new ArrayList<>();
        final Set<String> pinnedPackages = getPinnedPackages();
        for (final AppItem app : mLastApps) {
            if (mMenuMode == MENU_FLOATING && app.canFloat) {
                result.add(app);
            } else if (mMenuMode == MENU_FULLSCREEN) {
                result.add(app);
            } else if (mMenuMode == MENU_PINNED && pinnedPackages.contains(app.packageName)) {
                result.add(app);
            }
        }
        return result;
    }

    private List<AppItem> getSearchApps() {
        final String query = mSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() == 0) {
            return Collections.emptyList();
        }
        final List<AppItem> result = new ArrayList<>();
        for (final AppItem app : mLastApps) {
            if (app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                result.add(app);
            }
        }
        return result;
    }

    private void renderSearchResults() {
        final List<AppItem> matches = getSearchApps();
        if (matches.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.search_no_results);
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mStartMenuBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        final int visibleCount = Math.min(matches.size(), getMenuPageSize());
        if (mSearchSelection >= visibleCount) {
            mSearchSelection = visibleCount - 1;
        }
        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(getMenuColumnCount());
        final int end = visibleCount;
        for (int i = 0; i < end; i++) {
            grid.addView(createStartMenuAppTile(matches.get(i), i == mSearchSelection),
                    createMenuTileParams());
        }
        mStartMenuBody.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean handleSearchKey(final int keyCode, final KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        final List<AppItem> matches = getSearchApps();
        final int visibleCount = Math.min(matches.size(), getMenuPageSize());
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !matches.isEmpty()) {
            mSearchSelection = Math.min(visibleCount - 1, mSearchSelection + 1);
            renderStartMenuBody();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !matches.isEmpty()) {
            mSearchSelection = Math.max(0, mSearchSelection - 1);
            renderStartMenuBody();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && !matches.isEmpty()) {
            final AppItem app = matches.get(Math.min(mSearchSelection, matches.size() - 1));
            hideAllPanels();
            launchDefault(app);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hideAllPanels();
            return true;
        }
        return false;
    }

    private int getMenuColumnCount() {
        final int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 1100) {
            return 4;
        }
        return 3;
    }

    private int getMenuPageSize() {
        return getMenuColumnCount() * getMenuRowCount();
    }

    private int getMenuRowCount() {
        final int heightDp = getResources().getConfiguration().screenHeightDp;
        if (heightDp < 480) {
            return 1;
        }
        if (heightDp < 650) {
            return 2;
        }
        return 3;
    }

    private void toggleStartMenu() {
        if (mOverlayPanelController != null
                && mOverlayPanelController.isVisible(mStartMenu)) {
            setStartMenuVisible(false);
            return;
        }
        setStartMenuVisible(true, true);
    }

    private void setStartMenuVisible(final boolean visible) {
        setStartMenuVisible(visible, true);
    }

    private void setStartMenuVisible(final boolean visible, final boolean focusable) {
        if (mOverlayPanelController == null || mStartMenu == null) {
            return;
        }
        if (!visible) {
            mOverlayPanelController.hide(mStartMenu);
            return;
        }
        final boolean windowFocusable = focusable;
        mStartMenuFocusable = windowFocusable;
        renderStartMenuContent();
        final int width = getStartMenuWidth();
        final int height = getStartMenuHeight();
        final int left = desktopDp(16, 6);
        final int top = Math.max(0, getDesktopAreaHeight()
                - getTaskbarHeight() - height);
        if (!mOverlayPanelController.show(mStartMenu, left, top, width, height,
                windowFocusable, "MagicDesk Start")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
            return;
        }
        if (windowFocusable && mMenuMode != MENU_TOOLS && mStartSearch != null) {
            mStartSearch.post(() -> {
                mStartSearch.requestFocus();
                mStartSearch.setSelection(mStartSearch.length());
            });
        }
    }

    private void toggleToolsMenu() {
        if (mOverlayPanelController != null
                && mOverlayPanelController.isVisible(mStartMenu)
                && mMenuMode == MENU_TOOLS) {
            setStartMenuVisible(false);
            return;
        }
        showStartSection(MENU_TOOLS, false);
    }

    private void toggleShortcutHelp() {
        if (mOverlayPanelController == null || mShortcutHelp == null) {
            return;
        }
        if (mOverlayPanelController.isVisible(mShortcutHelp)) {
            mOverlayPanelController.hide(mShortcutHelp);
            return;
        }
        final int width = Math.min(dp(520), getDesktopAreaWidth() - dp(24));
        final int height = Math.min(dp(560),
                getDesktopAreaHeight() - getTaskbarHeight() - dp(24));
        final int left = Math.max(0, (getDesktopAreaWidth() - width) / 2);
        final int top = Math.max(0,
                (getDesktopAreaHeight() - getTaskbarHeight() - height) / 2);
        if (!mOverlayPanelController.show(mShortcutHelp, left, top, width, height,
                false, "MagicDesk keyboard shortcuts")) {
            setErrorStatus("OVERLAY-001",
                    getString(R.string.status_overlay_panel_unavailable));
        }
    }

    private void hideAllPanels() {
        if (mOverlayPanelController != null) {
            mOverlayPanelController.hideAll();
        }
    }

    private int getDesktopAreaWidth() {
        return mDesktopRoot != null && mDesktopRoot.getWidth() > 0
                ? mDesktopRoot.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
    }

    private int getDesktopAreaHeight() {
        return mDesktopRoot != null && mDesktopRoot.getHeight() > 0
                ? mDesktopRoot.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
    }

    private int getTaskbarHeight() {
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
                scheduleDisplayProfileRefresh();
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

    private void registerProfileDisplayListener() {
        final DisplayManager displayManager = getSystemService(DisplayManager.class);
        if (displayManager == null) {
            return;
        }
        mProfileDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
                scheduleDisplayProfileRefresh();
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
                scheduleDisplayProfileRefresh();
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                scheduleDisplayProfileRefresh();
            }
        };
        displayManager.registerDisplayListener(mProfileDisplayListener, mMainHandler);
    }

    private void scheduleDisplayProfileRefresh() {
        mMainHandler.removeCallbacks(mDisplayProfileRefresh);
        mMainHandler.post(mDisplayProfileRefresh);
        mMainHandler.postDelayed(mDisplayProfileRefresh, 1_000L);
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
        for (final String packageName : FAVORITE_PACKAGES) {
            final AppItem app = findApp(apps, packageName);
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

    private void populateToolsControls(final LinearLayout parent, final int spacing) {
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
        if (override == LAYOUT_DESKTOP) {
            return true;
        }
        if (override == LAYOUT_PHONE) {
            return false;
        }
        return getResources().getConfiguration().screenWidthDp >= 700;
    }

    private int getStartMenuWidth() {
        final int width = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(560), Math.max(dp(280), width - dp(32)));
    }

    private int getStartMenuHeight() {
        final int height = getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(620), Math.max(dp(360), height - dp(124)));
    }

    private int getContextMenuWidth() {
        final int width = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(310), Math.max(dp(250), width - dp(24)));
    }

    private int getLayoutMode() {
        return getWorkspaceProfile().layoutMode;
    }

    private void setLayoutMode(final int mode) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.layoutMode = mode;
        saveWorkspaceProfile();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_LAYOUT_MODE, mode)
                .apply();
        recreate();
    }

    private int getPreferredDesktopDpi() {
        return getWorkspaceProfile().dpi;
    }

    private void setPreferredDesktopDpi(final int dpi) {
        final WorkspaceProfileStore.Profile profile = getWorkspaceProfile();
        profile.dpi = dpi;
        saveWorkspaceProfile();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_DESKTOP_DPI, dpi)
                .apply();
    }

    private WorkspaceProfileStore.Profile getWorkspaceProfile() {
        if (mWorkspaceProfile != null) {
            return mWorkspaceProfile;
        }
        final String displayKey = resolveMonitorProfileKey();
        final String monitorKey = WorkspaceProfileStore.resolveMonitorAlias(
                this, displayKey);
        final SharedPreferences legacy = getSharedPreferences(PREFS, MODE_PRIVATE);
        final Set<String> defaultPackages = new LinkedHashSet<>();
        Collections.addAll(defaultPackages, FAVORITE_PACKAGES);
        mProfileDisplayKey = displayKey;
        mMonitorProfileKey = monitorKey;
        mWorkspaceProfile = WorkspaceProfileStore.load(this, monitorKey,
                legacy.getInt(PREF_DESKTOP_DPI, DEFAULT_DESKTOP_DPI),
                legacy.getInt(PREF_LAYOUT_MODE, LAYOUT_AUTO),
                getLegacyPinnedPackages(), defaultPackages);
        mWorkspaceRestoreAttempted = false;
        mWorkspaceBoundsRestorePending = false;
        mLoadedFolderUri = null;
        mDesktopFiles = Collections.emptyList();
        return mWorkspaceProfile;
    }

    private void refreshWorkspaceProfileForDisplay() {
        final String displayKey = resolveMonitorProfileKey();
        if (mWorkspaceProfile != null && displayKey.equals(mProfileDisplayKey)) {
            return;
        }
        mWorkspaceProfile = null;
        mProfileDisplayKey = displayKey;
        mMonitorProfileKey = null;
        mMonitorIdentityRequested = false;
        getWorkspaceProfile();
    }

    private void saveWorkspaceProfile() {
        WorkspaceProfileStore.save(this, getWorkspaceProfile());
    }

    private String resolveMonitorProfileKey() {
        final Display profileDisplay = getProfileDisplay();
        if (profileDisplay == null) {
            return "display:default";
        }
        final Display.Mode mode = profileDisplay.getMode();
        final String resolution = mode == null ? "unknown"
                : mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight();
        return "display-" + profileDisplay.getDisplayId() + "|" + profileDisplay.getName()
                + "|" + resolution;
    }

    private Display getProfileDisplay() {
        final DisplayManager manager = getSystemService(DisplayManager.class);
        Display current = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? getDisplay() : getWindowManager().getDefaultDisplay();
        if (manager == null) {
            return current;
        }
        final boolean currentIsVirtual = current != null
                && current.getName().contains("NubiaAppMirror");
        if (current != null && !currentIsVirtual) {
            return current;
        }
        final Display[] presentations = manager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (final Display display : presentations) {
            if (display != null && !display.getName().contains("NubiaAppMirror")
                    && display.getState() != Display.STATE_OFF) {
                return display;
            }
        }
        return current;
    }

    private String getMonitorProfileLabel() {
        final Display display = getProfileDisplay();
        return display == null ? getString(R.string.profile_default) : display.getName();
    }

    private void resolveMonitorIdentityAsync() {
        if (mMonitorIdentityRequested) {
            return;
        }
        final Display profileDisplay = getProfileDisplay();
        if (profileDisplay == null
                || profileDisplay.getDisplayId() == Display.DEFAULT_DISPLAY
                || profileDisplay.getName().contains("NubiaAppMirror")) {
            return;
        }
        mMonitorIdentityRequested = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String output;
                try {
                    output = runRootCommand("for f in /sys/class/drm/card*-DP-*/edid; do "
                            + "/system/bin/sha256sum \"$f\" && exit; done").trim();
                } catch (IOException e) {
                    Log.w(TAG, "Cannot resolve monitor EDID", e);
                    return;
                }
                final String[] fields = output.split("\\s+");
                if (fields.length == 0 || !fields[0].matches("[0-9a-fA-F]{64}")) {
                    Log.w(TAG, "No usable monitor EDID hash: " + output);
                    return;
                }
                final String monitorKey = "edid:" + fields[0].toLowerCase(Locale.ROOT);
                Log.i(TAG, "Resolved monitor profile " + monitorKey);
                runOnUiThread(() -> applyResolvedMonitorProfile(monitorKey));
            }
        }, "MagicDeskMonitorIdentity").start();
    }

    private void applyResolvedMonitorProfile(final String monitorKey) {
        if (monitorKey == null || monitorKey.equals(mMonitorProfileKey)
                || isFinishing() || isDestroyed()) {
            return;
        }
        final WorkspaceProfileStore.Profile previous = getWorkspaceProfile();
        final boolean existed = WorkspaceProfileStore.exists(this, monitorKey);
        final WorkspaceProfileStore.Profile resolved = WorkspaceProfileStore.load(
                this, monitorKey, previous.dpi, previous.layoutMode,
                previous.taskbarPackages, previous.desktopPackages);
        if (!existed) {
            resolved.folderUri = previous.folderUri;
            resolved.workspacePackage = previous.workspacePackage;
            resolved.workspaceBounds = new Rect(previous.workspaceBounds);
            WorkspaceProfileStore.save(this, resolved);
        }
        final int previousDpi = previous.dpi;
        WorkspaceProfileStore.saveMonitorAlias(this, mProfileDisplayKey, monitorKey);
        mMonitorProfileKey = monitorKey;
        mWorkspaceProfile = resolved;
        Log.i(TAG, "Activated monitor profile " + monitorKey);
        mWorkspaceRestoreAttempted = false;
        mWorkspaceBoundsRestorePending = false;
        mLoadedFolderUri = null;
        mDesktopFiles = Collections.emptyList();
        renderApps();
        refreshDesktopFolder(true);
        updateConsoleControls();
        if (resolved.dpi != previousDpi) {
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

    private List<AppItem> loadLauncherApps() {
        final boolean universalFreeform = isUniversalFreeformEnabled();
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        final PackageManager packageManager = getPackageManager();
        final List<ResolveInfo> activities =
                packageManager.queryIntentActivities(launcherIntent, 0);
        final List<AppItem> result = new ArrayList<>();
        final Set<String> addedPackages = new HashSet<>();
        final String ownPackage = getPackageName();

        for (final ResolveInfo resolveInfo : activities) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) {
                continue;
            }
            final String packageName = resolveInfo.activityInfo.packageName;
            if (packageName == null
                    || ownPackage.equals(packageName)
                    || addedPackages.contains(packageName)) {
                continue;
            }
            final CharSequence labelChars = resolveInfo.loadLabel(packageManager);
            final String label = labelChars == null || labelChars.length() == 0
                    ? packageName : labelChars.toString();
            final Drawable icon = resolveInfo.loadIcon(packageManager);
            final ApplicationInfo applicationInfo =
                    resolveInfo.activityInfo.applicationInfo;
            result.add(new AppItem(label, packageName, universalFreeform,
                    getFullscreenPreference(resolveInfo.activityInfo, applicationInfo),
                    icon));
            addedPackages.add(packageName);
        }

        Collections.sort(result, new Comparator<AppItem>() {
            @Override
            public int compare(final AppItem left, final AppItem right) {
                final int labelCompare = left.label.compareToIgnoreCase(right.label);
                if (labelCompare != 0) {
                    return labelCompare;
                }
                return left.packageName.compareTo(right.packageName);
            }
        });
        return result;
    }

    private AppItem findApp(final List<AppItem> apps, final String packageName) {
        for (final AppItem app : apps) {
            if (app.packageName.equals(packageName)) {
                return app;
            }
        }
        return null;
    }

    private void launchDefault(final AppItem app) {
        Log.i(TAG, "launch default package=" + app.packageName
                + " canFloat=" + app.canFloat
                + " fullscreenReason=" + app.fullscreenReason
                + " display=" + getCurrentDisplayId());
        if (app.canFloat && FULLSCREEN_REASON_NONE.equals(app.fullscreenReason)) {
            launchFloating(app);
        } else {
            launchFullscreen(app);
        }
    }

    private static ActivityInfo resolveLauncherActivityInfo(
            final PackageManager packageManager, final String packageName) {
        final Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            return null;
        }
        try {
            return packageManager.getActivityInfo(launchIntent.getComponent(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String getFullscreenPreference(final ActivityInfo activityInfo,
            final ApplicationInfo applicationInfo) {
        if (activityInfo != null
                && (activityInfo.flags & ActivityInfo.FLAG_IMMERSIVE) != 0) {
            return FULLSCREEN_REASON_IMMERSIVE;
        }
        final Integer resizeMode = getResizeMode(activityInfo);
        if (resizeMode != null
                && resizeMode.intValue() == RESIZE_MODE_UNRESIZEABLE) {
            return FULLSCREEN_REASON_UNRESIZEABLE;
        }
        if (applicationInfo != null
                && applicationInfo.category == ApplicationInfo.CATEGORY_GAME) {
            return FULLSCREEN_REASON_GAME;
        }
        return FULLSCREEN_REASON_NONE;
    }

    private static Integer getResizeMode(final ActivityInfo activityInfo) {
        if (activityInfo == null) {
            return null;
        }
        final Field field = resolveResizeModeField();
        if (field == null) {
            return null;
        }
        try {
            return Integer.valueOf(field.getInt(activityInfo));
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static synchronized Field resolveResizeModeField() {
        if (sResizeModeFieldResolved) {
            return sResizeModeField;
        }
        sResizeModeFieldResolved = true;
        try {
            final Field field = ActivityInfo.class.getDeclaredField("resizeMode");
            field.setAccessible(true);
            sResizeModeField = field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "ActivityInfo.resizeMode unavailable; using public launch hints");
        }
        return sResizeModeField;
    }

    private void launchFloating(final AppItem app) {
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

    private void launchFullscreen(final AppItem app) {
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

    private void restartConsoleShortcuts() {
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
            setPreferredDesktopDpi(DEFAULT_DESKTOP_DPI);
            command = WM + " density " + DEFAULT_DESKTOP_DPI + " -d " + displayId;
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

    private void showLaunchFailure(final RuntimeException e) {
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

    private int getCurrentDisplayId() {
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
        final TextView title = new TextView(this);
        title.setText(titleResId);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    private Button createActionButton(final int textResId, final int accentColor) {
        return createActionButton(getString(textResId), accentColor);
    }

    private Button createActionButton(final String text, final int accentColor) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackground(rounded(COLOR_PANEL_ALT, dp(10), accentColor));
        return button;
    }

    private Button createSmallButton(final int textResId, final int accentColor) {
        return createSmallButton(getString(textResId), accentColor);
    }

    private Button createSmallButton(final String text, final int accentColor) {
        final Button button = createActionButton(text, accentColor);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        return button;
    }

    private ImageButton createTaskbarIconButton(final int drawableResId,
            final int descriptionResId) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(drawableResId);
        button.setColorFilter(COLOR_TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(rounded(COLOR_PANEL_ALT, desktopDp(8, 6), COLOR_PANEL_ALT));
        button.setContentDescription(getString(descriptionResId));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(getString(descriptionResId));
        }
        return button;
    }

    private GradientDrawable rounded(final int color, final int radius, final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void setStatus(final int stringResId) {
        setStatus(getString(stringResId));
    }

    private void setErrorStatus(final String code, final String message) {
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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int desktopDp(final int normalValue, final int compactValue) {
        return dp(isCompactDesktopPreview() ? compactValue : normalValue);
    }

    private boolean isCompactDesktopPreview() {
        return mDesktopMode && getResources().getConfiguration().screenWidthDp < 700;
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class AppItem {
        final String label;
        final String packageName;
        final boolean canFloat;
        final String fullscreenReason;
        final Drawable icon;

        AppItem(final String label, final String packageName,
                final boolean canFloat, final String fullscreenReason,
                final Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.canFloat = canFloat;
            this.fullscreenReason = fullscreenReason;
            this.icon = icon;
        }
    }

    private static final class DesktopFile {
        final Uri uri;
        final String name;
        final String mimeType;
        final long modified;
        final boolean directory;

        DesktopFile(final Uri uri, final String name, final String mimeType,
                final long modified, final boolean directory) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.modified = modified;
            this.directory = directory;
        }
    }

    private static final class ContextTarget {
        final AppItem app;
        final TaskRepository.TaskEntry task;

        ContextTarget(final AppItem app, final TaskRepository.TaskEntry task) {
            this.app = app;
            this.task = task;
        }
    }

}
