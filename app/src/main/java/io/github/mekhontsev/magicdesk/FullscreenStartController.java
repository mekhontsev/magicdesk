package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.graphics.Color;
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

/** Fullscreen Start, hosted either by HOME or by the independent Apps Activity. */
final class FullscreenStartController implements StartMenuContent.Host {
    private final Activity mActivity;
    private final int mDisplayId;
    private final boolean mHome;

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
            runnable -> new Thread(runnable, "MagicDeskStartApps"));
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
    private final OnBackInvokedCallback mBackCallback = this::back;

    private void back() {
        // Back clears navigation within Start but never finishes HOME.
        if (!mHome) {
            mActivity.finish();
        } else if (mStart != null) {
            mStart.showSection(StartMenuContent.MENU_APPS);
        }
    }

    FullscreenStartController(final Activity activity, final boolean home) {
        mActivity = activity;
        mHome = home;
        mDisplayId = activity.getDisplay().getDisplayId();
        if (home) { activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER); }
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
        mRoot = new FrameLayout(activity);
        activity.setContentView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showHome();
        if (home) { applyHomeIntent(true); }
        mLauncherApps = activity.getSystemService(LauncherApps.class);
        if (mLauncherApps != null) {
            mLauncherApps.registerCallback(mPackageCallback, new Handler(Looper.getMainLooper()));
        }
        MagicDeskRuntime.start(activity);
    }

    static boolean canHost(final Activity activity) {
        final DesktopHomeRoleLease.State lease = activeLease();
        return lease != null && activity.getDisplay() != null
                && lease.targetForDisplay(activity.getDisplay().getDisplayId()) == null;
    }

    static boolean isReleasing() {
        final DesktopHomeRoleLease.State lease = DesktopHomeRoleLease.snapshot();
        return lease != null && lease.phase == DesktopHomeRoleLease.Phase.RELEASING;
    }

    void newIntent(final Intent intent) {
        mActivity.setIntent(intent);
        applyHomeIntent(false);
        refreshCloseAction();
    }

    void start() {
        mStarted = true;
        DesktopRuntimeBridge.registerUiWindow(mActivity.getWindow(), mAutomation);
        loadApps();
    }

    void stop() {
        mStarted = false;
        ++mRecentGeneration;
        mRecentLoading = false;
        DesktopRuntimeBridge.unregisterUiWindow(mActivity.getWindow());
        mStart.pause();
    }

    void resume() {
        refreshCloseAction();
        mStart.prepare(true);
        loadRecents();
    }

    void destroy() {
        DesktopRuntimeBridge.unregisterUiWindow(mActivity.getWindow());
        if (mLauncherApps != null) { mLauncherApps.unregisterCallback(mPackageCallback); }
        mStart.release();
        mAppLoader.shutdownNow();
        mActivity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
    }

    private void showHome() {
        mRoot.removeAllViews();
        mRoot.setBackgroundColor(Color.TRANSPARENT);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
        SystemBarInsets.addToPadding(content, true);

        final DesktopUiFactory ui = new DesktopUiFactory(mActivity);
        mStart = new StartMenuContent(mActivity, ui, StartMenuScope.APPLICATIONS, this);
        content.addView(mStart.create(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(14), 0, dp(14), dp(12));
        addAction(actions, ui, R.drawable.ic_settings,
                R.string.action_open_control_panel, "phone.controls",
                () -> PhoneControlPanelLauncher.open(mActivity));
        addAction(actions, ui, R.drawable.ic_touchpad,
                R.string.action_open_touchpad, "phone.touchpad",
                DesktopOperations::openTouchpad);
        mCloseDesktop = ui.menuIconButton(R.drawable.ic_close, R.string.action_close_desktop);
        actions.addView(mCloseDesktop, actionParams());
        mCloseDesktop.setOnClickListener(view -> closeDesktop());
        mAutomation.register(mCloseDesktop, "phone.close_desktop", "button",
                mActivity.getString(R.string.action_close_desktop));
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
        mAutomation.register(button, id, "button", mActivity.getString(label));
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
        if (!mStarted || !mAppsDirty || mAppsLoading || mActivity.isFinishing()) {
            return;
        }
        mAppsDirty = false;
        mAppsLoading = true;
        // Catalog work runs only on first display or package changes, never on a timer.
        mAppLoader.execute(() -> {
            final List<AppItem> apps;
            try {
                apps = new LauncherAppRepository(mActivity).load(false);
            } catch (RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    mAppsLoading = false;
                    mAppsDirty = true;
                    if (!mActivity.isDestroyed() && !mActivity.isFinishing()) {
                        CompatibilityDiagnostics.record("PHONE-APPS-001",
                                "Could not load phone applications", "", error);
                    }
                });
                return;
            }
            mActivity.runOnUiThread(() -> {
                mAppsLoading = false;
                if (mActivity.isDestroyed() || mActivity.isFinishing()) {
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

    @Override public List<AppItem> apps() { return mApps; }
    @Override public List<AppReference> recentApps() {
        final DesktopHomeRoleLease.State lease = activeLease();
        return lease == null ? Collections.emptyList()
                : HomeRecentApps.select(mRecentTasks, mApps, lease.previousHome.packageName, mDisplayId);
    }
    @Override public int recentSectionLabel() {
        return mHome ? R.string.section_recent : R.string.display_running_apps;
    }
    @Override public List<StartMenuEntry> entries(int section) {
        if (!mHome && section == StartMenuContent.MENU_RECENT) {
            final List<StartMenuEntry> entries = new java.util.ArrayList<>();
            final LauncherAppRepository repository = new LauncherAppRepository(mActivity);
            for (final TaskRepository.TaskEntry task : mRecentTasks) {
                if (!TaskRepository.isTransferable(task)) { continue; }
                final AppItem app = LauncherAppRepository.find(mApps,
                        AppReference.forTask(repository.profile().application(task), task));
                if (app != null) {
                    entries.add(StartMenuEntry.task(app, task, TaskTitle.resolve(mActivity, app, task),
                            mActivity.getString(task.displayId == mStart.destination().displayId()
                                    ? R.string.display_show_task : R.string.display_move_task,
                                    task.displayId, task.taskId)));
                }
            }
            return entries;
        }
        final List<StartMenuEntry> entries = new java.util.ArrayList<>(StartMenuContent.Host.super.entries(section));
        if (section == StartMenuContent.MENU_APPS) {
            entries.add(0, StartMenuEntry.terminals(mActivity.getString(R.string.terminal_sessions)));
        }
        return entries;
    }
    @Override public List<StartMenuEntry> searchEntries(int section) {
        return entries(!mHome ? section : StartMenuContent.MENU_APPS);
    }
    @Override public String recentAppsError() { return mRecentError; }
    @Override public void onSectionShown(final int section) {
        if (section == StartMenuContent.MENU_RECENT) {
            loadRecents();
        }
    }
    @Override public DesktopAutomationUiRegistry automation() { return mAutomation; }
    @Override public void dismiss() {
        if (mHome) { mStart.showSection(StartMenuContent.MENU_APPS); }
        else { mActivity.finish(); }
    }

    @Override public void appContext(android.view.View view, AppItem app) {
        view.setOnContextClickListener(anchor -> anchor.performLongClick());
        view.setOnLongClickListener(anchor -> {
            final android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
            final StartDisplaySelector.Target target = mStart.destination();
            menu.getMenu().add(R.string.action_open).setOnMenuItemClickListener(item -> {
                launch(StartMenuEntry.app(app), target, DesktopLaunchPresentation.automatic()); return true;
            });
            menu.getMenu().add(R.string.action_open_fullscreen).setOnMenuItemClickListener(item -> {
                launch(StartMenuEntry.app(app), target,
                        DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN)); return true;
            });
            menu.getMenu().add(R.string.action_open_floating)
                    .setEnabled(DesktopRuntimeBridge.hasWorkspace(target.displayId()))
                    .setOnMenuItemClickListener(item -> {
                        launch(StartMenuEntry.app(app), target,
                                DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED)); return true;
                    });
            menu.show();
            return true;
        });
    }

    @Override
    public void open(final StartMenuEntry result) {
        launch(result, mStart.destination(), DesktopLaunchPresentation.automatic());
    }

    private void launch(StartMenuEntry result, StartDisplaySelector.Target target,
            DesktopLaunchPresentation presentation) {
        if (!canLaunch() || mLaunching) { return; }
        mLaunching = true;
        if (mHome && mDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            PhoneTouchpadController.release(MagicDeskRuntime.inputDisplayId());
        }
        StartEntryLauncher.open(mActivity, result, target, presentation, this::canLaunch,
                () -> { mLaunching = false; }, error -> {
                    mLaunching = false;
                    CompatibilityDiagnostics.record("START-LAUNCH-001",
                            "Could not launch Start item", result.stableKey(), error);
                    Toast.makeText(mActivity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
                });
    }

    private boolean canLaunch() {
        return !mActivity.isFinishing() && !mActivity.isDestroyed() && !mClosing
                && (!mHome || hasActiveHomeLease());
    }

    private void applyHomeIntent(final boolean initialLaunch) {
        final Intent intent = mActivity.getIntent();
        final boolean showRecent = intent != null
                && intent.getBooleanExtra(PhoneHomeActivity.EXTRA_SHOW_RECENT, false);
        // Initial session HOME permits automatic touchpad startup. Explicit
        // HOME/Recents navigation, including a cold Recents launch, retires it.
        if (!initialLaunch || showRecent) {
            final DesktopHomeRoleLease.State lease = activeLease();
            if (lease != null) {
                if (mDisplayId == android.view.Display.DEFAULT_DISPLAY) {
                    PhoneTouchpadController.release(MagicDeskRuntime.inputDisplayId());
                }
            }
        }
        if (showRecent) {
            intent.removeExtra(PhoneHomeActivity.EXTRA_SHOW_RECENT);
            mStart.showSection(StartMenuContent.MENU_RECENT);
        }
    }

    private void loadRecents() {
        final DesktopHomeRoleLease.State lease = activeLease();
        if (!mStarted || mRecentLoading || (mHome && (lease == null || !hasActiveHomeLease()))) {
            return;
        }
        final int generation = ++mRecentGeneration;
        mRecentLoading = true;
        // One snapshot per HOME resume or explicit Recent selection also sees
        // phone applications opened from notifications and other launchers.
        TaskCommandQueue.execute(() -> {
            final TaskRepository.Snapshot snapshot = mHome
                    ? TaskRepository.loadNow(mDisplayId) : TaskRepository.loadAllNow();
            mActivity.runOnUiThread(() -> {
                    if (generation != mRecentGeneration || !mStarted
                            || mActivity.isFinishing() || mActivity.isDestroyed()) {
                        return;
                    }
                    mRecentLoading = false;
                    if (mHome && !hasActiveHomeLease()) {
                        return;
                    }
                    mRecentTasks = snapshot.available ? snapshot.tasks : Collections.emptyList();
                    mRecentError = snapshot.available ? ""
                            : mActivity.getString(R.string.phone_recent_unavailable, snapshot.error);
                    mStart.prepare(true);
                });
        });
    }

    private void closeDesktop() {
        if (!mClosing) { DesktopWorkspacePicker.select(mActivity, this::closeDesktop); }
    }

    private void closeDesktop(final DesktopDisplayTarget target) {
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
        mCloseDesktop.setContentDescription(mActivity.getString(R.string.status_desktop_closing));
        DesktopOperations.closeDesktop(
                target,
                DesktopCloseMode.HOME,
                success -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                        return;
                    }
                    if (mHome && !hasActiveHomeLease()) {
                        mActivity.finishAndRemoveTask();
                        return;
                    }
                    mClosing = false;
                    mCloseDesktop.setContentDescription(mActivity.getString(R.string.action_close_desktop));
                    refreshCloseAction();
                }));
    }

    private void refreshCloseAction() {
        if (mCloseDesktop == null || mClosing) {
            return;
        }
        mCloseDesktop.setEnabled(activeLease() != null);
    }

    private boolean hasActiveHomeLease() {
        return canHost(mActivity);
    }

    private static DesktopHomeRoleLease.State activeLease() {
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        return lease != null && lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                ? lease : null;
    }

    private int dp(final int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
