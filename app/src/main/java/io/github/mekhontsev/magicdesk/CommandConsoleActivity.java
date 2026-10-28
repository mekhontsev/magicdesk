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
        ConsoleTerminalSession.Listener, TerminalWindowAttachment.Host {
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
    private static final String STATE_SESSION = "session_intent";
    private static final String EXTRA_EMPTY = "empty_terminal_window";
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

    static boolean isTermux(Intent intent) { return backend(intent) == DesktopExecBackend.TERMUX; }

    static DesktopApplicationShortcut recentLaunch(Context context, Intent source) {
        // Command entries keep their original recipe in the launch coordinator.
        // A tmux attachment instead remembers the terminal, never a transient server session ID.
        final String command = source.getStringExtra(EXTRA_AUTO_RUN_COMMAND);
        if (command != null && !command.isBlank() && !source.hasExtra(EXTRA_TMUX_SESSION)) return null;
        final boolean termux = isTermux(source);
        return new DesktopApplicationShortcut(context.getString(termux
                ? R.string.console_termux_title : R.string.console_title), "", "", launchTarget(),
                termux ? createTermuxIntent(context).toUri(Intent.URI_INTENT_SCHEME) : "",
                DesktopLaunchMode.AUTO, !termux, DesktopExecBackend.SHELL, false);
    }

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
        mWindow = new ConsoleTerminalWindow(this, mTerminalView, mActions,
                this::createTerminalApplication, this::selectSession);
        setContentView(mWindow.content());
        if (savedInstanceState != null) {
            mTerminalView.setFontSizeSp(savedInstanceState.getInt(STATE_FONT_SIZE, mTerminalView.fontSizeSp()));
        }

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
        final Intent restored = savedInstanceState == null ? null
                : savedInstanceState.getParcelable(STATE_SESSION, Intent.class);
        final Intent request = restored == null ? getIntent() : restored;
        if (restored != null) restoreSession(restored);
        else if (request.getBooleanExtra(EXTRA_EMPTY, false)) onTerminalDetached();
        else openSession(request);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        openSession(intent);
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
        if (mTerminalView != null) { state.putInt(STATE_FONT_SIZE, mTerminalView.fontSizeSp()); }
        state.putParcelable(STATE_SESSION, getIntent());
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
        ShellAccess.addStateListener(this);
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
            if (isFinishing() || isDestroyed() || mBackend != DesktopExecBackend.SHELL) {
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
        // The registry releases the binding when its PTY exits.
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
        mWindow.updateSession(mSession == null ? getString(R.string.terminal_sessions) : label,
                mSession != null);
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

    private void selectSession(Intent request) {
        final String id = terminalId(request);
        final var session = id == null ? null : ConsoleTerminalRegistry.status(id);
        if (session != null && session.taskId >= 0 && session.taskId != getTaskId()) {
            UiDialogs.builder(this).setTitle(R.string.terminal_move_here)
                    .setMessage(getString(R.string.terminal_move_confirm,
                            session.taskLabel(getString(R.string.console_title))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.terminal_move_here, (dialog, which) -> openSession(request)).show();
        } else openSession(request);
    }

    private void restoreSession(Intent request) {
        final String id = terminalId(request);
        final var session = id == null ? null : ConsoleTerminalRegistry.status(id);
        // Saved Activity state may precede a transfer out of a stopped window.
        if (session == null || session.taskId >= 0 && session.taskId != getTaskId()) onTerminalDetached();
        else openSession(attachIntent(this, session));
    }

    private void openSession(Intent request) {
        if (isFinishing() || isDestroyed()) return;
        String id = terminalId(request);
        if (id != null && id.equals(mTerminalRegistryId)) {
            mTerminalView.requestFocus();
            return;
        }
        final DesktopExecBackend requestedBackend = backend(request);
        final String tmuxId = request.getStringExtra(EXTRA_TMUX_SESSION);
        final long tmuxCreated = request.getLongExtra(EXTRA_TMUX_CREATED, 0);
        try {
            if (tmuxId != null && (requestedBackend != DesktopExecBackend.TERMUX
                    || !TmuxSessionProvider.isSessionId(tmuxId) || tmuxCreated < 0)) {
                throw new IllegalArgumentException("invalid tmux binding");
            }
            if (id == null || id.isEmpty()) id = ConsoleTerminalRegistry.nextId();
            final String command = takeAutoRunCommand(request);
            final ConsoleTerminalSession next = ConsoleTerminalRegistry.acquire(id,
                    request.getBooleanExtra(EXTRA_ATTACH_ONLY, false) ? null
                            : listener -> {
                                var files = tmuxId == null ? new ConsoleSessionFiles(getApplicationContext(), requestedBackend)
                                        : TmuxSessionProvider.files(getApplicationContext(), tmuxId, tmuxCreated);
                                var session = new ConsoleTerminalSession(initialDirectory(request),
                                    mTerminalView.columns(), mTerminalView.rows(), mTerminalView.cellWidth(),
                                    mTerminalView.cellHeight(), requestedBackend, command,
                                    terminalTransportFactory(requestedBackend, files), listener);
                                session.setFiles(files, tmuxId == null);
                                return session;
                            });
            if (tmuxId != null) ConsoleTerminalRegistry.bindTmux(id, tmuxId, tmuxCreated);
            // Registration revokes both former bindings before this window adopts the session.
            ConsoleTerminalRegistry.register(this, next, mTerminalView, id);
            mSession = next;
            mTerminalRegistryId = id;
            mBackend = next.backend();
            mSnapshot = mBackend == DesktopExecBackend.SHELL ? ShellAccess.currentSnapshot() : null;
            mTerminalFailed = false;
            mPermissionRequested = false;
            mTerminalStatus = "";
            setIntent(attachIntent(this, ConsoleTerminalRegistry.status(id)));
            mTerminalView.attach(next, mActions);
            MagicDeskRuntime.startTools(this, mBackend == DesktopExecBackend.SHELL);
            onTitleChanged(next.title());
            onMetadataChanged();
            updateShellStatus();
            updateActions();
            if (hasWindowFocus()) ConsoleTerminalRegistry.focused(id, this);
            mTerminalView.requestFocus();
            maybeStartSession();
        } catch (IllegalArgumentException | IllegalStateException error) {
            Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
            if (mSession == null) onTerminalDetached();
        }
    }

    @Override public void onTerminalDetached() {
        mSession = null;
        mTerminalRegistryId = "";
        mBackend = null;
        mSnapshot = null;
        mTerminalStatus = "";
        mTerminalFailed = false;
        setIntent(new Intent(this, CommandConsoleActivity.class).putExtra(EXTRA_EMPTY, true));
        onTitleChanged("");
        updateShellStatus();
        updateActions();
    }

    private TerminalTransport.Factory terminalTransportFactory(DesktopExecBackend backend, ConsoleSessionFiles files) {
        final Context application = getApplicationContext();
        return (directory, rows, columns, startupCommand) -> {
            var audio = LinuxAudio.prepare(application, new CommandExecution(application, backend));
            try {
                var environment = new java.util.LinkedHashMap<>(audio.environment());
                environment.putAll(files.prepare());
                TerminalTransport terminal = backend == DesktopExecBackend.TERMUX
                        ? TermuxPtyTransport.open(application, directory, rows, columns, startupCommand, environment)
                        : ShellAccess.openPty(directory, rows, columns, environment);
                return new ScopedTerminalTransport(terminal, audio);
            } catch (IOException | RuntimeException error) { audio.close(); throw error; }
        };
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
                || mTerminalView.getWidth() <= 0 || mTerminalView.getHeight() <= 0
                || mTerminalFailed) {
            return;
        }
        if (mBackend == DesktopExecBackend.TERMUX) {
            final TermuxIntegration.Endpoint endpoint = TermuxIntegration.inspect(this);
            if (!endpoint.available() && !endpoint.canRequestPermission()) {
                failTerminal(endpoint.packageName + ": " + endpoint.error);
                return;
            }
            if (endpoint.canRequestPermission()) {
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
