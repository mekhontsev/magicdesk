package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class DiagnosticsActivity extends Activity {
    static final String EXTRA_SELF_TEST_TARGET =
            "io.github.mekhontsev.magicdesk.extra.SELF_TEST_TARGET";
    static final String EXTRA_SELF_TEST_DISPLAY_KIND =
            "io.github.mekhontsev.magicdesk.extra.SELF_TEST_DISPLAY_KIND";
    static final String EXTRA_SELF_TEST_EXECUTION_POLICY =
            "io.github.mekhontsev.magicdesk.extra.SELF_TEST_EXECUTION_POLICY";
    static final String EXTRA_SELF_TEST_RUN_ID =
            "io.github.mekhontsev.magicdesk.extra.SELF_TEST_RUN_ID";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private static final String EXTRA_GUARD_RUN_ID = "magicdesk_self_test_guard_run_id";
    private static final String EXTRA_RESULT_RUN_ID = "magicdesk_self_test_result_run_id";
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mRenderProgress = this::renderRunState;
    private final Runnable mRunChanged = () -> {
        mMain.removeCallbacks(mRenderProgress);
        mMain.post(mRenderProgress);
    };
    private Button mClose;
    private long mGuardRunId;
    private long mObservedRunId;
    private long mReportRunId;
    private boolean mCancelGesture;
    private boolean mResumed;
    private TextView mStatus;
    private TextView mReportView;
    private Button mRefresh;
    private Button mCopy;
    private Button mShare;
    private Button mSelfTest;
    private Button mOnboarding;
    private Button mVendorProbe;
    private String mReport = "";
    private boolean mLoading;

    static Intent createIntent(final Context context) {
        return new Intent(context, DiagnosticsActivity.class);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mObservedRunId = savedInstanceState == null ? 0L
                : savedInstanceState.getLong("observedRunId");
        setContentView(createContentView());
        if (!handleAutomatedSelfTest(getIntent())) {
            final long requestedResult = getIntent().getLongExtra(EXTRA_RESULT_RUN_ID, 0L);
            final DesktopSelfTestRunState.Snapshot state = DesktopSelfTestRunState.snapshot();
            if (state.terminal()
                    && (state.runId == requestedResult || state.runId == mObservedRunId)) {
                mReportRunId = state.runId;
            }
            refreshReport();
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutomatedSelfTest(intent);
        renderRunState();
        if (mResumed) {
            DesktopSelfTestGuardWindow.resumed(this, mGuardRunId);
        }
    }

    private boolean handleAutomatedSelfTest(final Intent intent) {
        final long guardRunId = intent.getLongExtra(EXTRA_GUARD_RUN_ID, 0L);
        if (DesktopSelfTestGuardWindow.accepts(guardRunId)) {
            mGuardRunId = guardRunId;
            renderRunState();
            return true;
        }
        final DesktopSelfTestTarget target = requestedSelfTestTarget(intent);
        if (target == null) {
            return DesktopSelfTestRunState.isActive();
        }
        final DesktopDisplayOutput.Kind kind = requestedSelfTestDisplayKind(intent);
        final DesktopSelfTestExecutionPolicy policy = requestedSelfTestExecutionPolicy(intent);
        final long runId = intent.getLongExtra(EXTRA_SELF_TEST_RUN_ID, 0L);
        intent.removeExtra(EXTRA_SELF_TEST_TARGET);
        intent.removeExtra(EXTRA_SELF_TEST_DISPLAY_KIND);
        intent.removeExtra(EXTRA_SELF_TEST_EXECUTION_POLICY);
        intent.removeExtra(EXTRA_SELF_TEST_RUN_ID);
        DesktopSelfTestLauncher.start(this, target, kind, policy, runId);
        renderRunState();
        return true;
    }

    static void showGuard(final Context context, final long runId) {
        showOnPhone(context, createIntent(context).putExtra(EXTRA_GUARD_RUN_ID, runId));
    }

    static void showResults(final Context context, final long runId) {
        showOnPhone(context, createIntent(context).putExtra(EXTRA_RESULT_RUN_ID, runId));
    }

    private static void showOnPhone(final Context context, final Intent intent) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        DesktopShellActivity.setLaunchWindowingMode(
                options, FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN);
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION), options.toBundle());
    }

    @Override
    protected void onStart() {
        super.onStart();
        DesktopSelfTestLauncher.attach(this);
        DesktopSelfTestRunState.addListener(mRunChanged);
        renderRunState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        DesktopSelfTestGuardWindow.resumed(this, mGuardRunId);
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putLong("observedRunId", mObservedRunId);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        DesktopSelfTestGuardWindow.stopped(this, isChangingConfigurations());
        DesktopSelfTestRunState.removeListener(mRunChanged);
        mMain.removeCallbacks(mRenderProgress);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        DesktopSelfTestGuardWindow.destroyed(this);
        super.onDestroy();
    }

    void releaseSelfTestGuard() {
        mGuardRunId = 0L;
        getIntent().removeExtra(EXTRA_GUARD_RUN_ID);
        // Retain this report task, but uncover the original phone UI so the
        // existing foreground and cleanup assertions still exercise it.
        if (DesktopSelfTestGuardWindow.isVisible() && !moveTaskToBack(true)) {
            Log.w("MagicDeskDiagnostics", "Could not hide the self-test input guard");
        }
    }

    private boolean guardsPhoneInput() {
        return getDisplay() != null && getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY
                && DesktopSelfTestGuardWindow.accepts(mGuardRunId);
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (!guardsPhoneInput()) {
            return super.dispatchTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            final Rect bounds = new Rect();
            mCancelGesture = mSelfTest.getGlobalVisibleRect(bounds)
                    && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
        }
        if (!mCancelGesture) {
            DesktopSelfTestPhoneInputGuard.recordTouch(event);
            return true;
        }
        super.dispatchTouchEvent(event);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        if (guardsPhoneInput()) {
            DesktopSelfTestPhoneInputGuard.recordKey(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void renderRunState() {
        if (isFinishing() || isDestroyed() || mStatus == null) {
            return;
        }
        final DesktopSelfTestRunState.Snapshot state = DesktopSelfTestRunState.snapshot();
        if (state.active()) {
            mObservedRunId = state.runId;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            final WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null && guardsPhoneInput()) {
                insets.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                insets.hide(WindowInsets.Type.systemBars());
            }
            setActionsEnabled(false);
            setButtonEnabled(mClose, false);
            mSelfTest.setText(state.cancellationRequested
                    ? R.string.self_test_phone_guard_stopping
                    : R.string.self_test_phone_guard_cancel);
            setButtonEnabled(mSelfTest, !state.cancellationRequested
                    && state.state != DesktopSelfTestRunState.State.CLEANUP);
            mStatus.setText(state.state == DesktopSelfTestRunState.State.CLEANUP
                    ? R.string.self_test_phone_guard_stopping_message
                    : state.cancellationRequested ? R.string.self_test_phone_guard_stopping
                    : state.state == DesktopSelfTestRunState.State.STARTING
                    ? R.string.diagnostics_self_test_preparing
                    : R.string.diagnostics_self_test_running);
            if (guardsPhoneInput()) {
                mStatus.append("\n" + getString(R.string.self_test_phone_guard_message));
            }
            final DesktopSelfTestRunState.Progress progress = state.progress;
            mReportView.setTextSize(16);
            mReportView.setTextIsSelectable(false);
            mReportView.setText(getString(R.string.diagnostics_self_test_progress,
                    state.target, state.stage, progress.stageLabel,
                    progress.passed, progress.warnings, progress.failed, progress.notTested,
                    state.lastCompletedStage, progress.lastResult, progress.lastLabel,
                    progress.lastDetail));
            return;
        }
        mGuardRunId = 0L;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.show(WindowInsets.Type.systemBars());
        }
        setButtonEnabled(mClose, true);
        mSelfTest.setText(R.string.diagnostics_self_test);
        setActionsEnabled(!mLoading);
        final long resultRunId = getIntent().getLongExtra(EXTRA_RESULT_RUN_ID, 0L);
        if (state.terminal() && (mObservedRunId == state.runId || resultRunId == state.runId)
                && mReportRunId != state.runId && !mLoading) {
            mReportRunId = state.runId;
            refreshReport();
        }
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(16));
        SystemBarInsets.addToPadding(page, true);
        page.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.diagnostics_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mClose = createButton(R.string.action_close, COLOR_MUTED);
        mClose.setOnClickListener(view -> finish());
        header.addView(mClose, new LinearLayout.LayoutParams(
                dp(92), dp(46)));
        page.addView(header);

        final TextView description = new TextView(this);
        description.setText(R.string.diagnostics_description);
        description.setTextColor(COLOR_MUTED);
        description.setTextSize(13);
        final LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(6), 0, dp(10));
        page.addView(description, descriptionParams);

        mStatus = new TextView(this);
        mStatus.setTextColor(COLOR_CYAN);
        mStatus.setTextSize(14);
        mStatus.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(mStatus);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        mReportView = new TextView(this);
        mReportView.setTextColor(COLOR_TEXT);
        mReportView.setTextSize(11);
        mReportView.setTypeface(Typeface.MONOSPACE);
        mReportView.setTextIsSelectable(true);
        mReportView.setPadding(dp(12), dp(10), dp(12), dp(10));
        mReportView.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        scroll.addView(mReportView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(8), 0, dp(10));
        page.addView(scroll, scrollParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mRefresh = createButton(R.string.diagnostics_refresh, COLOR_CYAN);
        mRefresh.setOnClickListener(view -> refreshReport());
        actions.addView(mRefresh, weightedButtonParams(0));
        mCopy = createButton(R.string.diagnostics_copy, COLOR_CYAN);
        mCopy.setOnClickListener(view -> copyReport());
        actions.addView(mCopy, weightedButtonParams(dp(8)));
        mShare = createButton(R.string.diagnostics_share, COLOR_AMBER);
        mShare.setOnClickListener(view -> shareReport());
        actions.addView(mShare, weightedButtonParams(dp(8)));
        page.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        mSelfTest = createButton(
                R.string.diagnostics_self_test, COLOR_AMBER);
        mSelfTest.setOnClickListener(view -> {
            final DesktopSelfTestRunState.Snapshot state = DesktopSelfTestRunState.snapshot();
            if (state.active()) {
                DesktopSelfTestRunState.requestCancellation(state.runId);
            } else {
                chooseDesktopSelfTestTarget();
            }
        });
        final LinearLayout.LayoutParams selfTestParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        selfTestParams.setMargins(0, dp(8), 0, 0);
        page.addView(mSelfTest, selfTestParams);

        mOnboarding = createButton(
                R.string.diagnostics_onboarding, COLOR_CYAN);
        mOnboarding.setOnClickListener(view -> startActivity(
                CompatibilityOnboardingActivity.createIntent(this)));
        final LinearLayout.LayoutParams onboardingParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        onboardingParams.setMargins(0, dp(8), 0, 0);
        page.addView(mOnboarding, onboardingParams);

        mVendorProbe = createButton(
                R.string.diagnostics_vendor_probe, COLOR_MUTED);
        mVendorProbe.setOnClickListener(view -> confirmVendorProbe());
        final LinearLayout.LayoutParams vendorProbeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        vendorProbeParams.setMargins(0, dp(8), 0, 0);
        page.addView(mVendorProbe, vendorProbeParams);
        return page;
    }

    private LinearLayout.LayoutParams weightedButtonParams(final int leftMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private void refreshReport() {
        if (mLoading || DesktopSelfTestRunState.isActive()) {
            return;
        }
        mLoading = true;
        setActionsEnabled(false);
        mStatus.setText(R.string.diagnostics_collecting);
        new Thread(() -> {
            final DiagnosticsReportResult report = collectReport();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                finishReportCollection(report, report.successful()
                        ? getString(R.string.diagnostics_ready) : "");
            });
        }, "MagicDeskDiagnostics").start();
    }

    private DiagnosticsReportResult collectReport() {
        final DiagnosticsReportResult result = DiagnosticsReportResult.collect(() ->
                CompatibilityDiagnostics.buildReport(getApplicationContext()));
        if (!result.successful()) {
            Log.w("MagicDeskDiagnostics", "Could not collect compatibility report", result.cause);
        }
        return result;
    }

    private void showReport(
            final DiagnosticsReportResult report,
            final String status) {
        mReport = report.report;
        final String failure = report.successful() ? "" : getString(
                R.string.diagnostics_report_failed, report.failure);
        mReportView.setTextSize(11);
        mReportView.setTextIsSelectable(true);
        mReportView.setText(report.successful() ? mReport : failure);
        mStatus.setText(failure.isEmpty() ? status
                : status.isEmpty() ? failure : status + "\n" + failure);
    }

    private void finishReportCollection(
            final DiagnosticsReportResult report,
            final String status) {
        mLoading = false;
        if (DesktopSelfTestRunState.isActive()) {
            renderRunState();
            return;
        }
        final DesktopSelfTestRunState.Snapshot state = DesktopSelfTestRunState.snapshot();
        final String resultStatus = mReportRunId == state.runId && state.terminal()
                ? state.state == DesktopSelfTestRunState.State.CANCELLED
                        ? getString(R.string.diagnostics_self_test_cancelled)
                        : getString(R.string.diagnostics_self_test_complete, state.detail)
                : status;
        showReport(report, resultStatus);
        setActionsEnabled(true);
        renderRunState();
    }

    private void chooseDesktopSelfTestTarget() {
        if (mLoading || DesktopSelfTestRunState.isActive()
                || DesktopSelfTestController.isRunning()) {
            return;
        }
        final String[] choices = {
                getString(R.string.diagnostics_self_test_simulated),
                getString(R.string.diagnostics_self_test_external),
                getString(R.string.diagnostics_self_test_phone)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_self_test_target)
                .setItems(choices, (dialog, which) -> {
                    final DesktopSelfTestTarget target = which == 0
                            ? DesktopSelfTestTarget.SIMULATED : which == 1
                            ? DesktopSelfTestTarget.EXTERNAL : DesktopSelfTestTarget.PHONE;
                    DesktopSelfTestLauncher.start(this, target, null,
                            DesktopSelfTestExecutionPolicy.FULL, 0L);
                    renderRunState();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static DesktopSelfTestTarget requestedSelfTestTarget(
            final Intent intent) {
        if (intent == null) {
            return null;
        }
        final String name = intent.getStringExtra(EXTRA_SELF_TEST_TARGET);
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return DesktopSelfTestTarget.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static DesktopDisplayOutput.Kind requestedSelfTestDisplayKind(
            final Intent intent) {
        if (intent == null) {
            return null;
        }
        final String name = intent.getStringExtra(
                EXTRA_SELF_TEST_DISPLAY_KIND);
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            final DesktopDisplayOutput.Kind kind =
                    DesktopDisplayOutput.Kind.valueOf(name);
            return kind == DesktopDisplayOutput.Kind.WIRED
                            || kind == DesktopDisplayOutput.Kind.WIRELESS
                    ? kind : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static DesktopSelfTestExecutionPolicy
            requestedSelfTestExecutionPolicy(final Intent intent) {
        if (intent == null) {
            return DesktopSelfTestExecutionPolicy.FULL;
        }
        return DesktopSelfTestExecutionPolicy.parse(intent.getStringExtra(
                EXTRA_SELF_TEST_EXECUTION_POLICY));
    }

    private void copyReport() {
        if (mReport.isEmpty()) {
            return;
        }
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(this).writeText(
                        "MagicDesk compatibility report", mReport, false);
        if (!copied.successful) {
            Toast.makeText(this, R.string.diagnostics_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (mReport.isEmpty()) {
            return;
        }
        final AndroidContentPayload content = AndroidContentPayload.create(
                AndroidContentPayload.Origin.APPLICATION,
                "MagicDesk compatibility report",
                "MagicDesk compatibility report",
                mReport,
                "",
                List.of(),
                List.of("text/plain"),
                false);
        final int displayId = getDisplay() == null
                ? Display.DEFAULT_DISPLAY : getDisplay().getDisplayId();
        if (DesktopRuntimeBridge.hasWorkspace(displayId)) {
            AndroidDesktopActionDispatcher.shareContent(
                    this,
                    content,
                    displayId,
                    result -> {
                        if (!result.success) {
                            Toast.makeText(
                                    this,
                                    result.message,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
            return;
        }
        final Intent share = AndroidContentIntentAdapter.share(content);
        startActivity(Intent.createChooser(share, getString(R.string.diagnostics_share)));
    }

    private void confirmVendorProbe() {
        if (mLoading || DesktopSelfTestRunState.isActive() || !ShellAccess.isReady()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_vendor_probe)
                .setMessage(R.string.diagnostics_vendor_probe_description)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.diagnostics_vendor_probe_collect,
                        (dialog, which) -> collectVendorProbe())
                .show();
    }

    private void collectVendorProbe() {
        mLoading = true;
        setActionsEnabled(false);
        mStatus.setText(R.string.diagnostics_vendor_probe_running);
        new Thread(() -> {
            String failure = "";
            try {
                VendorDiscoveryReport.save(
                        getApplicationContext(),
                        VendorDiscoveryReport.collect(
                                getApplicationContext()));
            } catch (java.io.IOException | RuntimeException error) {
                failure = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
            }
            final DiagnosticsReportResult report = collectReport();
            final String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                finishReportCollection(report, message.isEmpty()
                        ? getString(R.string.diagnostics_vendor_probe_complete)
                        : getString(
                                R.string.diagnostics_vendor_probe_failed,
                                message));
            });
        }, "MagicDeskVendorProbe").start();
    }

    private void setActionsEnabled(final boolean enabled) {
        setButtonEnabled(mRefresh, enabled);
        setButtonEnabled(mCopy, enabled && !mReport.isEmpty());
        setButtonEnabled(mShare, enabled && !mReport.isEmpty());
        setButtonEnabled(mSelfTest, enabled && ShellAccess.isReady());
        setButtonEnabled(mOnboarding, enabled);
        setButtonEnabled(mVendorProbe, enabled && ShellAccess.isReady());
    }

    private static void setButtonEnabled(final Button button, final boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    private Button createButton(final int textResId, final int accentColor) {
        final Button button = new Button(this);
        button.setText(textResId);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        button.setBackground(rounded(COLOR_PANEL_ALT, dp(6), accentColor));
        return button;
    }

    private GradientDrawable rounded(
            final int color, final int radius, final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
