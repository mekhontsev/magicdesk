package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.view.DragEvent;
import android.view.View;
import android.widget.Toast;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** User-requested content actions; never owns terminal parsing or PTY lifetime. */
final class ConsoleTerminalActions implements ConsoleTerminalView.Actions, AutoCloseable {
    private final Activity mActivity;
    private final ConsoleTerminalView mTerminalView;
    private final Supplier<ConsoleTerminalSession> mSession;
    private final java.util.concurrent.ExecutorService mContentWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "MagicDeskTerminalContent"));
    private boolean mExportingImage;
    private volatile boolean mClosed;

    ConsoleTerminalActions(Activity activity, ConsoleTerminalView view, Supplier<ConsoleTerminalSession> session) {
        mActivity = activity;
        mTerminalView = view;
        mSession = session;
    }

    @Override public void close() {
        mClosed = true;
        mContentWorker.shutdownNow();
    }

    private void execute(Runnable action) {
        if (mClosed) return;
        try {
            mContentWorker.execute(() -> {
                if (!mClosed) action.run();
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            if (!mClosed) throw error;
        }
    }

    void clear() {
        if (mSession.get() == null) return;
        mSession.get().clear();
        mTerminalView.clearSelection();
    }

    @Override public void showLink(final com.termux.terminal.TerminalHyperlink link) {
        final TerminalLink target = TerminalLink.parse(link.uri());
        final AlertDialog.Builder dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.console_link).setMessage(link.uri())
                .setNeutralButton(android.R.string.copy, (which, button) -> copyText(link.uri()))
                .setNegativeButton(android.R.string.cancel, null);
        if (target.canOpen()) { dialog.setPositiveButton(R.string.action_open, (which, button) -> {
            final int display = mActivity.getDisplay() == null ? 0 : mActivity.getDisplay().getDisplayId();
            execute(() -> {
                if (target.localPath() != null) { loadAndOpenPath(target.localPath()); return; }
                try {
                    final var result = new AndroidIntegrationGateway(mActivity).openContent(
                            AndroidContentPayload.text(mActivity.getString(R.string.console_link), target.uri(), false,
                                    AndroidContentPayload.Origin.APPLICATION), display);
                    if (!result.success) { throw new IOException(result.message); }
                } catch (Exception error) {
                    mActivity.runOnUiThread(() -> Toast.makeText(mActivity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show());
                }
            });
        }); }
        dialog.show();
    }

    @Override public void showImage(final com.termux.terminal.TerminalImage image) {
        if (mExportingImage || mActivity.isFinishing() || mActivity.isDestroyed()) return;
        new AlertDialog.Builder(mActivity)
                .setTitle(mActivity.getString(R.string.console_image_title, image.width, image.height))
                .setItems(new String[]{mActivity.getString(R.string.console_image_save),
                        mActivity.getString(R.string.action_open), mActivity.getString(R.string.file_manager_share)},
                        (dialog, action) -> exportImage(image, action))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void exportImage(final com.termux.terminal.TerminalImage image, final int action) {
        if (mExportingImage) return;
        mExportingImage = true;
        final int display = mActivity.getDisplay() == null ? 0 : mActivity.getDisplay().getDisplayId();
        Toast.makeText(mActivity, R.string.console_image_preparing, Toast.LENGTH_SHORT).show();
        // The immutable raster stays alive even if the program clears or replaces its placement.
        execute(() -> {
            try {
                final var uri = GeneratedContentProvider.publish(mActivity, "Terminal image.png",
                        output -> com.termux.terminal.AndroidTerminalImages.writePng(image, output));
                final var content = AndroidContentPayload.uris(mActivity.getString(R.string.console_image),
                        java.util.List.of(new AndroidContentPayload.UriItem(uri, "image/png")),
                        java.util.List.of(), AndroidContentPayload.Origin.APPLICATION);
                if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                if (action == 0) {
                    mActivity.runOnUiThread(() -> {
                        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
                        try {
                            ToolApplications.open(mActivity, FileManagerActivity.createSaveIntent(mActivity, content),
                                    ToolLaunchTarget.resolve("auto", display, DesktopRuntimeBridge.workspaceDisplayIds()),
                                    null, this::imageActionFinished);
                        } catch (RuntimeException error) { imageActionFinished(error); }
                    });
                } else {
                    final var gateway = new AndroidIntegrationGateway(mActivity);
                    final var result = action == 1 ? gateway.openContent(content, display) : gateway.shareContent(content, display);
                    if (!result.success) throw new IOException(result.message);
                }
            } catch (Exception error) {
                mActivity.runOnUiThread(() -> imageActionFinished(error));
            } finally {
                mActivity.runOnUiThread(() -> mExportingImage = false);
            }
        });
    }

    private void imageActionFinished(final Throwable error) {
        if (error != null && !mActivity.isFinishing() && !mActivity.isDestroyed()) {
            Toast.makeText(mActivity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    void showCommandHistory() {
        if (mSession.get() == null) { return; }
        final var history = mSession.get().emulator().getCommandHistory();
        final var commands = new java.util.ArrayList<>(history.snapshots());
        java.util.Collections.reverse(commands);
        final String[] labels = new String[commands.size()];
        for (int i = 0; i < labels.length; i++) {
            final var command = commands.get(i);
            labels[i] = (command.commandKnown() ? command.command() : mActivity.getString(R.string.console_command_unknown))
                    + "\n" + command.state() + (command.exitCode() == null ? "" : " [exit " + command.exitCode() + "]");
        }
        final AlertDialog.Builder dialog = new AlertDialog.Builder(mActivity).setTitle(R.string.console_commands)
                .setNegativeButton(android.R.string.cancel, null);
        if (commands.isEmpty()) { dialog.setMessage(R.string.console_no_commands); }
        else { dialog.setItems(labels, (picker, index) -> {
            final var command = commands.get(index);
            final java.util.ArrayList<String> actions = new java.util.ArrayList<>();
            final java.util.ArrayList<Runnable> handlers = new java.util.ArrayList<>();
            if (command.commandKnown()) {
                actions.add(mActivity.getString(R.string.console_copy_command)); handlers.add(() -> copyText(command.command()));
            }
            if (command.outputAvailable()) {
                actions.add(mActivity.getString(R.string.console_copy_command_output)); handlers.add(() -> {
                    final String output = history.output(command.id());
                    if (output != null) { copyText(output); }
                    else { Toast.makeText(mActivity, R.string.console_output_expired, Toast.LENGTH_SHORT).show(); }
                });
            }
            if (command.position() != null && !mSession.get().emulator().isAlternateBufferActive()) {
                for (final boolean output : new boolean[]{false, true}) {
                    if (history.range(command.id(), output) == null) { continue; }
                    actions.add(mActivity.getString(output ? R.string.console_select_output : R.string.console_select_command));
                    handlers.add(() -> {
                        final var range = history.range(command.id(), output);
                        if (range != null) { mTerminalView.selectRange(range); }
                        else { Toast.makeText(mActivity, R.string.console_output_expired, Toast.LENGTH_SHORT).show(); }
                    });
                }
                actions.add(mActivity.getString(R.string.console_jump_command)); handlers.add(() -> {
                    for (final var latest : history.snapshots()) {
                        if (latest.id() == command.id()) { mTerminalView.jumpTo(latest.position()); break; }
                    }
                });
            }
            new AlertDialog.Builder(mActivity).setTitle(labels[index])
                    .setItems(actions.toArray(new String[0]), (menu, action) -> handlers.get(action).run())
                    .setNegativeButton(android.R.string.cancel, null).show();
        }); }
        dialog.show();
    }

    void showNotificationSettings() {
        TerminalNotifications.ensureChannel(mActivity);
        if (mActivity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mActivity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            mActivity.startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, mActivity.getPackageName())
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, TerminalNotifications.CHANNEL));
        }
    }

    @Override
    public void copySelection() {
        if (mSession.get() == null || mTerminalView == null) {
            return;
        }
        final String selected = mTerminalView.selectedText();
        copyText(selected.isEmpty() ? mSession.get().transcript() : selected);
    }

    void showCopyActions(final View anchor) {
        if (mSession.get() == null || mTerminalView == null) return;
        final String selected = mTerminalView.selectedText();
        if (selected.isEmpty()) {
            copySelection();
            return;
        }
        final android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
        menu.getMenu().add(R.string.console_copy_exact).setOnMenuItemClickListener(item -> {
            copyText(selected);
            return true;
        });
        menu.getMenu().add(R.string.console_copy_paragraph).setOnMenuItemClickListener(item -> {
            copyText(ConsoleCopyText.asParagraph(selected));
            return true;
        });
        menu.show();
    }

    @Override
    public void pasteClipboard() {
        if (mSession.get() == null) {
            return;
        }
        final AndroidClipboardGateway.TextReadResult clipboard =
                AndroidClipboardGateway.get(mActivity).readText();
        if (clipboard.text.isEmpty()) {
            return;
        }
        mSession.get().paste(clipboard.text);
        mTerminalView.scrollToBottom();
    }

    boolean handleFileDrop(final View view, final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return payload != null;
            case DragEvent.ACTION_DROP:
                if (payload == null || mSession.get() == null) {
                    return false;
                }
                mSession.get().write(ConsolePathText.quotePaths(
                        payload.absolutePaths) + " ");
                mTerminalView.requestFocus();
                return true;
            default:
                return payload != null;
        }
    }

    void openSelectedPathOrWorkingDirectory() {
        final String selected = mTerminalView.selectedText();
        if (selected.isEmpty()) {
            refreshWorkingDirectory(directory -> openFilesAt(
                    createFilesDirectoryInfo(directory)));
            return;
        }
        mSession.get().requestWorkingDirectory((directory, lookupError) -> {
            final String resolved;
            try {
                resolved = ConsolePathText.resolveSelectedPath(
                        directory, selected);
            } catch (IllegalArgumentException error) {
                showPathUnavailable(error);
                return;
            }
            execute(() -> loadAndOpenPath(resolved));
        });
    }

    private void loadAndOpenPath(final String path) {
        try {
            final ShellFileInfo file = ShellAccess.getShellFileInfo(path);
            mActivity.runOnUiThread(() -> {
                if (!mActivity.isFinishing() && !mActivity.isDestroyed()) {
                    openFilesAt(file);
                }
            });
        } catch (IOException | RuntimeException error) {
            mActivity.runOnUiThread(() -> showPathUnavailable(error));
        }
    }

    void refreshWorkingDirectory(final Consumer<String> action) {
        if (mSession.get() == null) {
            return;
        }
        mSession.get().requestWorkingDirectory((directory, error) -> {
            if (action != null && !mActivity.isFinishing() && !mActivity.isDestroyed()) {
                action.accept(directory);
            }
        });
    }

    private void openFilesAt(final ShellFileInfo file) {
        BuiltInWindowLauncher.launch(
                mActivity,
                FileManagerActivity.createRevealIntent(mActivity, file),
                FileManagerActivity.launchTarget(mActivity),
                error -> {
                    if (error != null) {
                        Toast.makeText(
                                mActivity,
                                mActivity.getString(
                                        R.string.console_files_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showPathUnavailable(final Throwable error) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            return;
        }
        Toast.makeText(
                mActivity,
                mActivity.getString(
                        R.string.console_path_unavailable,
                        ShellAccess.usefulMessage(error)),
                Toast.LENGTH_SHORT).show();
    }

    void copyText(final String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(mActivity).writeText(
                        "MagicDesk console output", text, false);
        if (!copied.successful) {
            Toast.makeText(mActivity, R.string.console_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(mActivity, R.string.console_copied,
                Toast.LENGTH_SHORT).show();
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
}
