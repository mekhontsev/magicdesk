package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
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
    private boolean mSelfTestRunning;
    private DesktopSelfTestTarget mPendingSelfTestTarget;
    private DesktopDisplayTarget.Kind mPendingSelfTestDisplayKind;
    private DesktopSelfTestExecutionPolicy mPendingSelfTestExecutionPolicy;
    private long mPendingSelfTestRunId;
    private DesktopSelfTestExecutionPolicy mSelfTestExecutionPolicy =
            DesktopSelfTestExecutionPolicy.FULL;
    private long mSelfTestRunId;
    private DesktopSelfTestTarget mPreparingSelfTestTarget;
    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mWirelessDisplayListener;

    static Intent createIntent(final Context context) {
        return new Intent(context, DiagnosticsActivity.class);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        if (!handleAutomatedSelfTest(getIntent())) {
            refreshReport();
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutomatedSelfTest(intent);
    }

    private boolean handleAutomatedSelfTest(final Intent intent) {
        final DesktopSelfTestTarget target = requestedSelfTestTarget(intent);
        if (target == null) {
            return false;
        }
        final DesktopDisplayTarget.Kind displayKind =
                requestedSelfTestDisplayKind(intent);
        final DesktopSelfTestExecutionPolicy executionPolicy =
                requestedSelfTestExecutionPolicy(intent);
        final long runId = intent.getLongExtra(EXTRA_SELF_TEST_RUN_ID, 0L);
        // The debug launcher is a one-shot trigger. Consuming its extras keeps
        // activity recreation during a display test from starting another run.
        intent.removeExtra(EXTRA_SELF_TEST_TARGET);
        intent.removeExtra(EXTRA_SELF_TEST_DISPLAY_KIND);
        intent.removeExtra(EXTRA_SELF_TEST_EXECUTION_POLICY);
        intent.removeExtra(EXTRA_SELF_TEST_RUN_ID);
        if (runId > 0L && !DesktopSelfTestRunState.isStarting(runId)) {
            return true;
        }
        if (mSelfTestRunning || DesktopSelfTestController.isRunning()) {
            return true;
        }
        if (mLoading) {
            mPendingSelfTestTarget = target;
            mPendingSelfTestDisplayKind = displayKind;
            mPendingSelfTestExecutionPolicy = executionPolicy;
            mPendingSelfTestRunId = runId;
            return true;
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mSelfTest.post(() -> prepareSelfTest(
                target, displayKind, executionPolicy, runId));
        return true;
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(16));
        SystemBarInsets.addToPadding(page);
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
        final Button close = createButton(R.string.action_close, COLOR_MUTED);
        close.setOnClickListener(view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(
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
        mSelfTest.setOnClickListener(view -> chooseDesktopSelfTestTarget());
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
        if (mLoading || mSelfTestRunning) {
            return;
        }
        mLoading = true;
        setActionsEnabled(false);
        mStatus.setText(R.string.diagnostics_collecting);
        new Thread(() -> {
            final String report =
                    CompatibilityDiagnostics.buildReport(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mReport = report;
                mReportView.setText(report);
                mStatus.setText(R.string.diagnostics_ready);
                mLoading = false;
                setActionsEnabled(true);
                runPendingAutomatedSelfTest();
            });
        }, "MagicDeskDiagnostics").start();
    }

    private void runPendingAutomatedSelfTest() {
        final DesktopSelfTestTarget target = mPendingSelfTestTarget;
        if (target == null || mSelfTestRunning
                || DesktopSelfTestController.isRunning()) {
            return;
        }
        final DesktopDisplayTarget.Kind displayKind =
                mPendingSelfTestDisplayKind;
        final DesktopSelfTestExecutionPolicy executionPolicy =
                mPendingSelfTestExecutionPolicy == null
                        ? DesktopSelfTestExecutionPolicy.FULL
                        : mPendingSelfTestExecutionPolicy;
        final long runId = mPendingSelfTestRunId;
        mPendingSelfTestTarget = null;
        mPendingSelfTestDisplayKind = null;
        mPendingSelfTestExecutionPolicy = null;
        mPendingSelfTestRunId = 0L;
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mSelfTest.post(() -> prepareSelfTest(
                target, displayKind, executionPolicy, runId));
    }

    private void chooseDesktopSelfTestTarget() {
        if (mLoading || mSelfTestRunning
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
                    mSelfTestExecutionPolicy =
                            DesktopSelfTestExecutionPolicy.FULL;
                    if (which == 0) {
                        prepareSimulatedSelfTest();
                    } else if (which == 1) {
                        prepareExternalSelfTest();
                    } else {
                        preparePhoneSelfTest();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void prepareSimulatedSelfTest() {
        prepareSimulatedSelfTest(0L);
    }

    private void prepareSimulatedSelfTest(final long requestedRunId) {
        if (beginSelfTestPreparation(
                DesktopSelfTestTarget.SIMULATED, null, requestedRunId)) {
            runDesktopSelfTest(DesktopSelfTestTarget.SIMULATED);
        }
    }

    private void prepareSelfTest(
            final DesktopSelfTestTarget target,
            final DesktopDisplayTarget.Kind displayKind,
            final DesktopSelfTestExecutionPolicy executionPolicy,
            final long requestedRunId) {
        mSelfTestExecutionPolicy = executionPolicy == null
                ? DesktopSelfTestExecutionPolicy.FULL : executionPolicy;
        if (target == DesktopSelfTestTarget.SIMULATED) {
            prepareSimulatedSelfTest(requestedRunId);
        } else if (target == DesktopSelfTestTarget.EXTERNAL) {
            prepareExternalSelfTest(displayKind, requestedRunId);
        } else {
            preparePhoneSelfTest(requestedRunId);
        }
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

    private static DesktopDisplayTarget.Kind requestedSelfTestDisplayKind(
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
            final DesktopDisplayTarget.Kind kind =
                    DesktopDisplayTarget.Kind.valueOf(name);
            return kind == DesktopDisplayTarget.Kind.WIRED
                            || kind == DesktopDisplayTarget.Kind.WIRELESS
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

    private void preparePhoneSelfTest() {
        preparePhoneSelfTest(0L);
    }

    private void preparePhoneSelfTest(final long requestedRunId) {
        if (!beginSelfTestPreparation(
                DesktopSelfTestTarget.PHONE, null, requestedRunId)) {
            return;
        }
        DesktopDisplayDrivers
                .forKind(DesktopDisplayTarget.Kind.PHONE)
                .showReady(
                        this,
                        DesktopDisplayTarget.phone(),
                        DesktopSessionPolicy.ISOLATED_SELF_TEST);
        waitForPreparedDesktop(DesktopSelfTestTarget.PHONE);
    }

    private void prepareExternalSelfTest() {
        prepareExternalSelfTest(null, 0L);
    }

    private void prepareExternalSelfTest(
            final DesktopDisplayTarget.Kind requestedKind) {
        prepareExternalSelfTest(requestedKind, 0L);
    }

    private void prepareExternalSelfTest(
            final DesktopDisplayTarget.Kind requestedKind,
            final long requestedRunId) {
        if (!beginSelfTestPreparation(
                DesktopSelfTestTarget.EXTERNAL,
                requestedKind,
                requestedRunId)) {
            return;
        }
        new Thread(() -> {
            final int physicalWiredDisplayId =
                    ExternalDisplayController.findExternalDisplayId();
            final int wirelessDisplayId =
                    ExternalDisplayController.findWirelessDisplayId();
            if (!DesktopSelfTestRunState.isStarting(mSelfTestRunId)) {
                return;
            }
            if (requestedKind != DesktopDisplayTarget.Kind.WIRELESS
                    && physicalWiredDisplayId > Display.DEFAULT_DISPLAY) {
                DesktopOperations.showWiredDesktop(
                        DesktopSessionPolicy.ISOLATED_SELF_TEST);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        waitForPreparedDesktop(
                                DesktopSelfTestTarget.EXTERNAL,
                                DesktopDisplayTarget.Kind.WIRED);
                    }
                });
                return;
            }
            if (requestedKind == DesktopDisplayTarget.Kind.WIRED) {
                runOnUiThread(() -> {
                    failSelfTestPreparation(
                            "connected wired display is unavailable",
                            R.string.status_external_display_unavailable);
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (wirelessDisplayId <= Display.DEFAULT_DISPLAY) {
                    awaitWirelessDisplay();
                    if (mWirelessDisplayListener != null
                            && PlatformDrivers.current().projection()
                                    .openWirelessConnectionUi(this)) {
                        mStatus.setText(
                                R.string.diagnostics_self_test_connect_wireless);
                    } else {
                        failSelfTestPreparation(
                                "external display is unavailable",
                                R.string.status_external_display_unavailable);
                    }
                    return;
                }
                DesktopOperations.showDesktop(
                        DesktopDisplayTarget.wireless(
                                wirelessDisplayId),
                        DesktopSessionPolicy.ISOLATED_SELF_TEST);
                waitForPreparedDesktop(
                        DesktopSelfTestTarget.EXTERNAL,
                        DesktopDisplayTarget.Kind.WIRELESS);
            });
        }, "MagicDeskSelfTestDisplayProbe").start();
    }

    private void awaitWirelessDisplay() {
        stopAwaitingWirelessDisplay();
        mDisplayManager = getSystemService(DisplayManager.class);
        if (mDisplayManager == null) {
            return;
        }
        mWirelessDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
                probeWirelessDisplay();
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                probeWirelessDisplay();
            }
        };
        mDisplayManager.registerDisplayListener(
                mWirelessDisplayListener, null);
    }

    private void probeWirelessDisplay() {
        new Thread(() -> {
            final int displayId =
                    ExternalDisplayController.findWirelessDisplayId();
            runOnUiThread(() -> continueExternalSelfTest(displayId));
        }, "MagicDeskSelfTestWirelessProbe").start();
    }

    private void continueExternalSelfTest(final int displayId) {
        if (isFinishing() || isDestroyed()
                || !DesktopSelfTestRunState.isStarting(mSelfTestRunId)
                || mWirelessDisplayListener == null
                || mDisplayManager == null) {
            return;
        }
        if (displayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        stopAwaitingWirelessDisplay();
        DesktopOperations.showDesktop(
                DesktopDisplayTarget.wireless(displayId),
                DesktopSessionPolicy.ISOLATED_SELF_TEST);
        waitForPreparedDesktop(
                DesktopSelfTestTarget.EXTERNAL,
                DesktopDisplayTarget.Kind.WIRELESS);
    }

    private void stopAwaitingWirelessDisplay() {
        if (mDisplayManager != null && mWirelessDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(
                    mWirelessDisplayListener);
        }
        mWirelessDisplayListener = null;
        mDisplayManager = null;
    }

    private boolean beginSelfTestPreparation(
            final DesktopSelfTestTarget target,
            final DesktopDisplayTarget.Kind displayKind,
            final long requestedRunId) {
        if (DesktopSelfTestController.phoneUiUnavailableReason(this) != null) {
            failReservedSelfTest(
                    requestedRunId, "phone is locked or not interactive");
            mStatus.setText(R.string.diagnostics_self_test_unlock_phone);
            return false;
        }
        if (DesktopRuntimeBridge.getActiveDesktopDisplayId()
                != Display.INVALID_DISPLAY) {
            failReservedSelfTest(
                    requestedRunId, "close the active desktop first");
            mStatus.setText(R.string.diagnostics_self_test_close_desktop);
            return false;
        }
        final String targetName = displayKind == null
                ? target.name().toLowerCase(java.util.Locale.ROOT)
                : displayKind.name().toLowerCase(java.util.Locale.ROOT);
        final long runId = requestedRunId > 0L
                ? requestedRunId
                : DesktopSelfTestRunState.beginRequest(
                        targetName,
                        mSelfTestExecutionPolicy,
                        System.currentTimeMillis());
        if (!DesktopSelfTestRunState.isStarting(runId)) {
            mStatus.setText(R.string.diagnostics_self_test_prepare_failed);
            return false;
        }
        mSelfTestRunId = runId;
        mPreparingSelfTestTarget = target;
        mSelfTestRunning = true;
        setActionsEnabled(false);
        mStatus.setText(R.string.diagnostics_self_test_preparing);
        DesktopSelfTestHostObserver.begin();
        DesktopSelfTestRunState.registerPreparationCancellationHandler(
                runId,
                () -> runOnUiThread(
                        () -> cancelSelfTestPreparation(runId)));
        if (DesktopSelfTestRunState.snapshot().cancellationRequested) {
            return false;
        }
        return true;
    }

    private void failReservedSelfTest(
            final long runId, final String detail) {
        if (!DesktopSelfTestRunState.isStarting(runId)) {
            return;
        }
        final boolean cancelled = DesktopSelfTestRunState.snapshot()
                .cancellationRequested;
        DesktopSelfTestRunState.complete(
                runId,
                cancelled,
                cancelled,
                System.currentTimeMillis(),
                cancelled ? "cancelled during preparation" : detail,
                DesktopSelfTestResult.lastModifiedMillis(this));
    }

    private void failSelfTestPreparation(
            final String detail, final int statusResource) {
        final long runId = mSelfTestRunId;
        if (!DesktopSelfTestRunState.isStarting(runId)) {
            return;
        }
        DesktopSelfTestRunState.complete(
                runId,
                false,
                false,
                System.currentTimeMillis(),
                detail,
                DesktopSelfTestResult.lastModifiedMillis(this));
        finishSelfTestPreparation();
        mStatus.setText(statusResource);
    }

    private void cancelSelfTestPreparation(final long runId) {
        if (!DesktopSelfTestRunState.isStarting(runId)
                || runId != mSelfTestRunId) {
            return;
        }
        closePreparedDesktop(mPreparingSelfTestTarget);
        DesktopSelfTestRunState.complete(
                runId,
                true,
                true,
                System.currentTimeMillis(),
                "cancelled during preparation",
                DesktopSelfTestResult.lastModifiedMillis(this));
        finishSelfTestPreparation();
        mStatus.setText(R.string.diagnostics_self_test_cancelled);
    }

    private void closePreparedDesktop(final DesktopSelfTestTarget target) {
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (target == null || !target.matchesDisplay(
                displayId,
                DesktopRuntimeBridge.getDesktopTarget(displayId))) {
            return;
        }
        if (target == DesktopSelfTestTarget.EXTERNAL) {
            PhoneTouchpadController.release(displayId);
        }
        DesktopRuntimeBridge.closeDesktopSession(displayId);
    }

    private void finishSelfTestPreparation() {
        DesktopSelfTestRunState.clearPreparationCancellationHandler(
                mSelfTestRunId);
        stopAwaitingWirelessDisplay();
        mSelfTestRunning = false;
        setActionsEnabled(true);
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DesktopSelfTestHostObserver.cancel();
        mSelfTestRunId = 0L;
        mPreparingSelfTestTarget = null;
    }

    @Override
    protected void onDestroy() {
        stopAwaitingWirelessDisplay();
        if (isFinishing()
                && DesktopSelfTestRunState.isStarting(mSelfTestRunId)) {
            DesktopSelfTestRunState.requestCancellation(mSelfTestRunId);
            cancelSelfTestPreparation(mSelfTestRunId);
        } else if (mSelfTestRunning
                && !DesktopSelfTestController.isRunning()) {
            // Preparation owns the observer until the test takes over. Do not
            // leave it recording unrelated desktop frames if this UI closes.
            DesktopSelfTestHostObserver.cancel();
        }
        if (isFinishing()
                && DesktopSelfTestRunState.isStarting(mPendingSelfTestRunId)) {
            final long pendingRunId = mPendingSelfTestRunId;
            DesktopSelfTestRunState.requestCancellation(pendingRunId);
            DesktopSelfTestRunState.complete(
                    pendingRunId,
                    true,
                    true,
                    System.currentTimeMillis(),
                    "diagnostics closed during preparation",
                    DesktopSelfTestResult.lastModifiedMillis(this));
        }
        super.onDestroy();
    }

    private void waitForPreparedDesktop(
            final DesktopSelfTestTarget target) {
        waitForPreparedDesktop(target, null);
    }

    private void waitForPreparedDesktop(
            final DesktopSelfTestTarget target,
            final DesktopDisplayTarget.Kind expectedKind) {
        final long runId = mSelfTestRunId;
        new Thread(() -> {
            final long deadline = SystemClock.uptimeMillis()
                    + ExternalDisplayController.START_TIMEOUT_MS * 2L;
            boolean ready = false;
            do {
                if (!DesktopSelfTestRunState.isStarting(runId)) {
                    return;
                }
                final int displayId =
                        DesktopRuntimeBridge.getActiveDesktopDisplayId();
                final DesktopDisplayTarget displayTarget =
                        DesktopRuntimeBridge.getDesktopTarget(displayId);
                // The self-test verifies host window readiness itself.
                if (target.matchesDisplay(
                                displayId,
                                displayTarget)
                        && (expectedKind == null
                                || displayTarget.kind == expectedKind)) {
                    ready = true;
                    break;
                }
                BoundedStateAwaiter.pause(
                        BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        ExternalDisplayController.STATE_POLL_MS);
            } while (SystemClock.uptimeMillis() < deadline);
            final boolean prepared = ready;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!DesktopSelfTestRunState.isStarting(runId)) {
                    return;
                }
                if (prepared) {
                    runDesktopSelfTest(target);
                } else {
                    abortSelfTestPreparation(target);
                }
            });
        }, "MagicDeskSelfTestDesktopWait").start();
    }

    private void abortSelfTestPreparation(
            final DesktopSelfTestTarget target) {
        final long runId = mSelfTestRunId;
        closePreparedDesktop(target);
        DesktopSelfTestRunState.complete(
                runId,
                false,
                false,
                System.currentTimeMillis(),
                "desktop preparation timed out",
                DesktopSelfTestResult.lastModifiedMillis(this));
        finishSelfTestPreparation();
        mStatus.setText(R.string.diagnostics_self_test_prepare_failed);
    }

    private void runDesktopSelfTest(
            final DesktopSelfTestTarget target) {
        if (!mSelfTestRunning) {
            mSelfTestRunning = true;
            setActionsEnabled(false);
        }
        mStatus.setText(R.string.diagnostics_self_test_running);
        new Thread(() -> {
            final long runId = mSelfTestRunId;
            final DesktopSelfTestResult result =
                    DesktopSelfTestController.run(
                            getApplicationContext(),
                            target,
                            getTaskId(),
                            mSelfTestExecutionPolicy,
                            runId);
            final String report =
                    CompatibilityDiagnostics.buildReport(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mReport = report;
                mReportView.setText(report);
                if (result.isCancelled()) {
                    mStatus.setText(R.string.diagnostics_self_test_cancelled);
                } else {
                    mStatus.setText(getString(
                            R.string.diagnostics_self_test_complete,
                            result.summary()));
                }
                finishSelfTestPreparation();
            });
        }, "MagicDeskDesktopSelfTest").start();
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
        final Intent share = AndroidContentIntentAdapter.share(content);
        startActivity(Intent.createChooser(share, getString(R.string.diagnostics_share)));
    }

    private void confirmVendorProbe() {
        if (mLoading || mSelfTestRunning || !ShellAccess.isReady()) {
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
            final String report = CompatibilityDiagnostics.buildReport(
                    getApplicationContext());
            final String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mReport = report;
                mReportView.setText(report);
                mStatus.setText(message.isEmpty()
                        ? getString(R.string.diagnostics_vendor_probe_complete)
                        : getString(
                                R.string.diagnostics_vendor_probe_failed,
                                message));
                mLoading = false;
                setActionsEnabled(true);
            });
        }, "MagicDeskVendorProbe").start();
    }

    private void setActionsEnabled(final boolean enabled) {
        mRefresh.setEnabled(enabled);
        mCopy.setEnabled(enabled);
        mShare.setEnabled(enabled);
        mSelfTest.setEnabled(enabled && ShellAccess.isReady());
        mOnboarding.setEnabled(enabled);
        mVendorProbe.setEnabled(enabled && ShellAccess.isReady());
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

    private int getDisplayId() {
        final Display display = getDisplay();
        return display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }
}
