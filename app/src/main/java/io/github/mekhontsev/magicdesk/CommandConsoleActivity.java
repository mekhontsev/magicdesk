package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.function.Consumer;

public final class CommandConsoleActivity extends Activity
        implements ShellAccess.StateListener,
        ConsoleTerminalSession.Listener,
        ConsoleTerminalView.ClipboardActions {
    private static final String EXTRA_INITIAL_DIRECTORY =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_DIRECTORY";
    private static final String EXTRA_AUTO_RUN_COMMAND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_AUTO_RUN";
    private static final String EXTRA_TERMINAL_ID =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_TERMINAL_ID";
    private static final String STATE_WORKING_DIRECTORY = "working_directory";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private ConsoleTerminalView mTerminalView;
    private ConsoleTerminalSession mSession;
    private TextView mShellStatus;
    private ImageButton mClear;
    private ImageButton mCopy;
    private ImageButton mPaste;
    private LinearLayout.LayoutParams mTerminalParams;
    private ShellAccess.Snapshot mSnapshot;
    private String mPendingAutoRunCommand;
    private String mTerminalStatus = "";
    private String mTerminalRegistryId = "";
    private boolean mTerminalFailed;

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

    static Intent createCommandIntent(
            final Context context, final String command) {
        return createPreparedCommandIntent(
                context,
                DesktopExecCommand.prepare(command),
                ShellDesktopDirectory.ABSOLUTE_PATH);
    }

    static Intent createPreparedCommandIntent(
            final Context context,
            final String command,
            final String workingDirectory) {
        final Intent intent = workingDirectory == null
                || workingDirectory.isEmpty()
                ? createIntent(context)
                : createIntentAtDirectory(context, workingDirectory);
        return intent.putExtra(
                EXTRA_AUTO_RUN_COMMAND,
                DesktopExecCommand.normalize(command));
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

    static Intent withTerminalId(final Intent intent, final String id) {
        return intent.putExtra(EXTRA_TERMINAL_ID, id);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DesktopTaskDescription.apply(
                this,
                R.string.console_title,
                R.drawable.ic_file_console);
        BuiltInWindowRegistry.register(this);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mSnapshot = ShellAccess.currentSnapshot();
        setContentView(createContentView());

        final String restoredDirectory = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_WORKING_DIRECTORY);
        final String initialDirectory = restoredDirectory == null
                ? initialDirectory(getIntent()) : restoredDirectory;
        mSession = new ConsoleTerminalSession(
                initialDirectory,
                mTerminalView.columns(),
                mTerminalView.rows(),
                mTerminalView.cellWidth(),
                mTerminalView.cellHeight(),
                this);
        mTerminalView.attach(mSession, this);
        mTerminalRegistryId = ConsoleTerminalRegistry.register(
                this,
                mSession,
                mTerminalView,
                getIntent().getStringExtra(EXTRA_TERMINAL_ID));
        applyLaunchRequest(getIntent(), false);
        updateShellStatus();
        updateActions();
        mTerminalView.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                mTerminalView.requestFocus();
                maybeStartSession();
            }
        });
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyLaunchRequest(intent, true);
        maybeRunPendingCommand();
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
        if (mSession != null) {
            state.putString(
                    STATE_WORKING_DIRECTORY,
                    mSession.workingDirectory());
        }
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
        refreshWorkingDirectory(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BuiltInWindowRegistry.unregister(this);
        ConsoleTerminalRegistry.unregister(mTerminalRegistryId);
        if (mSession != null) {
            mSession.close();
        }
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
            maybeStartSession();
        });
    }

    @Override
    public void onScreenChanged() {
        if (mTerminalView != null) {
            mTerminalView.onTerminalChanged();
        }
        updateActions();
    }

    @Override
    public void onReady() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mTerminalStatus = getString(R.string.console_terminal_ready);
        updateShellStatus();
        updateActions();
        maybeRunPendingCommand();
    }

    @Override
    public void onFinished() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!mTerminalFailed) {
            finish();
        }
    }

    @Override
    public void onError(final IOException error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mTerminalFailed = true;
        mTerminalStatus = getString(
                R.string.console_failed,
                ShellAccess.usefulMessage(error));
        updateShellStatus();
        updateActions();
    }

    @Override
    public void onTitleChanged(final String title) {
        // Android owns the native caption title. Keep OSC titles internal.
    }

    @Override
    public void onCopyRequested(final String text) {
        copyText(text);
    }

    @Override
    public void onPasteRequested() {
        pasteClipboard();
    }

    @Override
    public void onBell() {
        if (mTerminalView != null) {
            mTerminalView.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS);
        }
    }

    @Override
    public void copySelection() {
        if (mSession == null || mTerminalView == null) {
            return;
        }
        final String selected = mTerminalView.selectedText();
        copyText(selected.isEmpty() ? mSession.transcript() : selected);
    }

    @Override
    public void pasteClipboard() {
        if (mSession == null) {
            return;
        }
        final ClipboardManager clipboard = getSystemService(
                ClipboardManager.class);
        final ClipData clip = clipboard == null
                ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        final CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null) {
            mSession.paste(text.toString());
            mTerminalView.scrollToBottom();
        }
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(6), dp(8), dp(6));
        SystemBarInsets.addToPadding(page);
        page.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mShellStatus = new TextView(this);
        mShellStatus.setTextColor(COLOR_CYAN);
        mShellStatus.setTextSize(12);
        mShellStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mShellStatus.setSingleLine(true);
        header.addView(mShellStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mClear = createIconButton(
                android.R.drawable.ic_menu_delete,
                R.string.console_clear,
                view -> {
                    mSession.clear();
                    mTerminalView.clearSelection();
                });
        header.addView(mClear, buttonParams());
        mCopy = createIconButton(
                R.drawable.ic_file_copy,
                R.string.console_copy_output,
                view -> copySelection());
        header.addView(mCopy, buttonParams());
        mPaste = createIconButton(
                android.R.drawable.ic_menu_set_as,
                R.string.console_paste,
                view -> pasteClipboard());
        header.addView(mPaste, buttonParams());
        final ImageButton openFiles = createIconButton(
                R.drawable.ic_desktop_folder,
                R.string.console_open_working_directory,
                view -> openSelectedPathOrWorkingDirectory());
        header.addView(openFiles, buttonParams());
        page.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mTerminalView = new ConsoleTerminalView(this);
        mTerminalView.setOnDragListener(this::handleFileDrop);
        mTerminalParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        mTerminalParams.setMargins(0, dp(4), 0, taskbarInset());
        page.addView(mTerminalView, mTerminalParams);
        return page;
    }

    private void applyLaunchRequest(
            final Intent intent, final boolean applyDirectory) {
        if (intent == null) {
            return;
        }
        if (applyDirectory && intent.hasExtra(EXTRA_INITIAL_DIRECTORY)) {
            final String directory = intent.getStringExtra(
                    EXTRA_INITIAL_DIRECTORY);
            if (directory != null && directory.startsWith("/")) {
                mSession.write("cd -- "
                        + ShellCommandLine.quote(directory) + "\r");
            }
        }
        final String autoRun = intent.getStringExtra(EXTRA_AUTO_RUN_COMMAND);
        intent.removeExtra(EXTRA_AUTO_RUN_COMMAND);
        if (autoRun != null && !autoRun.trim().isEmpty()) {
            mPendingAutoRunCommand = autoRun;
        }
    }

    private void maybeStartSession() {
        if (mSession == null || mSnapshot == null
                || !mSnapshot.isReady() || mTerminalFailed) {
            return;
        }
        if (!mSession.isReady()) {
            mTerminalStatus = getString(R.string.console_terminal_starting);
            updateShellStatus();
            mSession.start();
        }
    }

    private void maybeRunPendingCommand() {
        if (mPendingAutoRunCommand == null
                || mSession == null
                || !mSession.isReady()) {
            return;
        }
        final String command = mPendingAutoRunCommand;
        mPendingAutoRunCommand = null;
        mSession.write(command + "\r");
        mTerminalView.scrollToBottom();
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
            mShellStatus.setText(mTerminalStatus.isEmpty()
                    ? shell : shell + "  |  " + mTerminalStatus);
            mShellStatus.setTextColor(
                    mTerminalFailed ? COLOR_AMBER : COLOR_CYAN);
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
        if (mClear == null || mCopy == null || mPaste == null) {
            return;
        }
        final boolean ready = mSession != null && mSession.isReady();
        mClear.setEnabled(ready);
        mCopy.setEnabled(mSession != null);
        mPaste.setEnabled(ready);
    }

    private boolean handleFileDrop(final View view, final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return payload != null;
            case DragEvent.ACTION_DROP:
                if (payload == null || mSession == null) {
                    return false;
                }
                mSession.write(ConsolePathText.quotePaths(
                        payload.absolutePaths) + " ");
                mTerminalView.requestFocus();
                return true;
            default:
                return payload != null;
        }
    }

    private void openSelectedPathOrWorkingDirectory() {
        final String selected = mTerminalView.selectedText();
        if (selected.isEmpty()) {
            refreshWorkingDirectory(directory -> openFilesAt(
                    createFilesDirectoryInfo(directory)));
            return;
        }
        mSession.requestWorkingDirectory((directory, lookupError) -> {
            final String resolved;
            try {
                resolved = ConsolePathText.resolveSelectedPath(
                        directory, selected);
            } catch (IllegalArgumentException error) {
                showPathUnavailable(error);
                return;
            }
            new Thread(
                    () -> loadAndOpenPath(resolved),
                    "MagicDeskConsolePath").start();
        });
    }

    private void loadAndOpenPath(final String path) {
        try {
            final ShellFileInfo file = ShellAccess.getShellFileInfo(path);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    openFilesAt(file);
                }
            });
        } catch (IOException | RuntimeException error) {
            runOnUiThread(() -> showPathUnavailable(error));
        }
    }

    private void refreshWorkingDirectory(final Consumer<String> action) {
        if (mSession == null) {
            return;
        }
        mSession.requestWorkingDirectory((directory, error) -> {
            if (action != null && !isFinishing() && !isDestroyed()) {
                action.accept(directory);
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

    private void showPathUnavailable(final Throwable error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Toast.makeText(
                this,
                getString(
                        R.string.console_path_unavailable,
                        ShellAccess.usefulMessage(error)),
                Toast.LENGTH_SHORT).show();
    }

    private void copyText(final String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final ClipboardManager clipboard = getSystemService(
                ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.console_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MagicDesk console output", text));
        Toast.makeText(this, R.string.console_copied,
                Toast.LENGTH_SHORT).show();
    }

    private void updateTaskbarInset(final boolean inMultiWindowMode) {
        if (mTerminalParams == null) {
            return;
        }
        mTerminalParams.bottomMargin = taskbarInset(inMultiWindowMode);
        mTerminalView.setLayoutParams(mTerminalParams);
    }

    private int taskbarInset() {
        return taskbarInset(isInMultiWindowMode());
    }

    private int taskbarInset(final boolean inMultiWindowMode) {
        final Display display = getDisplay();
        return display != null
                        && display.getDisplayId() != Display.DEFAULT_DISPLAY
                        && !inMultiWindowMode
                ? dp(DesktopShellActivity.TASKBAR_HEIGHT_DP) : 0;
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

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private static String initialDirectory(final Intent intent) {
        if (intent == null) {
            return ShellDesktopDirectory.ABSOLUTE_PATH;
        }
        final String directory = intent.getStringExtra(EXTRA_INITIAL_DIRECTORY);
        return directory == null || !directory.startsWith("/")
                ? ShellDesktopDirectory.ABSOLUTE_PATH : directory;
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
                DocumentsContract.Document.MIME_TYPE_DIR,
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

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
