package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.Collections;
import java.util.List;

/** Launcher-owned phone Overview for tasks outside the desktop workspace. */
public final class PhoneOverviewActivity extends Activity {
    private static final int OVERVIEW_PADDING_DP = 16;
    private static final int OVERVIEW_HEADER_HEIGHT_DP = 52;
    private static final int TASK_ROW_HEIGHT_DP = 72;
    private static final int TASK_ICON_SIZE_DP = 44;
    private static final int TASK_ROW_GAP_DP = 8;

    private FrameLayout mRoot;
    private Button mCloseDesktop;
    private boolean mClosing;
    private boolean mFirstResume = true;
    private int mOverviewGeneration;
    private final OnBackInvokedCallback mBackCallback =
            this::finishAndRemoveTask;

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, PhoneOverviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (activeLease() == null) {
            finishAndRemoveTask();
            return;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
        mRoot = new FrameLayout(this);
        setContentView(
                mRoot,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        showOverview();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showOverview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mFirstResume) {
            mFirstResume = false;
            refreshCloseAction();
            return;
        }
        showOverview();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mBackCallback);
        super.onDestroy();
    }

    private void showOverview() {
        final DesktopHomeRoleLease.State lease = activeLease();
        if (lease == null) {
            finishAndRemoveTask();
            return;
        }
        final int generation = ++mOverviewGeneration;
        mRoot.removeAllViews();
        mRoot.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);

        final DesktopUiFactory ui = new DesktopUiFactory(this);
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(OVERVIEW_PADDING_DP);
        page.setPadding(padding, padding, padding, padding);
        SystemBarInsets.addToPadding(page);
        mRoot.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        final ImageButton back = ui.menuIconButton(
                R.drawable.ic_file_back,
                R.string.action_back);
        header.addView(back, new LinearLayout.LayoutParams(
                dp(OVERVIEW_HEADER_HEIGHT_DP),
                dp(OVERVIEW_HEADER_HEIGHT_DP)));
        back.setOnClickListener(view -> finishAndRemoveTask());

        final TextView title = ui.sectionTitle(R.string.phone_overview_title);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);

        mCloseDesktop = ui.actionButton(
                R.string.action_close_desktop,
                DesktopUiFactory.COLOR_RED);
        mCloseDesktop.setOnClickListener(view -> closeDesktop());
        header.addView(mCloseDesktop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refreshCloseAction();
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(OVERVIEW_HEADER_HEIGHT_DP)));

        TaskRepository.load(
                android.view.Display.DEFAULT_DISPLAY,
                snapshot -> runOnUiThread(() -> populateOverview(
                        page, ui, lease, generation, snapshot)));
    }

    private void populateOverview(
            final LinearLayout page,
            final DesktopUiFactory ui,
            final DesktopHomeRoleLease.State lease,
            final int generation,
            final TaskRepository.Snapshot snapshot) {
        if (isFinishing()
                || isDestroyed()
                || generation != mOverviewGeneration) {
            return;
        }
        if (!snapshot.available) {
            showOverviewMessage(
                    page,
                    getString(
                            R.string.phone_overview_unavailable,
                            snapshot.error));
            return;
        }
        List<TaskRepository.TaskEntry> desktopTasks = Collections.emptyList();
        if (lease.targetKind == DesktopDisplayTarget.Kind.PHONE) {
            final TaskRepository.Snapshot desktopSnapshot =
                    MagicDeskRuntime.selectDesktopTaskSnapshot(
                            android.view.Display.DEFAULT_DISPLAY,
                            snapshot);
            if (!desktopSnapshot.available) {
                showOverviewMessage(
                        page,
                        getString(
                                R.string.phone_overview_unavailable,
                                desktopSnapshot.error));
                return;
            }
            desktopTasks = desktopSnapshot.tasks;
        }
        showOverviewTasks(
                page,
                PhoneOverviewTaskPolicy.select(
                        snapshot.tasks,
                        BuildConfig.APPLICATION_ID,
                        lease.previousPackage,
                        desktopTasks),
                ui);
    }

    private void showOverviewTasks(
            final LinearLayout page,
            final List<TaskRepository.TaskEntry> tasks,
            final DesktopUiFactory ui) {
        removeOverviewContent(page);
        if (tasks.isEmpty()) {
            page.addView(overviewMessage(R.string.recent_apps_empty),
                    overviewContentParams());
            return;
        }

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, overviewContentParams());

        for (final TaskRepository.TaskEntry task : tasks) {
            list.addView(taskRow(task, ui), taskRowParams());
        }
    }

    private View taskRow(
            final TaskRepository.TaskEntry task,
            final DesktopUiFactory ui) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), 0, dp(8), 0);
        row.setBackground(ui.menuSurface());
        row.setClickable(true);
        row.setFocusable(true);

        final AppPresentation app = appPresentation(task.packageName);
        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setContentDescription(app.label);
        row.addView(icon, new LinearLayout.LayoutParams(
                dp(TASK_ICON_SIZE_DP),
                dp(TASK_ICON_SIZE_DP)));

        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        labelParams.leftMargin = dp(14);
        row.addView(label, labelParams);

        final ImageButton close = ui.menuIconButton(
                R.drawable.ic_close,
                R.string.action_close);
        row.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        close.setOnClickListener(view -> closeTask(task));
        row.setOnClickListener(view -> activateTask(task));
        return row;
    }

    private void activateTask(final TaskRepository.TaskEntry task) {
        TaskRepository.bringToFront(task, result -> runOnUiThread(() -> {
            if (!result.success) {
                Toast.makeText(
                        this,
                        result.message,
                        Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void closeTask(final TaskRepository.TaskEntry task) {
        TaskRepository.closeTask(task, result -> runOnUiThread(() -> {
            if (!result.success) {
                Toast.makeText(
                        this,
                        result.message,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            showOverview();
        }));
    }

    private AppPresentation appPresentation(final String packageName) {
        try {
            final PackageManager packages = getPackageManager();
            final ApplicationInfo info = packages.getApplicationInfo(
                    packageName, 0);
            return new AppPresentation(
                    packages.getApplicationLabel(info),
                    packages.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException error) {
            return new AppPresentation(
                    packageName,
                    getDrawable(R.drawable.ic_magicdesk));
        }
    }

    private TextView overviewMessage(final int textResource) {
        return overviewMessage(getString(textResource));
    }

    private TextView overviewMessage(final String text) {
        final TextView message = new TextView(this);
        message.setText(text);
        message.setTextColor(DesktopUiFactory.COLOR_MUTED);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        return message;
    }

    private void showOverviewMessage(
            final LinearLayout page,
            final String message) {
        removeOverviewContent(page);
        page.addView(overviewMessage(message), overviewContentParams());
    }

    private void removeOverviewContent(final LinearLayout page) {
        while (page.getChildCount() > 1) {
            page.removeViewAt(page.getChildCount() - 1);
        }
    }

    private LinearLayout.LayoutParams overviewContentParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
    }

    private LinearLayout.LayoutParams taskRowParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TASK_ROW_HEIGHT_DP));
        params.topMargin = dp(TASK_ROW_GAP_DP);
        return params;
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
        mCloseDesktop.setText(R.string.status_desktop_closing);
        DesktopOperations.closeDesktop(
                lease.target(),
                false,
                success -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (success) {
                        finishAndRemoveTask();
                        return;
                    }
                    mClosing = false;
                    mCloseDesktop.setText(R.string.action_close_desktop);
                    refreshCloseAction();
                }));
    }

    private void refreshCloseAction() {
        if (mCloseDesktop == null || mClosing) {
            return;
        }
        mCloseDesktop.setEnabled(activeLease() != null);
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

    private static final class AppPresentation {
        final CharSequence label;
        final Drawable icon;

        AppPresentation(final CharSequence label, final Drawable icon) {
            this.label = label;
            this.icon = icon;
        }
    }
}
