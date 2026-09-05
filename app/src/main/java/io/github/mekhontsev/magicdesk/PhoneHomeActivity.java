package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone-side HOME surface used while an external desktop owns HOME. */
public final class PhoneHomeActivity extends Activity implements StartMenuContent.Host {
    static final String EXTRA_SHOW_RECENT =
            BuildConfig.APPLICATION_ID + ".extra.SHOW_PHONE_RECENT";

    private FrameLayout mRoot;
    private ImageButton mCloseDesktop;
    private boolean mClosing;
    private boolean mLaunching;
    private StartMenuContent mStart;
    private final DesktopAutomationUiRegistry mAutomation = new DesktopAutomationUiRegistry();
    private List<AppItem> mApps = Collections.emptyList();
    private List<TaskRepository.TaskEntry> mRecentTasks = Collections.emptyList();
    private String mRecentError = "";
    private boolean mRecentLoading;
    private int mRecentGeneration;
    private LauncherApps mLauncherApps;
    private boolean mStarted;
    private boolean mAppsDirty = true;
    private boolean mAppsLoading;
    private final ExecutorService mAppLoader = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "MagicDeskPhoneApps"));
    private final LauncherApps.Callback mPackageCallback = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String name, UserHandle user) { invalidateApps(); }
        @Override public void onPackageRemoved(String name, UserHandle user) { invalidateApps(); }
        @Override public void onPackageChanged(String name, UserHandle user) { invalidateApps(); }
        @Override public void onPackagesAvailable(String[] names, UserHandle user, boolean replacing) {
            invalidateApps();
        }
        @Override public void onPackagesUnavailable(String[] names, UserHandle user, boolean replacing) {
            invalidateApps();
        }
    };
    private final OnBackInvokedCallback mBackCallback = () -> {
        // Back clears navigation within Start but never finishes HOME.
        if (mStart != null) {
            mStart.showSection(StartMenuContent.MENU_APPS);
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DesktopHomeStartupGuard.shouldDiscardStaleHomeLaunch(
                getIntent())) {
            finishAndRemoveTask();
            return;
        }
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);

        mRoot = new FrameLayout(this);
        setContentView(
                mRoot,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        showHome();
        applyHomeIntent(true);
        mLauncherApps = getSystemService(LauncherApps.class);
        if (mLauncherApps != null) {
            mLauncherApps.registerCallback(
                    mPackageCallback, new Handler(Looper.getMainLooper()));
        }
        MagicDeskRuntime.start(this);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        setIntent(intent);
        applyHomeIntent(false);
        refreshCloseAction();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        if (mStart != null) {
            DesktopRuntimeBridge.registerPhoneHome(this);
            loadApps();
        }
    }

    @Override
    protected void onStop() {
        mStarted = false;
        ++mRecentGeneration;
        mRecentLoading = false;
        DesktopRuntimeBridge.unregisterPhoneHome(this);
        if (mStart != null) {
            mStart.pause();
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        refreshCloseAction();
        if (mStart != null) {
            mStart.prepare(true);
            loadRecents();
        }
    }

    @Override
    protected void onDestroy() {
        DesktopRuntimeBridge.unregisterPhoneHome(this);
        if (mLauncherApps != null) {
            mLauncherApps.unregisterCallback(mPackageCallback);
        }
        if (mStart != null) {
            mStart.release();
        }
        mAppLoader.shutdownNow();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mBackCallback);
        super.onDestroy();
    }

    private void showHome() {
        mRoot.removeAllViews();
        mRoot.setBackgroundColor(Color.TRANSPARENT);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
        SystemBarInsets.addToPadding(content, true);

        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mStart = new StartMenuContent(this, ui, StartMenuScope.PHONE, this);
        content.addView(mStart.create(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(14), 0, dp(14), dp(12));
        addAction(actions, ui, android.R.drawable.ic_menu_manage,
                R.string.action_open_control_panel, "phone.controls",
                () -> PhoneControlPanelLauncher.open(this));
        addAction(actions, ui, R.drawable.ic_touchpad,
                R.string.action_open_touchpad, "phone.touchpad",
                DesktopOperations::openTouchpad);
        mCloseDesktop = ui.menuIconButton(R.drawable.ic_close, R.string.action_close_desktop);
        actions.addView(mCloseDesktop, actionParams());
        mCloseDesktop.setOnClickListener(view -> closeDesktop());
        mAutomation.register(mCloseDesktop, "phone.close_desktop", "button",
                getString(R.string.action_close_desktop));
        content.addView(actions);

        mRoot.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refreshCloseAction();
    }

    private void addAction(final LinearLayout parent, final DesktopUiFactory ui,
            final int icon, final int label, final String id, final Runnable action) {
        final ImageButton button = ui.menuIconButton(icon, label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, actionParams());
        mAutomation.register(button, id, "button", getString(label));
    }

    private LinearLayout.LayoutParams actionParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void invalidateApps() {
        mAppsDirty = true;
        loadApps();
    }

    private void loadApps() {
        if (!mStarted || !mAppsDirty || mAppsLoading || isFinishing()) {
            return;
        }
        mAppsDirty = false;
        mAppsLoading = true;
        // Catalog work runs only on first display or package changes, never on a timer.
        mAppLoader.execute(() -> {
            final List<AppItem> apps;
            try {
                apps = new LauncherAppRepository(this).load(false);
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    mAppsLoading = false;
                    mAppsDirty = true;
                    if (!isDestroyed() && !isFinishing()) {
                        CompatibilityDiagnostics.record("PHONE-APPS-001",
                                "Could not load phone applications", "", error);
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                mAppsLoading = false;
                if (isDestroyed() || isFinishing()) {
                    return;
                }
                mApps = apps;
                if (mStarted) {
                    mStart.prepare(true);
                }
                loadApps();
            });
        });
    }

    boolean isAutomationAvailable() {
        return mStarted && !isFinishing() && !isDestroyed() && hasActivePhoneHomeLease();
    }

    @Override public List<AppItem> apps() { return mApps; }
    @Override public List<String> recentApps() {
        final DesktopHomeRoleLease.State lease = activeLease();
        return lease == null ? Collections.emptyList()
                : PhoneRecentApps.select(mRecentTasks, mApps, lease.previousHome.packageName);
    }
    @Override public String recentAppsError() { return mRecentError; }
    @Override public void onSectionShown(final int section) {
        if (section == StartMenuContent.MENU_RECENT) {
            loadRecents();
        }
    }
    @Override public DesktopAutomationUiRegistry automation() { return mAutomation; }
    @Override public void dismiss() { mStart.showSection(StartMenuContent.MENU_APPS); }

    @Override
    public void open(final StartSearchController.Result result) {
        final DesktopHomeRoleLease.State lease = activeLease();
        if (result.app == null || lease == null || !hasActivePhoneHomeLease()
                || mClosing || mLaunching) {
            return;
        }
        mLaunching = true;
        PhoneTouchpadController.release(lease.target().displayId);
        PhoneAppLauncher.launch(this, result.app, lease.target().displayId,
                () -> {
                    final DesktopHomeRoleLease.State current = activeLease();
                    final boolean valid = !isFinishing() && !isDestroyed() && !mClosing
                            && current != null && current.matches(lease.target());
                    if (!valid) {
                        mLaunching = false;
                    }
                    return valid;
                },
                () -> mLaunching = false, error -> {
                    mLaunching = false;
                    CompatibilityDiagnostics.record("PHONE-APP-LAUNCH-001",
                            "Could not launch phone application",
                            result.app.launchTarget.stableKey(), error);
                    Toast.makeText(this, getString(R.string.status_desktop_launch_unavailable,
                            result.app.label), Toast.LENGTH_LONG).show();
                });
    }

    private void applyHomeIntent(final boolean initialLaunch) {
        final Intent intent = getIntent();
        final boolean showRecent = intent != null
                && intent.getBooleanExtra(EXTRA_SHOW_RECENT, false);
        // Initial session HOME permits automatic touchpad startup. Explicit
        // HOME/Recents navigation, including a cold Recents launch, retires it.
        if (!initialLaunch || showRecent) {
            final DesktopHomeRoleLease.State lease = activeLease();
            if (lease != null) {
                PhoneTouchpadController.release(lease.target().displayId);
            }
        }
        if (showRecent) {
            intent.removeExtra(EXTRA_SHOW_RECENT);
            mStart.showSection(StartMenuContent.MENU_RECENT);
        }
    }

    private void loadRecents() {
        final DesktopHomeRoleLease.State lease = activeLease();
        if (!mStarted || mRecentLoading || lease == null || !hasActivePhoneHomeLease()) {
            return;
        }
        final int generation = ++mRecentGeneration;
        mRecentLoading = true;
        // One snapshot per HOME resume or explicit Recent selection also sees
        // phone applications opened from notifications and other launchers.
        TaskRepository.load(android.view.Display.DEFAULT_DISPLAY,
                snapshot -> runOnUiThread(() -> {
                    if (generation != mRecentGeneration || !mStarted
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    mRecentLoading = false;
                    final DesktopHomeRoleLease.State current = activeLease();
                    if (current == null || !current.matches(lease.target())
                            || !hasActivePhoneHomeLease()) {
                        return;
                    }
                    mRecentTasks = snapshot.available ? snapshot.tasks : Collections.emptyList();
                    mRecentError = snapshot.available ? ""
                            : getString(R.string.phone_recent_unavailable, snapshot.error);
                    mStart.prepare(true);
                }));
    }

    private void closeDesktop() {
        if (mClosing) {
            return;
        }
        final DesktopHomeRoleLease.State lease = activeLease();
        if (lease == null) {
            refreshCloseAction();
            return;
        }
        mClosing = true;
        mCloseDesktop.setEnabled(false);
        mCloseDesktop.setContentDescription(getString(R.string.status_desktop_closing));
        DesktopOperations.closeDesktop(
                lease.target(),
                DesktopCloseMode.HOME,
                success -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (!hasActivePhoneHomeLease()) {
                        finishAndRemoveTask();
                        return;
                    }
                    mClosing = false;
                    mCloseDesktop.setContentDescription(getString(R.string.action_close_desktop));
                    refreshCloseAction();
                }));
    }

    private void refreshCloseAction() {
        if (mCloseDesktop == null || mClosing) {
            return;
        }
        mCloseDesktop.setEnabled(hasActivePhoneHomeLease());
    }

    private static boolean hasActivePhoneHomeLease() {
        return DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.PHONE);
    }

    private static DesktopHomeRoleLease.State activeLease() {
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        return lease != null && lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                ? lease : null;
    }

    private int dp(final int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
