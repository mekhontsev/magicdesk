package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

public final class CommandConsoleActivity extends Activity
        implements ShellAccess.StateListener {
    private static final String EXTRA_INITIAL_COMMAND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_COMMAND";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private EditText mScript;
    private TextView mShellStatus;
    private TextView mExecutionStatus;
    private TextView mOutput;
    private Button mRun;
    private Button mClear;
    private Button mCopy;
    private ShellAccess.Snapshot mSnapshot;
    private String mCopyText = "";
    private boolean mRunning;
    private boolean mWarningAccepted;

    static Intent createIntent(final Context context) {
        return new Intent(context, CommandConsoleActivity.class);
    }

    static Intent createIntent(
            final Context context, final String initialCommand) {
        return createIntent(context).putExtra(
                EXTRA_INITIAL_COMMAND,
                initialCommand == null ? "" : initialCommand);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mSnapshot = ShellAccess.currentSnapshot();
        setContentView(createContentView());
        applyInitialCommand(getIntent());
        updateShellStatus();
        updateActions();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyInitialCommand(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShellAccess.addStateListener(this);
    }

    @Override
    protected void onStop() {
        ShellAccess.removeStateListener(this);
        super.onStop();
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            mSnapshot = snapshot;
            updateShellStatus();
            updateActions();
        });
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final Display display = getDisplay();
        final int displayId = display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
        final int bottomPadding = dp(16)
                + (displayId == Display.DEFAULT_DISPLAY
                        ? 0 : dp(DesktopShellActivity.TASKBAR_HEIGHT_DP));
        page.setPadding(dp(18), dp(16), dp(18), bottomPadding);
        SystemBarInsets.addToPadding(page);
        page.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.console_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close = createButton(R.string.action_close, COLOR_MUTED);
        close.setOnClickListener(view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(92), dp(46)));
        page.addView(header);

        final TextView description = new TextView(this);
        description.setText(R.string.console_description);
        description.setTextColor(COLOR_MUTED);
        description.setTextSize(13);
        final LinearLayout.LayoutParams descriptionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(6), 0, dp(6));
        page.addView(description, descriptionParams);

        mShellStatus = new TextView(this);
        mShellStatus.setTextColor(COLOR_CYAN);
        mShellStatus.setTextSize(13);
        mShellStatus.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(mShellStatus);

        final TextView warning = new TextView(this);
        warning.setText(R.string.console_warning);
        warning.setTextColor(COLOR_AMBER);
        warning.setTextSize(12);
        final LinearLayout.LayoutParams warningParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        warningParams.setMargins(0, dp(3), 0, dp(10));
        page.addView(warning, warningParams);

        final TextView commandLabel = sectionLabel(R.string.console_command);
        page.addView(commandLabel);

        mScript = new EditText(this);
        mScript.setTextColor(COLOR_TEXT);
        mScript.setHintTextColor(COLOR_MUTED);
        mScript.setHint(R.string.console_command_hint);
        mScript.setTextSize(13);
        mScript.setTypeface(Typeface.MONOSPACE);
        mScript.setGravity(Gravity.TOP | Gravity.START);
        mScript.setSingleLine(false);
        mScript.setMinLines(4);
        mScript.setMaxLines(8);
        mScript.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mScript.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mScript.setPadding(dp(12), dp(10), dp(12), dp(10));
        mScript.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_MUTED));
        mScript.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    final CharSequence text,
                    final int start,
                    final int count,
                    final int after) {
            }

            @Override
            public void onTextChanged(
                    final CharSequence text,
                    final int start,
                    final int before,
                    final int count) {
                updateActions();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        final LinearLayout.LayoutParams scriptParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        scriptParams.setMargins(0, dp(6), 0, dp(10));
        page.addView(mScript, scriptParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mRun = createButton(R.string.console_run, COLOR_CYAN);
        mRun.setOnClickListener(view -> requestRun());
        actions.addView(mRun, weightedButtonParams(0));
        mClear = createButton(R.string.console_clear, COLOR_CYAN);
        mClear.setOnClickListener(view -> clear());
        actions.addView(mClear, weightedButtonParams(dp(8)));
        mCopy = createButton(R.string.console_copy_output, COLOR_CYAN);
        mCopy.setOnClickListener(view -> copyOutput());
        actions.addView(mCopy, weightedButtonParams(dp(8)));
        page.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        mExecutionStatus = new TextView(this);
        mExecutionStatus.setText(R.string.console_idle);
        mExecutionStatus.setTextColor(COLOR_CYAN);
        mExecutionStatus.setTextSize(13);
        mExecutionStatus.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams executionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        executionParams.setMargins(0, dp(10), 0, dp(6));
        page.addView(mExecutionStatus, executionParams);

        final ScrollView outputScroll = new ScrollView(this);
        outputScroll.setFillViewport(true);
        mOutput = new TextView(this);
        mOutput.setText(R.string.console_no_output);
        mOutput.setTextColor(COLOR_TEXT);
        mOutput.setTextSize(11);
        mOutput.setTypeface(Typeface.MONOSPACE);
        mOutput.setTextIsSelectable(true);
        mOutput.setPadding(dp(12), dp(10), dp(12), dp(10));
        mOutput.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        outputScroll.addView(mOutput, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams outputParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        page.addView(outputScroll, outputParams);
        return page;
    }

    private void requestRun() {
        final String command = mScript.getText().toString();
        if (command.trim().isEmpty() || mRunning
                || mSnapshot == null || !mSnapshot.isReady()) {
            return;
        }
        if (mWarningAccepted) {
            execute(command);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.console_warning_title)
                .setMessage(R.string.console_warning_dialog)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.console_run, (dialog, which) -> {
                    mWarningAccepted = true;
                    execute(command);
                })
                .show();
    }

    private void applyInitialCommand(final Intent intent) {
        if (mScript == null || intent == null) {
            return;
        }
        final String command = intent.getStringExtra(EXTRA_INITIAL_COMMAND);
        if (command != null && command.length() > 0) {
            mScript.setText(command);
            mScript.setSelection(mScript.length());
        }
    }

    private void execute(final String command) {
        mRunning = true;
        mCopyText = "";
        mExecutionStatus.setText(R.string.console_running);
        mOutput.setText(R.string.console_running_output);
        updateActions();
        final long started = SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                final ShellAccess.CommandResult result =
                        ShellAccess.executeForConsole(command);
                final long duration = SystemClock.elapsedRealtime() - started;
                runOnUiThread(() -> showResult(result, duration));
            } catch (IOException | RuntimeException error) {
                final long duration = SystemClock.elapsedRealtime() - started;
                runOnUiThread(() -> showFailure(error, duration));
            }
        }, "MagicDeskConsole").start();
    }

    private void showResult(
            final ShellAccess.CommandResult result,
            final long durationMillis) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String status = getString(
                R.string.console_result,
                Integer.valueOf(result.exitCode),
                formatDuration(durationMillis));
        final String output = result.output.isEmpty()
                ? getString(R.string.console_no_output)
                : result.output;
        mExecutionStatus.setText(status);
        mExecutionStatus.setTextColor(
                result.exitCode == 0 ? COLOR_CYAN : COLOR_AMBER);
        mOutput.setText(output);
        mCopyText = status + "\n" + output;
        mRunning = false;
        updateActions();
    }

    private void showFailure(final Throwable error, final long durationMillis) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String status = getString(
                R.string.console_failed,
                formatDuration(durationMillis));
        final String output = ShellAccess.usefulMessage(error);
        mExecutionStatus.setText(status);
        mExecutionStatus.setTextColor(COLOR_AMBER);
        mOutput.setText(output);
        mCopyText = status + "\n" + output;
        mRunning = false;
        updateActions();
    }

    private void clear() {
        mScript.setText("");
        mOutput.setText(R.string.console_no_output);
        mExecutionStatus.setText(R.string.console_idle);
        mExecutionStatus.setTextColor(COLOR_CYAN);
        mCopyText = "";
        updateActions();
    }

    private void copyOutput() {
        if (mCopyText.isEmpty()) {
            return;
        }
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.console_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(
                ClipData.newPlainText("MagicDesk console output", mCopyText));
        Toast.makeText(this, R.string.console_copied, Toast.LENGTH_SHORT).show();
    }

    private void updateShellStatus() {
        if (mShellStatus == null || mSnapshot == null) {
            return;
        }
        if (mSnapshot.isReady()) {
            mShellStatus.setText(getString(
                    mSnapshot.uid == ShellAccess.ROOT_UID
                            ? R.string.console_shell_root
                            : R.string.console_shell_adb,
                    Integer.valueOf(mSnapshot.uid)));
            mShellStatus.setTextColor(COLOR_CYAN);
            return;
        }
        mShellStatus.setText(getString(
                R.string.console_shell_unavailable,
                mSnapshot.error.isEmpty()
                        ? getString(R.string.state_unavailable)
                        : mSnapshot.error));
        mShellStatus.setTextColor(COLOR_AMBER);
    }

    private void updateActions() {
        if (mRun == null || mScript == null) {
            return;
        }
        final boolean ready = mSnapshot != null && mSnapshot.isReady();
        mRun.setEnabled(!mRunning
                && ready
                && !mScript.getText().toString().trim().isEmpty());
        mClear.setEnabled(!mRunning);
        mCopy.setEnabled(!mRunning && !mCopyText.isEmpty());
        mScript.setEnabled(!mRunning);
    }

    private TextView sectionLabel(final int textResId) {
        final TextView label = new TextView(this);
        label.setText(textResId);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        return label;
    }

    private LinearLayout.LayoutParams weightedButtonParams(final int leftMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
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

    private static String formatDuration(final long durationMillis) {
        if (durationMillis < 1000L) {
            return durationMillis + " ms";
        }
        return String.format(
                Locale.US, "%.1f s", durationMillis / 1000.0d);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
