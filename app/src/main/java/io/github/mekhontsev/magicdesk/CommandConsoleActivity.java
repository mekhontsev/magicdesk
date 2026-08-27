package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
    private static final String EXTRA_BACKEND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_BACKEND";
    private static final String STATE_WORKING_DIRECTORY = "working_directory";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private ConsoleTerminalView mTerminalView;
    private FrameLayout mTerminalContainer;
    private ConsoleTerminalSession mSession;
    private TextView mShellStatus;
    private LinearLayout mToolbar;
    private ImageButton mShowToolbar;
    private ImageButton mClear;
    private ImageButton mCopy;
    private ImageButton mPaste;
    private LinearLayout.LayoutParams mTerminalParams;
    private ShellAccess.Snapshot mSnapshot;
    private DesktopExecBackend mBackend;
    private String mTerminalStatus = "";
    private String mTerminalRegistryId = "";
    private boolean mTerminalFailed;
    private boolean mPermissionRequested;
    private boolean mToolbarVisible = true;

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
        return createIntentAtDirectory(
                context, initialDirectory, DesktopExecBackend.SHELL);
    }

    static Intent createTermuxIntent(final Context context) {
        return createIntentAtDirectory(
                context,
                TermuxIntegration.HOME_DIRECTORY,
                DesktopExecBackend.TERMUX);
    }

    static Intent createTermuxIntentAtDirectory(
            final Context context, final String initialDirectory) {
        return createIntentAtDirectory(
                context, initialDirectory, DesktopExecBackend.TERMUX);
    }

    static Intent createIntentAtDirectory(
            final Context context,
            final String initialDirectory,
            final DesktopExecBackend backend) {
        return new Intent(context, CommandConsoleActivity.class)
                .putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory)
                .putExtra(EXTRA_BACKEND, backend.wireName);
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
        return createPreparedCommandIntent(
                context,
                command,
                workingDirectory,
                DesktopExecBackend.SHELL);
    }

    static Intent createPreparedCommandIntent(
            final Context context,
            final String command,
            final String workingDirectory,
            final DesktopExecBackend backend) {
        final Intent intent = workingDirectory == null
                || workingDirectory.isEmpty()
                ? createIntentAtDirectory(
                        context,
                        backend == DesktopExecBackend.TERMUX
                                ? TermuxIntegration.HOME_DIRECTORY
                                : ShellDesktopDirectory.ABSOLUTE_PATH,
                        backend)
                : createIntentAtDirectory(
                        context, workingDirectory, backend);
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
        mBackend = backend(getIntent());
        DesktopTaskDescription.apply(
                this,
                mBackend == DesktopExecBackend.TERMUX
                        ? R.string.console_termux_title
                        : R.string.console_title,
                R.drawable.ic_file_console);
        BuiltInWindowRegistry.register(this);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mSnapshot = mBackend == DesktopExecBackend.SHELL
                ? ShellAccess.currentSnapshot() : null;
        setContentView(createContentView());

        final String restoredDirectory = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_WORKING_DIRECTORY);
        final String initialDirectory = restoredDirectory == null
                ? initialDirectory(getIntent()) : restoredDirectory;
        final String startupCommand = takeAutoRunCommand(getIntent());
        final TerminalTransport.Factory transportFactory =
                terminalTransportFactory();
        mSession = new ConsoleTerminalSession(
                initialDirectory,
                mTerminalView.columns(),
                mTerminalView.rows(),
                mTerminalView.cellWidth(),
                mTerminalView.cellHeight(),
                mBackend,
                startupCommand,
                transportFactory,
                this);
        mTerminalView.attach(mSession, this);
        mTerminalView.addOnLayoutChangeListener((
                view,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom) -> maybeStartSession());
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
    public boolean dispatchKeyEvent(final KeyEvent event) {
        if (!isToggleToolbarShortcut(event)) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            setToolbarVisible(!mToolbarVisible);
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mBackend == DesktopExecBackend.SHELL) {
            ShellAccess.addStateListener(this);
        }
        maybeStartSession();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            final String[] permissions,
            final int[] grantResults) {
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);
        if (requestCode == TermuxIntegration.PERMISSION_REQUEST_CODE) {
            maybeStartSession();
            updateShellStatus();
        }
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
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        if (mBackend != DesktopExecBackend.SHELL) {
            return;
        }
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
        if (mSession != null) {
            mSession.appendLocalMessage(mTerminalStatus);
        }
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

        mToolbar = new LinearLayout(this);
        mToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mToolbar.setGravity(Gravity.CENTER_VERTICAL);

        mShellStatus = new TextView(this);
        mShellStatus.setTextColor(COLOR_CYAN);
        mShellStatus.setTextSize(12);
        mShellStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mShellStatus.setSingleLine(true);
        mToolbar.addView(mShellStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mClear = createIconButton(
                android.R.drawable.ic_menu_delete,
                R.string.console_clear,
                view -> {
                    mSession.clear();
                    mTerminalView.clearSelection();
                });
        mToolbar.addView(mClear, buttonParams());
        mCopy = createIconButton(
                R.drawable.ic_file_copy,
                R.string.console_copy_output,
                view -> copySelection());
        mToolbar.addView(mCopy, buttonParams());
        mPaste = createIconButton(
                android.R.drawable.ic_menu_set_as,
                R.string.console_paste,
                view -> pasteClipboard());
        mToolbar.addView(mPaste, buttonParams());
        final ImageButton createApplication = createIconButton(
                android.R.drawable.ic_menu_add,
                R.string.action_new_terminal_application,
                view -> createTerminalApplication());
        mToolbar.addView(createApplication, buttonParams());
        final ImageButton openFiles = createIconButton(
                R.drawable.ic_desktop_folder,
                R.string.console_open_working_directory,
                view -> openSelectedPathOrWorkingDirectory());
        mToolbar.addView(openFiles, buttonParams());
        final ImageButton hideToolbar = createIconButton(
                android.R.drawable.arrow_up_float,
                R.string.console_hide_toolbar,
                view -> setToolbarVisible(false));
        mToolbar.addView(hideToolbar, buttonParams());
        mToolbar.setVisibility(mToolbarVisible ? View.VISIBLE : View.GONE);
        page.addView(mToolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mTerminalView = new ConsoleTerminalView(this);
        mTerminalView.setOnDragListener(this::handleFileDrop);
        mTerminalContainer = new FrameLayout(this);
        mTerminalContainer.addView(mTerminalView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mShowToolbar = createIconButton(
                android.R.drawable.arrow_down_float,
                R.string.console_show_toolbar,
                view -> setToolbarVisible(true));
        mShowToolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mShowToolbar.setVisibility(View.GONE);
        final FrameLayout.LayoutParams showToolbarParams =
                new FrameLayout.LayoutParams(dp(32), dp(32));
        showToolbarParams.gravity = Gravity.TOP | Gravity.END;
        mTerminalContainer.addView(mShowToolbar, showToolbarParams);
        mTerminalParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        mTerminalParams.setMargins(
                0, mToolbarVisible ? dp(4) : 0, 0, 0);
        page.addView(mTerminalContainer, mTerminalParams);
        return page;
    }

    private void setToolbarVisible(final boolean visible) {
        if (mToolbarVisible == visible || mToolbar == null) {
            return;
        }
        mToolbarVisible = visible;
        mToolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (mShowToolbar != null) {
            mShowToolbar.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        if (mTerminalParams != null
                && mTerminalContainer != null
                && mTerminalView != null) {
            mTerminalParams.topMargin = visible ? dp(4) : 0;
            mTerminalContainer.setLayoutParams(mTerminalParams);
            mTerminalView.requestFocus();
        }
    }

    private static boolean isToggleToolbarShortcut(final KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_M) {
            return false;
        }
        final int normalized = KeyEvent.normalizeMetaState(
                event.getMetaState());
        final int commandModifiers = KeyEvent.META_CTRL_ON
                | KeyEvent.META_SHIFT_ON
                | KeyEvent.META_ALT_ON
                | KeyEvent.META_META_ON
                | KeyEvent.META_SYM_ON
                | KeyEvent.META_FUNCTION_ON;
        return (normalized & commandModifiers)
                == (KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
    }

    private void createTerminalApplication() {
        final String directory = mSession == null
                ? initialDirectory(getIntent()) : mSession.workingDirectory();
        DesktopCommandApplicationDialog.show(
                this,
                DesktopCommandApplicationDialog.InitialValues.empty(
                        directory, mBackend),
                null);
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
        final String autoRun = takeAutoRunCommand(intent);
        if (autoRun != null && !autoRun.trim().isEmpty()) {
            mSession.write(autoRun + "\r");
            mTerminalView.scrollToBottom();
        }
    }

    private TerminalTransport.Factory terminalTransportFactory() {
        if (mBackend != DesktopExecBackend.TERMUX) {
            return (directory, rows, columns, startupCommand) ->
                    ShellAccess.openPty(directory, rows, columns);
        }
        return (directory, rows, columns, startupCommand) ->
                TermuxPtyTransport.open(
                        getApplicationContext(),
                        directory,
                        rows,
                        columns,
                        startupCommand);
    }

    private static String takeAutoRunCommand(final Intent intent) {
        if (intent == null) {
            return "";
        }
        final String command = intent.getStringExtra(EXTRA_AUTO_RUN_COMMAND);
        intent.removeExtra(EXTRA_AUTO_RUN_COMMAND);
        return command == null ? "" : command;
    }

    private void maybeStartSession() {
        if (mSession == null
                || mTerminalView == null
                || !mTerminalView.isLaidOut()
                || mTerminalFailed) {
            return;
        }
        if (mBackend == DesktopExecBackend.TERMUX) {
            if (!TermuxIntegration.isInstalled(this)) {
                failTerminal(getString(R.string.console_termux_unavailable));
                return;
            }
            if (!TermuxIntegration.isAvailable(this)) {
                mTerminalStatus = getString(
                        R.string.console_termux_permission_required);
                updateShellStatus();
                if (!mPermissionRequested) {
                    mPermissionRequested = true;
                    TermuxIntegration.ensureRunCommandPermission(this);
                }
                return;
            }
        } else if (mSnapshot == null || !mSnapshot.isReady()) {
            return;
        }
        if (!mSession.isReady()) {
            mTerminalStatus = getString(R.string.console_terminal_starting);
            updateShellStatus();
            mSession.start();
        }
    }

    private void updateShellStatus() {
        if (mShellStatus == null) {
            return;
        }
        if (mBackend == DesktopExecBackend.TERMUX) {
            final String termux = getString(R.string.console_shell_termux);
            mShellStatus.setText(mTerminalStatus.isEmpty()
                    ? termux : termux + "  |  " + mTerminalStatus);
            mShellStatus.setTextColor(
                    mTerminalFailed ? COLOR_AMBER : COLOR_CYAN);
            return;
        }
        if (mSnapshot == null) {
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

    private void failTerminal(final String message) {
        if (mTerminalFailed) {
            return;
        }
        mTerminalFailed = true;
        mTerminalStatus = message;
        updateShellStatus();
        updateActions();
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

    private static DesktopExecBackend backend(final Intent intent) {
        try {
            return DesktopExecBackend.parse(intent == null
                    ? "" : intent.getStringExtra(EXTRA_BACKEND));
        } catch (IllegalArgumentException error) {
            return DesktopExecBackend.SHELL;
        }
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
