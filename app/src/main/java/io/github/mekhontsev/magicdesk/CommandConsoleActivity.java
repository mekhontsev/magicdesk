package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
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
        ConsoleTerminalView.Actions {
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
    private static final String STATE_WORKING_DIRECTORY = "working_directory";
    private static final String STATE_FONT_SIZE = "font_size_sp";
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private ConsoleTerminalView mTerminalView;
    private FrameLayout mTerminalContainer;
    private ConsoleTerminalSession mSession;
    private TextView mShellStatus;
    private TextView mTerminalTitle;
    private android.widget.ProgressBar mProgress;
    private LinearLayout mToolbar;
    private ImageButton mShowToolbar;
    private ImageButton mClear;
    private ImageButton mCopy;
    private ImageButton mPaste;
    private ImageButton mTmuxSessions;
    private LinearLayout.LayoutParams mTerminalParams;
    private ShellAccess.Snapshot mSnapshot;
    private DesktopExecBackend mBackend;
    private String mTerminalStatus = "";
    private String mTerminalRegistryId = "";
    private boolean mTerminalFailed;
    private boolean mPermissionRequested;
    private boolean mTmuxQueryRunning;
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
        ConsoleTerminalRegistry.detach(mTerminalRegistryId, this);
        if (mTerminalView != null) { mTerminalView.attach(null, null); }
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
        final String label = TerminalTaskLabel.resolve(fallback,
                mSession == null ? TerminalProcessInfo.unknown() : mSession.foregroundProcess(), title);
        setTitle(label);
        if (mTerminalTitle != null) {
            mTerminalTitle.setText(label);
            mTerminalTitle.setTooltipText(label);
        }
        DesktopTaskDescription.apply(this, label, R.drawable.ic_file_console);
    }

    @Override public void onNotification(final String message) { }

    @Override public void onMetadataChanged() {
        if (mSession == null || mProgress == null) { return; }
        final var data = mSession.metadata();
        mProgress.setVisibility(data.progressState() == 0 ? View.INVISIBLE : View.VISIBLE);
        mProgress.setIndeterminate(data.progressState() == 3 || data.progressPercent() < 0);
        if (data.progressPercent() >= 0) { mProgress.setProgress(data.progressPercent()); }
        final int color = data.progressState() == 2 ? 0xFFEF4444
                : data.progressState() == 4 ? COLOR_AMBER : COLOR_CYAN;
        mProgress.setProgressTintList(ColorStateList.valueOf(color));
        mProgress.setIndeterminateTintList(ColorStateList.valueOf(color));
        mProgress.setContentDescription(getString(R.string.console_progress, data.progressState(), data.progressPercent()));
    }

    @Override public void showLink(final com.termux.terminal.TerminalHyperlink link) {
        final TerminalLink target = TerminalLink.parse(link.uri());
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.console_link).setMessage(link.uri())
                .setNeutralButton(android.R.string.copy, (which, button) -> copyText(link.uri()))
                .setNegativeButton(android.R.string.cancel, null);
        if (target.canOpen()) { dialog.setPositiveButton(R.string.action_open, (which, button) -> {
            final int display = getDisplay() == null ? 0 : getDisplay().getDisplayId();
            new Thread(() -> {
                if (target.localPath() != null) { loadAndOpenPath(target.localPath()); return; }
                try {
                    final var result = new AndroidIntegrationGateway(this).openContent(
                            AndroidContentPayload.text(getString(R.string.console_link), target.uri(), false,
                                    AndroidContentPayload.Origin.APPLICATION), display);
                    if (!result.success) { throw new IOException(result.message); }
                } catch (Exception error) {
                    runOnUiThread(() -> Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show());
                }
            }, "MagicDeskTerminalLink").start();
        }); }
        dialog.show();
    }

    private void showCommandHistory() {
        if (mSession == null) { return; }
        final var history = mSession.emulator().getCommandHistory();
        final var commands = new java.util.ArrayList<>(history.snapshots());
        java.util.Collections.reverse(commands);
        final String[] labels = new String[commands.size()];
        for (int i = 0; i < labels.length; i++) {
            final var command = commands.get(i);
            labels[i] = (command.commandKnown() ? command.command() : getString(R.string.console_command_unknown))
                    + "\n" + command.state() + (command.exitCode() == null ? "" : " [exit " + command.exitCode() + "]");
        }
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.console_commands)
                .setNegativeButton(android.R.string.cancel, null);
        if (commands.isEmpty()) { dialog.setMessage(R.string.console_no_commands); }
        else { dialog.setItems(labels, (picker, index) -> {
            final var command = commands.get(index);
            final java.util.ArrayList<String> actions = new java.util.ArrayList<>();
            final java.util.ArrayList<Runnable> handlers = new java.util.ArrayList<>();
            if (command.commandKnown()) {
                actions.add(getString(R.string.console_copy_command)); handlers.add(() -> copyText(command.command()));
            }
            if (command.outputAvailable()) {
                actions.add(getString(R.string.console_copy_command_output)); handlers.add(() -> {
                    final String output = history.output(command.id());
                    if (output != null) { copyText(output); }
                    else { Toast.makeText(this, R.string.console_output_expired, Toast.LENGTH_SHORT).show(); }
                });
            }
            if (command.position() != null && !mSession.emulator().isAlternateBufferActive()) {
                for (final boolean output : new boolean[]{false, true}) {
                    if (history.range(command.id(), output) == null) { continue; }
                    actions.add(getString(output ? R.string.console_select_output : R.string.console_select_command));
                    handlers.add(() -> {
                        final var range = history.range(command.id(), output);
                        if (range != null) { mTerminalView.selectRange(range); }
                        else { Toast.makeText(this, R.string.console_output_expired, Toast.LENGTH_SHORT).show(); }
                    });
                }
                actions.add(getString(R.string.console_jump_command)); handlers.add(() -> {
                    for (final var latest : history.snapshots()) {
                        if (latest.id() == command.id()) { mTerminalView.jumpTo(latest.position()); break; }
                    }
                });
            }
            new AlertDialog.Builder(this).setTitle(labels[index])
                    .setItems(actions.toArray(new String[0]), (menu, action) -> handlers.get(action).run())
                    .setNegativeButton(android.R.string.cancel, null).show();
        }); }
        dialog.show();
    }

    private void showNotificationSettings() {
        TerminalNotifications.ensureChannel(this);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, TerminalNotifications.CHANNEL));
        }
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
        final AndroidClipboardGateway.TextReadResult clipboard =
                AndroidClipboardGateway.get(this).readText();
        if (clipboard.text.isEmpty()) {
            return;
        }
        mSession.paste(clipboard.text);
        mTerminalView.scrollToBottom();
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(6), dp(8), dp(6));
        SystemBarInsets.addToPadding(page);
        page.setBackgroundColor(COLOR_BACKGROUND);

        // Ordinary fullscreen tools have no native caption; keep the OSC title visible there too.
        mTerminalTitle = new TextView(this);
        mTerminalTitle.setTextColor(COLOR_TEXT);
        mTerminalTitle.setTextSize(13);
        mTerminalTitle.setSingleLine(true);
        mTerminalTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mTerminalTitle.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(mTerminalTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        mToolbar = new LinearLayout(this);
        mToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mToolbar.setGravity(Gravity.CENTER_VERTICAL);

        mShellStatus = new TextView(this);
        mShellStatus.setTextColor(COLOR_CYAN);
        mShellStatus.setTextSize(12);
        mShellStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mShellStatus.setSingleLine(true);
        mShellStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mShellStatus.setMaxWidth(dp(180));
        mToolbar.addView(mShellStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        mToolbar.addView(createIconButton(android.R.drawable.ic_menu_agenda,
                R.string.console_commands, view -> showCommandHistory()), buttonParams());
        mToolbar.addView(createIconButton(android.R.drawable.ic_dialog_info,
                R.string.console_notifications, view -> showNotificationSettings()), buttonParams());
        mToolbar.addView(createIconButton(android.R.drawable.ic_menu_zoom,
                R.string.console_font_size, view -> ConsoleFontSizeDialog.show(this,
                        R.string.console_font_size, mTerminalView.fontSizeSp(),
                        ConsolePreferences.fontSizeSp(this), mTerminalView::setFontSizeSp)), buttonParams());
        if (mBackend == DesktopExecBackend.TERMUX) {
            mTmuxSessions = createIconButton(
                    android.R.drawable.ic_menu_recent_history,
                    R.string.console_tmux_sessions,
                    view -> showTmuxSessions());
            mToolbar.addView(mTmuxSessions, buttonParams());
        }
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
        final ImageButton sessions = createIconButton(
                R.drawable.ic_file_new_window, R.string.terminal_sessions,
                view -> TerminalSessionsDialog.show(this));
        mToolbar.addView(sessions, buttonParams());
        final ImageButton endSession = createIconButton(
                R.drawable.ic_close, R.string.terminal_end_session,
                view -> ConsoleTerminalRegistry.close(mTerminalRegistryId));
        mToolbar.addView(endSession, buttonParams());
        final ImageButton hideToolbar = createIconButton(
                android.R.drawable.arrow_up_float,
                R.string.console_hide_toolbar,
                view -> setToolbarVisible(false));
        mToolbar.addView(hideToolbar, buttonParams());
        mToolbar.setVisibility(mToolbarVisible ? View.VISIBLE : View.GONE);
        final android.widget.HorizontalScrollView toolbarScroll = new android.widget.HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.addView(mToolbar);
        page.addView(toolbarScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setVisibility(View.INVISIBLE);
        page.addView(mProgress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

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

    private void showTmuxSessions() {
        if (mTmuxSessions == null || mTmuxQueryRunning) {
            return;
        }
        mTmuxQueryRunning = true;
        mTmuxSessions.setEnabled(false);
        Toast.makeText(
                this,
                R.string.console_tmux_loading,
                Toast.LENGTH_SHORT).show();
        TmuxSessionProvider.list(this, (snapshot, error) -> {
            mTmuxQueryRunning = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            updateActions();
            if (error != null) {
                Toast.makeText(
                        this,
                        getString(
                                R.string.console_tmux_list_failed,
                                ShellAccess.usefulMessage(error)),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (snapshot == null || !snapshot.available) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.console_tmux_sessions)
                        .setMessage(snapshot == null
                                ? getString(R.string.console_tmux_unavailable)
                                : snapshot.detail)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            showTmuxSessionList(snapshot);
        });
    }

    private void showTmuxSessionList(
            final TmuxSessionProvider.Snapshot snapshot) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.console_tmux_sessions)
                .setPositiveButton(
                        R.string.console_tmux_new_session,
                        (dialog, which) -> showNewTmuxSessionDialog())
                .setNegativeButton(android.R.string.cancel, null);
        if (snapshot.sessions.isEmpty()) {
            builder.setMessage(R.string.console_tmux_no_sessions);
        } else {
            final String[] choices = new String[snapshot.sessions.size()];
            for (int index = 0; index < snapshot.sessions.size(); index++) {
                final TmuxSessionProvider.Session session =
                        snapshot.sessions.get(index);
                choices[index] = getString(
                        R.string.console_tmux_session_summary,
                        session.name,
                        getResources().getQuantityString(
                                R.plurals.console_tmux_windows,
                                session.windows,
                                Integer.valueOf(session.windows)),
                        getString(session.attached()
                                ? R.string.console_tmux_attached
                                : R.string.console_tmux_detached));
            }
            builder.setItems(choices, (dialog, which) -> {
                final TmuxSessionProvider.Session session =
                        snapshot.sessions.get(which);
                launchTmuxConsole(
                        TmuxSessionProvider.attachCommand(session.id));
            });
        }
        builder.show();
    }

    private void showNewTmuxSessionDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.console_tmux_session_name);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final int horizontalPadding = dp(24);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.console_tmux_new_session)
                .setView(container)
                .setPositiveButton(R.string.console_tmux_open, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    final String name;
                    try {
                        name = TmuxSessionProvider.normalizeName(
                                input.getText().toString());
                    } catch (IllegalArgumentException error) {
                        input.setError(getString(
                                R.string.console_tmux_invalid_name));
                        return;
                    }
                    dialog.dismiss();
                    launchTmuxConsole(
                            TmuxSessionProvider.openOrCreateCommand(name));
                }));
        dialog.show();
        input.requestFocus();
    }

    private void launchTmuxConsole(final String command) {
        BuiltInWindowLauncher.launch(
                this,
                createPreparedCommandIntent(
                        this,
                        command,
                        TermuxIntegration.homeDirectory(this),
                        DesktopExecBackend.TERMUX),
                launchTarget(),
                error -> {
                    if (error != null) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.console_tmux_launch_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
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
        if (mTmuxSessions != null) {
            mTmuxSessions.setEnabled(
                    !mTmuxQueryRunning
                            && TermuxIntegration.isAvailable(this));
        }
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
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(this).writeText(
                        "MagicDesk console output", text, false);
        if (!copied.successful) {
            Toast.makeText(this, R.string.console_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
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
