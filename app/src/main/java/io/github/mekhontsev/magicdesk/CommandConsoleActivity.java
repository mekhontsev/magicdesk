package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;

public final class CommandConsoleActivity extends Activity
        implements ShellAccess.StateListener,
        ConsoleTerminalSession.Listener {
    private static final String EXTRA_INITIAL_DIRECTORY =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_DIRECTORY";
    private static final String EXTRA_AUTO_RUN_COMMAND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_AUTO_RUN";
    private static final String EXTRA_TERMINAL_ID =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_TERMINAL_ID";
    private static final String EXTRA_BACKEND =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_BACKEND";
    private static final String EXTRA_ATTACH_ONLY =
            "io.github.mekhontsev.magicdesk.extra.CONSOLE_ATTACH_ONLY";
    private static final String EXTRA_TMUX_SESSION = "tmux_session";
    private static final String EXTRA_TMUX_CREATED = "tmux_created";
    private static final String STATE_WORKING_DIRECTORY = "working_directory";
    private static final String STATE_FONT_SIZE = "font_size_sp";

    private ConsoleTerminalView mTerminalView;
    private ConsoleTerminalSession mSession;
    private ConsoleTerminalActions mActions;
    private ConsoleTerminalWindow mWindow;
    private ShellAccess.Snapshot mSnapshot;
    private DesktopExecBackend mBackend;
    private String mTerminalStatus = "";
    private String mTerminalRegistryId = "";
    private boolean mTerminalFailed;
    private boolean mPermissionRequested;

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
                TermuxIntegration.homeDirectory(context),
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
                                ? TermuxIntegration.homeDirectory(context)
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

    static Intent attachIntent(final Context context, final ConsoleTerminalRegistry.Snapshot session) {
        return withTerminalId(createIntentAtDirectory(context, session.workingDirectory,
                DesktopExecBackend.parse(session.backend)), session.id).putExtra(EXTRA_ATTACH_ONLY, true);
    }

    static String terminalId(Intent intent) { return intent.getStringExtra(EXTRA_TERMINAL_ID); }

    static Intent createTmuxIntent(Context context, TmuxSessionProvider.Session session) {
        return withTerminalId(createPreparedCommandIntent(context,
                TmuxSessionProvider.sessionCommand(session, TmuxSessionProvider.attachCommand(session.id)),
                TermuxIntegration.homeDirectory(context), DesktopExecBackend.TERMUX), ConsoleTerminalRegistry.nextId())
                .putExtra(EXTRA_TMUX_SESSION, session.id).putExtra(EXTRA_TMUX_CREATED, session.createdSeconds);
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
        mTerminalView = new ConsoleTerminalView(this);
        mActions = new ConsoleTerminalActions(this, mTerminalView, () -> mSession);
        mWindow = new ConsoleTerminalWindow(this, mTerminalView, mActions, this::createTerminalApplication);
        setContentView(mWindow.content());
        if (savedInstanceState != null) {
            mTerminalView.setFontSizeSp(savedInstanceState.getInt(STATE_FONT_SIZE, mTerminalView.fontSizeSp()));
        }

        final String restoredDirectory = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_WORKING_DIRECTORY);
        final String initialDirectory = restoredDirectory == null
                ? initialDirectory(getIntent()) : restoredDirectory;
        final String startupCommand = takeAutoRunCommand(getIntent());
        final TerminalTransport.Factory transportFactory =
                terminalTransportFactory();
        String sessionId = getIntent().getStringExtra(EXTRA_TERMINAL_ID);
        if (sessionId == null || sessionId.isEmpty()) { sessionId = ConsoleTerminalRegistry.nextId(); }
        getIntent().putExtra(EXTRA_TERMINAL_ID, sessionId);
        try {
            mSession = ConsoleTerminalRegistry.acquire(sessionId,
                    getIntent().getBooleanExtra(EXTRA_ATTACH_ONLY, false) ? null
                            : listener -> new ConsoleTerminalSession(
                                    initialDirectory,
                                    mTerminalView.columns(),
                                    mTerminalView.rows(),
                                    mTerminalView.cellWidth(),
                                    mTerminalView.cellHeight(),
                                    mBackend,
                                    startupCommand,
                                    transportFactory,
                                    listener));
        } catch (IllegalArgumentException | IllegalStateException error) {
            Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        MagicDeskRuntime.startTools(this);
        if (getIntent().hasExtra(EXTRA_TMUX_SESSION)) {
            if (mBackend != DesktopExecBackend.TERMUX) throw new IllegalArgumentException("tmux requires Termux");
            ConsoleTerminalRegistry.bindTmux(sessionId, getIntent().getStringExtra(EXTRA_TMUX_SESSION),
                    getIntent().getLongExtra(EXTRA_TMUX_CREATED, 0));
        }
        mTerminalView.attach(mSession, mActions);
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
        onTitleChanged(mSession.title());
        onMetadataChanged();
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
        if (mTerminalView != null) { state.putInt(STATE_FONT_SIZE, mTerminalView.fontSizeSp()); }
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
            mWindow.toggleToolbar();
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { ConsoleTerminalRegistry.focused(mTerminalRegistryId, this); }
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
        if (mActions != null) mActions.refreshWorkingDirectory(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BuiltInWindowRegistry.unregister(this);
        ConsoleTerminalRegistry.detach(mTerminalRegistryId, this);
        if (mTerminalView != null) { mTerminalView.attach(null, null); }
        if (mActions != null) mActions.close();
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
        mTerminalStatus = "";
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
        updateShellStatus();
        updateActions();
    }

    @Override
    public void onTitleChanged(final String title) {
        final String fallback = getString(mBackend == DesktopExecBackend.TERMUX
                ? R.string.console_termux_title : R.string.console_title);
        final var snapshot = ConsoleTerminalRegistry.status(mTerminalRegistryId);
        final String label = snapshot == null ? TerminalTaskLabel.resolve(fallback,
                mSession == null ? TerminalProcessInfo.unknown() : mSession.foregroundProcess(), title)
                : snapshot.taskLabel(fallback);
        setTitle(label);
        DesktopTaskDescription.apply(this, label, R.drawable.ic_file_console);
    }

    @Override public void onNotification(final String message) { }

    @Override
    public void onCopyRequested(final String text) {
        mActions.copyText(text);
    }

    @Override
    public void onPasteRequested() {
        mActions.pasteClipboard();
    }

    @Override
    public void onBell() {
        if (mTerminalView != null) {
            mTerminalView.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS);
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
        final Context application = getApplicationContext();
        return (directory, rows, columns, startupCommand) ->
                TermuxPtyTransport.open(
                        application,
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
            final TermuxIntegration.Endpoint endpoint = TermuxIntegration.inspect(this);
            if (!endpoint.available() && !endpoint.permissionRequired) {
                failTerminal(endpoint.packageName + ": " + endpoint.error);
                return;
            }
            if (endpoint.permissionRequired) {
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

    private void failTerminal(final String message) {
        if (mTerminalFailed) {
            return;
        }
        mTerminalFailed = true;
        mTerminalStatus = message;
        updateShellStatus();
        updateActions();
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

    @Override public void onMetadataChanged() {
        if (mSession != null && mWindow != null) mWindow.updateMetadata(mSession.metadata());
    }

    private void updateActions() {
        if (mWindow != null) mWindow.updateActions(mSession != null, mSession != null && mSession.isReady());
    }

    private void updateShellStatus() {
        if (mWindow != null) mWindow.updateStatus(mBackend, mSnapshot, mTerminalStatus, mTerminalFailed);
    }
}
