package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Display;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CommandConsoleActivity extends Activity
        implements ShellAccess.StateListener {
    private static final String EXTRA_INITIAL_DIRECTORY =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_DIRECTORY";
    private static final String EXTRA_AUTO_RUN_COMMAND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_AUTO_RUN";
    private static final String STATE_WORKING_DIRECTORY = "working_directory";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int ACTION_OPEN_SELECTED_PATH = 1;
    private static final int COMPLETION_PAGE_SIZE = 500;

    private final ConsoleCommandHistory mHistory =
            new ConsoleCommandHistory();
    private final SpannableStringBuilder mTranscript =
            new SpannableStringBuilder();
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskConsoleWork");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicInteger mCompletionGeneration = new AtomicInteger();
    private final Object mStreamOutputLock = new Object();
    private final StringBuilder mPendingStreamOutput = new StringBuilder();

    private EditText mCommand;
    private TextView mWorkingDirectory;
    private TextView mShellStatus;
    private EditText mOutput;
    private ImageButton mRun;
    private ImageButton mClear;
    private ImageButton mCopy;
    private LinearLayout mCommandArea;
    private LinearLayout mCommandLine;
    private LinearLayout.LayoutParams mCommandAreaParams;
    private ConsoleShellSession mSession;
    private ShellAccess.Snapshot mSnapshot;
    private String mPendingAutoRunCommand;
    private String mExecutionStatus = "";
    private int mExecutionStatusColor = COLOR_CYAN;
    private volatile int mExecutionGeneration;
    private boolean mRunning;
    private boolean mStreamOutputPosted;
    private volatile boolean mStopRequested;

    static Intent createIntent(final Context context) {
        return new Intent(context, CommandConsoleActivity.class).putExtra(
                EXTRA_INITIAL_DIRECTORY,
                ShellDesktopDirectory.ABSOLUTE_PATH);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.consoleTarget();
    }

    static Intent createIntentAtDirectory(
            final Context context, final String initialDirectory) {
        return new Intent(context, CommandConsoleActivity.class).putExtra(
                EXTRA_INITIAL_DIRECTORY,
                initialDirectory);
    }

    static Intent createScriptIntent(
            final Context context, final String absolutePath) {
        return new Intent(context, CommandConsoleActivity.class)
                .putExtra(
                        EXTRA_INITIAL_DIRECTORY,
                        ShellScriptLauncher.workingDirectory(absolutePath))
                .putExtra(
                        EXTRA_AUTO_RUN_COMMAND,
                        ShellScriptLauncher.command(absolutePath));
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String restoredDirectory = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_WORKING_DIRECTORY);
        mSession = new ConsoleShellSession(restoredDirectory == null
                ? initialDirectory(getIntent()) : restoredDirectory);
        DesktopTaskDescription.apply(
                this,
                R.string.console_title,
                R.drawable.ic_file_console);
        BuiltInWindowRegistry.register(this);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mSnapshot = ShellAccess.currentSnapshot();
        setContentView(createContentView());
        applyLaunchRequest(getIntent(), savedInstanceState == null);
        updateWorkingDirectory();
        updateShellStatus();
        updateActions();
        maybeRunPendingCommand();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyLaunchRequest(intent, true);
        updateWorkingDirectory();
        maybeRunPendingCommand();
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
        state.putString(
                STATE_WORKING_DIRECTORY,
                mSession.workingDirectory());
        super.onSaveInstanceState(state);
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
    protected void onDestroy() {
        BuiltInWindowRegistry.unregister(this);
        if (mSession != null) {
            mSession.close();
        }
        mWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onMultiWindowModeChanged(
            final boolean inMultiWindowMode,
            final Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfig);
        updateTaskbarInset(inMultiWindowMode);
    }

    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTaskbarInset(isInMultiWindowMode());
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
            maybeRunPendingCommand();
        });
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final Display display = getDisplay();
        final int displayId = display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
        page.setPadding(dp(10), dp(8), dp(10), dp(8));
        SystemBarInsets.addToPadding(page);
        page.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mShellStatus = new TextView(this);
        mShellStatus.setTextColor(COLOR_CYAN);
        mShellStatus.setTextSize(12);
        mShellStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(mShellStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mClear = createIconButton(
                android.R.drawable.ic_menu_delete,
                R.string.console_clear,
                view -> clear());
        header.addView(mClear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mCopy = createIconButton(
                R.drawable.ic_file_copy,
                R.string.console_copy_output,
                view -> copyOutput());
        header.addView(mCopy, new LinearLayout.LayoutParams(dp(44), dp(44)));
        final ImageButton openFiles = createIconButton(
                R.drawable.ic_desktop_folder,
                R.string.console_open_working_directory,
                view -> openWorkingDirectory());
        header.addView(openFiles, new LinearLayout.LayoutParams(dp(44), dp(44)));
        final LinearLayout.LayoutParams headerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(6));
        page.addView(header, headerParams);

        mOutput = new EditText(this);
        mOutput.setTextColor(COLOR_TEXT);
        mOutput.setTextSize(12);
        mOutput.setTypeface(Typeface.MONOSPACE);
        mOutput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        mOutput.setKeyListener(null);
        mOutput.setTextIsSelectable(true);
        mOutput.setCursorVisible(false);
        mOutput.setShowSoftInputOnFocus(false);
        mOutput.setVerticalScrollBarEnabled(true);
        mOutput.setCustomSelectionActionModeCallback(
                new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(
                            final ActionMode mode, final Menu menu) {
                        menu.add(
                                Menu.NONE,
                                ACTION_OPEN_SELECTED_PATH,
                                Menu.NONE,
                                R.string.console_reveal_selected_path);
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(
                            final ActionMode mode, final Menu menu) {
                        return false;
                    }

                    @Override
                    public boolean onActionItemClicked(
                            final ActionMode mode, final MenuItem item) {
                        if (item.getItemId() != ACTION_OPEN_SELECTED_PATH) {
                            return false;
                        }
                        revealSelectedPath();
                        mode.finish();
                        return true;
                    }

                    @Override
                    public void onDestroyActionMode(final ActionMode mode) {
                    }
                });
        mOutput.setGravity(Gravity.TOP | Gravity.START);
        mOutput.setPadding(dp(8), dp(6), dp(8), dp(6));
        mOutput.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        final LinearLayout.LayoutParams outputParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        page.addView(mOutput, outputParams);

        mCommandArea = new LinearLayout(this);
        mCommandArea.setOrientation(LinearLayout.VERTICAL);

        mWorkingDirectory = new TextView(this);
        mWorkingDirectory.setTextColor(COLOR_CYAN);
        mWorkingDirectory.setTextSize(11);
        mWorkingDirectory.setTypeface(Typeface.MONOSPACE);
        mWorkingDirectory.setSingleLine(true);
        mWorkingDirectory.setEllipsize(TextUtils.TruncateAt.START);
        mWorkingDirectory.setPadding(dp(4), dp(3), dp(4), dp(2));
        mCommandArea.addView(
                mWorkingDirectory,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        mCommandLine = new LinearLayout(this);
        mCommandLine.setGravity(Gravity.BOTTOM);
        final TextView prompt = new TextView(this);
        prompt.setText(R.string.console_prompt);
        prompt.setTextColor(COLOR_CYAN);
        prompt.setTextSize(16);
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        prompt.setGravity(Gravity.CENTER);
        mCommandLine.addView(prompt, new LinearLayout.LayoutParams(
                dp(26), dp(44)));

        mCommand = new EditText(this);
        mCommand.setTextColor(COLOR_TEXT);
        mCommand.setHintTextColor(COLOR_MUTED);
        mCommand.setHint(R.string.console_command_hint);
        mCommand.setTextSize(13);
        mCommand.setTypeface(Typeface.MONOSPACE);
        mCommand.setGravity(Gravity.TOP | Gravity.START);
        mCommand.setSingleLine(false);
        mCommand.setMinLines(1);
        mCommand.setMaxLines(5);
        mCommand.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mCommand.setImeOptions(
                EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mCommand.setPadding(dp(9), dp(7), dp(9), dp(7));
        mCommand.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_MUTED));
        mCommand.addTextChangedListener(new TextWatcher() {
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
        mCommand.setOnKeyListener(this::handleCommandKey);
        mCommand.setOnDragListener(this::handleFileDrop);
        mCommand.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null) {
                return false;
            }
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND) {
                requestRun();
                return true;
            }
            return false;
        });
        final LinearLayout.LayoutParams commandParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        mCommandLine.addView(mCommand, commandParams);

        mRun = createIconButton(
                android.R.drawable.ic_media_play,
                R.string.console_run,
                view -> requestRunOrStop());
        mCommandLine.addView(
                mRun, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mCommandArea.addView(
                mCommandLine,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        mCommandAreaParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        mCommandAreaParams.setMargins(
                0,
                dp(6),
                0,
                displayId != Display.DEFAULT_DISPLAY
                                && !isInMultiWindowMode()
                        ? dp(DesktopShellActivity.TASKBAR_HEIGHT_DP) : 0);
        page.addView(mCommandArea, mCommandAreaParams);
        return page;
    }

    private void requestRun() {
        final String command = mCommand.getText().toString();
        if (command.trim().isEmpty() || mRunning
                || mSnapshot == null || !mSnapshot.isReady()) {
            return;
        }
        execute(command);
    }

    private void requestRunOrStop() {
        if (mRunning) {
            requestStop();
        } else {
            requestRun();
        }
    }

    private void requestStop() {
        if (!mRunning || mStopRequested) {
            return;
        }
        mStopRequested = true;
        mExecutionStatus = getString(R.string.console_stopping);
        mExecutionStatusColor = COLOR_AMBER;
        updateShellStatus();
        updateActions();
        mSession.cancelCurrentCommand();
    }

    private static String initialDirectory(final Intent intent) {
        if (intent == null) {
            return ShellDesktopDirectory.ABSOLUTE_PATH;
        }
        final String directory = intent.getStringExtra(EXTRA_INITIAL_DIRECTORY);
        return directory == null || directory.isEmpty()
                ? ShellDesktopDirectory.ABSOLUTE_PATH : directory;
    }

    private void applyLaunchRequest(
            final Intent intent, final boolean applyDirectory) {
        if (intent == null) {
            return;
        }
        if (applyDirectory && intent.hasExtra(EXTRA_INITIAL_DIRECTORY)) {
            final String directory = intent.getStringExtra(
                    EXTRA_INITIAL_DIRECTORY);
            if (directory != null && !directory.isEmpty()) {
                mSession.setWorkingDirectory(directory);
            }
        }
        final String autoRun = intent.getStringExtra(EXTRA_AUTO_RUN_COMMAND);
        intent.removeExtra(EXTRA_AUTO_RUN_COMMAND);
        if (autoRun != null && !autoRun.trim().isEmpty()) {
            mPendingAutoRunCommand = autoRun;
        }
    }

    private void maybeRunPendingCommand() {
        if (mPendingAutoRunCommand == null
                || mPendingAutoRunCommand.isEmpty()
                || mRunning
                || mSnapshot == null
                || !mSnapshot.isReady()) {
            return;
        }
        final String command = mPendingAutoRunCommand;
        mPendingAutoRunCommand = null;
        execute(command);
    }

    private void execute(final String command) {
        if (ConsoleShellSession.isExitCommand(command)) {
            finish();
            return;
        }
        mCompletionGeneration.incrementAndGet();
        final int executionGeneration = ++mExecutionGeneration;
        mStopRequested = false;
        mRunning = true;
        mHistory.record(command);
        appendCommand(command);
        mCommand.setText("");
        final InputMethodManager inputMethod =
                getSystemService(InputMethodManager.class);
        if (inputMethod != null) {
            inputMethod.hideSoftInputFromWindow(
                    mCommand.getWindowToken(), 0);
        }
        mExecutionStatus = getString(R.string.console_running);
        mExecutionStatusColor = COLOR_CYAN;
        updateShellStatus();
        updateActions();
        final long started = SystemClock.elapsedRealtime();
        mWorker.execute(() -> {
            if (mStopRequested) {
                final long duration = SystemClock.elapsedRealtime() - started;
                runOnUiThread(() -> showStopped(duration));
                return;
            }
            try {
                final ConsoleShellSession.ExecutionResult result =
                        mSession.execute(
                                command,
                                output -> queueStreamOutput(
                                        executionGeneration, output));
                final long duration = SystemClock.elapsedRealtime() - started;
                runOnUiThread(() -> {
                    if (mStopRequested) {
                        showStopped(duration);
                    } else {
                        showResult(result, duration);
                    }
                });
            } catch (IOException | RuntimeException error) {
                final long duration = SystemClock.elapsedRealtime() - started;
                runOnUiThread(() -> {
                    if (mStopRequested) {
                        showStopped(duration);
                    } else {
                        showFailure(error, duration);
                    }
                });
            }
        });
    }

    private void showResult(
            final ConsoleShellSession.ExecutionResult result,
            final long durationMillis) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String status = getString(
                R.string.console_result,
                Integer.valueOf(result.exitCode),
                formatDuration(durationMillis));
        mExecutionStatus = status;
        mExecutionStatusColor = result.exitCode == 0 ? COLOR_CYAN : COLOR_AMBER;
        mRunning = false;
        updateWorkingDirectory();
        updateShellStatus();
        updateActions();
        mCommand.requestFocus();
        maybeRunPendingCommand();
    }

    private void showStopped(final long durationMillis) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mExecutionStatus = getString(
                R.string.console_stopped,
                formatDuration(durationMillis));
        mExecutionStatusColor = COLOR_AMBER;
        mRunning = false;
        mStopRequested = false;
        updateWorkingDirectory();
        updateShellStatus();
        updateActions();
        mCommand.requestFocus();
        maybeRunPendingCommand();
    }

    private void showFailure(final Throwable error, final long durationMillis) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String status = getString(
                R.string.console_failed,
                formatDuration(durationMillis));
        final String output = ShellAccess.usefulMessage(error);
        appendResult(output, COLOR_AMBER);
        mExecutionStatus = status;
        mExecutionStatusColor = COLOR_AMBER;
        mRunning = false;
        updateShellStatus();
        updateActions();
        mCommand.requestFocus();
    }

    private void clear() {
        mTranscript.clear();
        mOutput.setText(mTranscript);
        mExecutionStatus = getString(R.string.console_idle);
        mExecutionStatusColor = COLOR_CYAN;
        updateShellStatus();
        updateActions();
    }

    private void copyOutput() {
        if (mTranscript.length() == 0) {
            return;
        }
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.console_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        "MagicDesk console output", mTranscript.toString()));
        Toast.makeText(this, R.string.console_copied, Toast.LENGTH_SHORT).show();
    }

    private void updateShellStatus() {
        if (mShellStatus == null || mSnapshot == null) {
            return;
        }
        if (mSnapshot.isReady()) {
            final String shell = getString(
                    mSnapshot.uid == ShellAccess.ROOT_UID
                            ? R.string.console_shell_root
                            : R.string.console_shell_adb,
                    Integer.valueOf(mSnapshot.uid));
            mShellStatus.setText(mExecutionStatus.isEmpty()
                    ? shell : shell + "  |  " + mExecutionStatus);
            mShellStatus.setTextColor(mExecutionStatusColor);
            return;
        }
        mShellStatus.setText(getString(
                R.string.console_shell_unavailable,
                mSnapshot.error.isEmpty()
                        ? getString(R.string.state_unavailable)
                        : mSnapshot.error));
        mShellStatus.setTextColor(COLOR_AMBER);
    }

    private void updateWorkingDirectory() {
        if (mWorkingDirectory != null && mSession != null) {
            mWorkingDirectory.setText(mSession.workingDirectory());
        }
    }

    private void updateActions() {
        if (mRun == null || mCommand == null) {
            return;
        }
        final boolean ready = mSnapshot != null && mSnapshot.isReady();
        mRun.setImageResource(mRunning
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        final int runAction = mRunning
                ? R.string.console_stop : R.string.console_run;
        mRun.setContentDescription(getString(runAction));
        mRun.setTooltipText(getString(runAction));
        mRun.setEnabled(mRunning
                ? !mStopRequested
                : ready
                        && !mCommand.getText().toString().trim().isEmpty());
        mClear.setEnabled(!mRunning);
        mCopy.setEnabled(!mRunning && mTranscript.length() > 0);
        mCommand.setEnabled(ready && !mRunning);
    }

    private boolean handleCommandKey(
            final View view, final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isShiftPressed()) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                requestRun();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                requestCompletion();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN
                || !event.hasNoModifiers()) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                && shouldNavigatePrevious()) {
            return showHistoryEntry(mHistory.previous(
                    mCommand.getText().toString()));
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                && shouldNavigateNext()) {
            return showHistoryEntry(mHistory.next());
        }
        return false;
    }

    private boolean handleFileDrop(final View view, final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return payload != null;
            case DragEvent.ACTION_DROP:
                if (payload == null) {
                    return false;
                }
                insertCommandText(ConsolePathText.quotePaths(
                        payload.absolutePaths));
                return true;
            default:
                return payload != null;
        }
    }

    private void insertCommandText(final String insertion) {
        final String current = mCommand.getText().toString();
        final int start = Math.max(0, mCommand.getSelectionStart());
        final int end = Math.max(0, mCommand.getSelectionEnd());
        final String updated = ConsolePathText.insert(
                current, start, end, insertion);
        mCommand.setText(updated);
        final int insertedAt = updated.indexOf(insertion,
                Math.min(start, updated.length()));
        mCommand.setSelection(insertedAt < 0
                ? updated.length() : insertedAt + insertion.length());
        mCommand.requestFocus();
    }

    private void requestCompletion() {
        if (mRunning || !ShellAccess.isReady()) {
            return;
        }
        final String command = mCommand.getText().toString();
        final int cursor = Math.max(0, mCommand.getSelectionStart());
        if (cursor != mCommand.getSelectionEnd()) {
            return;
        }
        final ConsolePathText.CompletionRequest request =
                ConsolePathText.completionRequest(
                        command, cursor, mSession.workingDirectory());
        if (request == null) {
            return;
        }
        final int generation = mCompletionGeneration.incrementAndGet();
        mWorker.execute(() -> {
            try {
                final List<ShellFileInfo> entries = new ArrayList<>();
                final List<String> parentPaths = request.commandName
                        ? mSession.commandSearchPath()
                        : Collections.singletonList(request.parentPath);
                for (final String parentPath : parentPaths) {
                    try {
                        addCompletionEntries(
                                generation, parentPath, entries);
                    } catch (IOException error) {
                        if (!request.commandName) {
                            throw error;
                        }
                        // One inaccessible PATH directory must not disable Tab.
                    }
                    if (generation != mCompletionGeneration.get()) {
                        return;
                    }
                }
                final ConsolePathText.CompletionResult result =
                        ConsolePathText.complete(request, entries);
                runOnUiThread(() -> applyCompletion(
                        generation, command, request, result));
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (generation == mCompletionGeneration.get()) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.console_completion_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void addCompletionEntries(
            final int generation,
            final String parentPath,
            final List<ShellFileInfo> entries) throws IOException {
        int offset = 0;
        ShellFilePage page;
        do {
            page = ShellAccess.listShellDirectory(
                    parentPath,
                    offset,
                    COMPLETION_PAGE_SIZE,
                    true,
                    ShellFileSystem.SORT_NAME,
                    true);
            Collections.addAll(entries, page.entries);
            offset = page.nextOffset;
        } while (!page.complete
                && generation == mCompletionGeneration.get());
    }

    private void applyCompletion(
            final int generation,
            final String original,
            final ConsolePathText.CompletionRequest request,
            final ConsolePathText.CompletionResult result) {
        if (generation != mCompletionGeneration.get()
                || result == null
                || !original.equals(mCommand.getText().toString())) {
            return;
        }
        if (result.replacement != null) {
            final String completed = original.substring(0, request.tokenStart)
                    + result.replacement
                    + original.substring(request.tokenEnd);
            mCommand.setText(completed);
            mCommand.setSelection(request.tokenStart
                    + result.replacement.length());
            return;
        }
        if (!result.alternatives.isEmpty()) {
            final int shown = Math.min(6, result.alternatives.size());
            Toast.makeText(
                    this,
                    TextUtils.join("  ", result.alternatives.subList(0, shown)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openWorkingDirectory() {
        openFilesAt(CommandConsoleActivity.createFilesDirectoryInfo(
                mSession.workingDirectory()));
    }

    private void revealSelectedPath() {
        final int start = Math.max(0, mOutput.getSelectionStart());
        final int end = Math.max(0, mOutput.getSelectionEnd());
        if (start == end) {
            return;
        }
        final String selected = mOutput.getText().subSequence(
                Math.min(start, end), Math.max(start, end)).toString();
        final String path;
        try {
            path = ConsolePathText.resolveSelectedPath(
                    mSession.workingDirectory(), selected);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, R.string.console_path_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        mWorker.execute(() -> {
            try {
                final ShellFileInfo file = ShellAccess.getShellFileInfo(path);
                runOnUiThread(() -> openFilesAt(file));
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(
                                R.string.console_path_unavailable,
                                ShellAccess.usefulMessage(error)),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openFilesAt(final ShellFileInfo file) {
        BuiltInWindowLauncher.launch(
                this,
                FileManagerActivity.createRevealIntent(this, file),
                FileManagerActivity.launchTarget(this),
                error -> {
                    if (error != null) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.console_files_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static ShellFileInfo createFilesDirectoryInfo(
            final String absolutePath) {
        final String normalized = ShellFilePathPolicy
                .normalizeShellAbsolute(absolutePath);
        final String name = "/".equals(normalized)
                ? "/" : normalized.substring(normalized.lastIndexOf('/') + 1);
        return new ShellFileInfo(
                normalized,
                name,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                "",
                0L,
                0L,
                0L,
                0L,
                ShellAccess.SHELL_UID,
                ShellAccess.SHELL_UID,
                0,
                true,
                false,
                true,
                false,
                true,
                false);
    }

    private boolean shouldNavigatePrevious() {
        return mCommand.getText().toString().indexOf('\n') < 0
                || (mCommand.getSelectionStart() == 0
                        && mCommand.getSelectionEnd() == 0);
    }

    private boolean shouldNavigateNext() {
        return mCommand.getText().toString().indexOf('\n') < 0
                || (mCommand.getSelectionStart() == mCommand.length()
                        && mCommand.getSelectionEnd() == mCommand.length());
    }

    private boolean showHistoryEntry(final String command) {
        if (command == null) {
            return false;
        }
        mCommand.setText(command);
        mCommand.setSelection(mCommand.length());
        return true;
    }

    private void appendCommand(final String command) {
        if (mTranscript.length() > 0) {
            mTranscript.append('\n');
        }
        final int start = mTranscript.length();
        mTranscript.append(mSession.workingDirectory());
        mTranscript.append(' ');
        mTranscript.append(getString(R.string.console_prompt));
        mTranscript.append(' ');
        mTranscript.append(command.replace("\n", "\n> "));
        mTranscript.append('\n');
        mTranscript.setSpan(
                new ForegroundColorSpan(COLOR_CYAN),
                start,
                mTranscript.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        showTranscript();
    }

    private void appendResult(final String output, final int color) {
        if (!output.isEmpty()) {
            final int start = mTranscript.length();
            mTranscript.append(output);
            if (mTranscript.charAt(mTranscript.length() - 1) != '\n') {
                mTranscript.append('\n');
            }
            mTranscript.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    mTranscript.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        showTranscript();
    }

    private void queueStreamOutput(
            final int executionGeneration, final String output) {
        if (output == null || output.isEmpty()
                || executionGeneration != mExecutionGeneration) {
            return;
        }
        synchronized (mStreamOutputLock) {
            mPendingStreamOutput.append(output);
            if (mStreamOutputPosted) {
                return;
            }
            mStreamOutputPosted = true;
        }
        mOutput.post(this::drainStreamOutput);
    }

    private void drainStreamOutput() {
        final String output;
        synchronized (mStreamOutputLock) {
            output = mPendingStreamOutput.toString();
            mPendingStreamOutput.setLength(0);
            mStreamOutputPosted = false;
        }
        if (output.isEmpty() || isFinishing() || isDestroyed()) {
            return;
        }
        final int start = mTranscript.length();
        mTranscript.append(output);
        mTranscript.setSpan(
                new ForegroundColorSpan(COLOR_TEXT),
                start,
                mTranscript.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        showTranscript();
    }

    private void showTranscript() {
        mOutput.setText(mTranscript);
        mOutput.post(() -> mOutput.setSelection(mOutput.length()));
    }

    private void updateTaskbarInset(final boolean inMultiWindowMode) {
        if (mCommandAreaParams == null) {
            return;
        }
        final Display display = getDisplay();
        mCommandAreaParams.bottomMargin = display != null
                        && display.getDisplayId() != Display.DEFAULT_DISPLAY
                        && !inMultiWindowMode
                ? dp(DesktopShellActivity.TASKBAR_HEIGHT_DP) : 0;
        if (mCommandArea != null) {
            mCommandArea.setLayoutParams(mCommandAreaParams);
        }
    }

    private ImageButton createIconButton(
            final int drawableResId,
            final int descriptionResId,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(drawableResId);
        button.setImageTintList(new ColorStateList(
                new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[]{COLOR_MUTED, COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(getString(descriptionResId));
        button.setTooltipText(getString(descriptionResId));
        button.setOnClickListener(listener);
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
