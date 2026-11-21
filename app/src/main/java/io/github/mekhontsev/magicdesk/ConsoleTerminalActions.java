package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
    private final OperationResources pathResources = new OperationResources();
    private volatile boolean mClosed;

    ConsoleTerminalActions(Activity activity, ConsoleTerminalView view, Supplier<ConsoleTerminalSession> session) {
        mActivity = activity;
        mTerminalView = view;
        mSession = session;
    }

    @Override public void close() {
        mClosed = true;
        pathResources.close();
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

    void redraw() {
        var session=mSession.get();
        if (session!=null) session.write(new byte[]{0x0C});
    }

    void confirmClearHistory() {
        var session=mSession.get();
        if (session==null) return;
        UiDialogs.builder(mActivity).setTitle(R.string.console_clear_history).setMessage(R.string.console_clear_history_confirm)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.console_clear,(d,w)->{
                    if (!mClosed && mSession.get()==session) { session.clearHistory(); mTerminalView.clearSelection(); }
                }).show();
    }

    void showSessionFiles() {
        final var session = mSession.get();
        if (session == null || session.files() == null) return;
        ConsoleSessionFilesDialog.show(mActivity, session.files(), paths -> {
            if (mClosed || mSession.get() != session) return;
            String text = session.files().shellPaths(paths);
            if (text.isEmpty()) return;
            session.paste(text + " ");
            mTerminalView.requestFocus();
        }, () -> editText(session));
    }

    private void editText(ConsoleTerminalSession session) {
        if (mClosed || mSession.get() != session) return;
        ConsoleTextDialog.show(mActivity, "", null, 0, (text, name, complete) ->
                ConsoleContentDelivery.confirm(mActivity, session, name.isEmpty() ? text : "", confirmed ->
                        ConsoleContentDelivery.prepare(session, java.util.List.of(), text, name)
                                .whenComplete((value, failure) -> mActivity.runOnUiThread(() -> {
                                    if (failure != null) { complete.accept(failure); return; }
                                    try {
                                        ConsoleContentDelivery.paste(session, () -> !mClosed && mSession.get() == session,
                                                value, confirmed);
                                        mTerminalView.requestFocus();
                                        complete.accept(null);
                                    } catch (IOException error) { complete.accept(error); }
                                })), () -> complete.accept(new java.util.concurrent.CancellationException())), () -> { });
    }

    @Override public void showLink(final com.termux.terminal.TerminalHyperlink link) {
        final var session=mSession.get();
        var target=TerminalLink.parse(link.uri());
        if (session==null) return;
        if (target.fileHost()==null) { showLink(session,target); return; }
        execute(()->{
            String local=null;
            try { local=new ConsolePathBrowser(session,pathResources).hostname(); }
            catch (IOException | RuntimeException unavailable) { /* Unknown authority stays copy-only. */ }
            var verified=TerminalLink.parse(link.uri(),local);
            mActivity.runOnUiThread(()->{ if (current(session)) showLink(session,verified); });
        });
    }
    private void showLink(ConsoleTerminalSession session,TerminalLink target) {
        final AlertDialog.Builder dialog = UiDialogs.builder(mActivity)
                .setTitle(R.string.console_link).setMessage(target.uri())
                .setNeutralButton(android.R.string.copy, (which, button) -> copyText(target.uri()))
                .setNegativeButton(android.R.string.cancel, null);
        if (target.canOpen()) { dialog.setPositiveButton(R.string.action_open, (which, button) -> {
            if (!current(session)) return;
            if (target.localPath()!=null) { browse(session,target.localPath(),true); return; }
            final int display = mActivity.getDisplay() == null ? 0 : mActivity.getDisplay().getDisplayId();
            execute(() -> {
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
        UiDialogs.builder(mActivity)
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
        final var session = mSession.get();
        if (session == null) { return; }
        final var history = session.emulator().getCommandHistory();
        final var commands = new java.util.ArrayList<>(history.snapshots());
        java.util.Collections.reverse(commands);
        final String[] labels = new String[commands.size()];
        for (int i = 0; i < labels.length; i++) {
            final var command = commands.get(i);
            labels[i] = (command.commandKnown() ? command.command() : mActivity.getString(R.string.console_command_unknown))
                    + "\n" + command.state() + (command.exitCode() == null ? "" : " [exit " + command.exitCode() + "]");
        }
        final AlertDialog.Builder dialog = UiDialogs.builder(mActivity).setTitle(R.string.console_commands)
                .setNegativeButton(android.R.string.cancel, null);
        if (commands.isEmpty()) { dialog.setMessage(R.string.console_no_commands); }
        else { dialog.setItems(labels, (picker, index) -> {
            if (mSession.get() != session) return;
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
            if (command.position() != null && !session.emulator().isAlternateBufferActive()) {
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
            UiDialogs.builder(mActivity).setTitle(labels[index])
                    .setItems(actions.toArray(new String[0]), (menu, action) -> {
                        if (mSession.get() == session) handlers.get(action).run();
                    })
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
        final var session = mSession.get();
        ConsoleContentDelivery.confirm(mActivity, session, clipboard.text, confirmed -> {
            try {
                ConsoleContentDelivery.paste(session, () -> !mClosed && mSession.get() == session,
                        clipboard.text, confirmed);
                mTerminalView.scrollToBottom();
            } catch (IOException error) {
                Toast.makeText(mActivity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
            }
        }, () -> { });
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
        final var session = mSession.get();
        if (session == null) return;
        final String selected = mTerminalView.selectedText();
        if (selected.startsWith("/")) {
            try { browse(session,ConsolePathText.resolveSelectedPath("/",selected)); }
            catch (IllegalArgumentException invalid) { showPathUnavailable(invalid); }
        } else withActionDirectory(session,dir -> {
            if (dir.isEmpty() && !selected.isEmpty()) throw new IllegalArgumentException(mActivity.getString(R.string.console_path_unknown));
            browse(session,selected.isEmpty() ? dir.isEmpty() ? "/" : dir : ConsolePathText.resolveSelectedPath(dir,selected));
        });
    }

    private boolean current(ConsoleTerminalSession session) {
        return session!=null && !mClosed && mSession.get()==session && !mActivity.isFinishing() && !mActivity.isDestroyed();
    }
    private void pathError(ConsoleTerminalSession session,Throwable error) {
        mActivity.runOnUiThread(()->{ if (current(session)) showPathUnavailable(error); });
    }
    void withActionDirectory(ConsoleTerminalSession session,Consumer<String> action) {
        execute(()->{ try {
            String dir=session.actionDirectory(pathResources);
            mActivity.runOnUiThread(()->{
                if (current(session)) try { action.accept(dir); } catch (RuntimeException error) { showPathUnavailable(error); }
            });
        } catch (IOException | RuntimeException error) { pathError(session,error); } });
    }
    private void browse(ConsoleTerminalSession session,String path) {
        browse(session,path,false);
    }
    private void browse(ConsoleTerminalSession session,String path,boolean openLink) {
        int display=mActivity.getDisplay()==null ? 0 : mActivity.getDisplay().getDisplayId();
        execute(()->{ try {
            var scope=session.actionTransport();
            if (scope.execution.backend==DesktopExecBackend.SHELL && session.initialEnvironment==null) {
                final ShellFileInfo file=ShellAccess.getShellFileInfo(path);
                if (openLink && !file.directory) {
                    if (!current(session)) return;
                    scope.execution.requireCurrent();
                    viewFile(session,file.name,ShellFileGrantStore.create(mActivity,file,false),display);
                    return;
                }
                mActivity.runOnUiThread(()->{ if (current(session)) {
                    try { scope.execution.requireCurrent(); openFilesAt(file); }
                    catch (RuntimeException failure) { showPathUnavailable(failure); }
                }});
                return;
            }
            var browser=new ConsolePathBrowser(session,pathResources);
            var listing=browser.list(path);
            mActivity.runOnUiThread(() -> {
                if (!current(session)) return;
                if (openLink && !listing.directory()) { openFile(session,browser,listing.path()); return; }
                var dialog=UiDialogs.builder(mActivity).setTitle(listing.path()).setNegativeButton(android.R.string.cancel,null);
                if (listing.directory()) {
                    var entries=listing.children();
                    dialog.setItems(entries.stream().map(e->e.name()+(e.directory()?"/":"")).toArray(String[]::new),
                            (d,i)->{ if (current(session)) browse(session,ShellFilePathPolicy.normalizeShellAbsolute(listing.path()+"/"+entries.get(i).name())); });
                    if (!listing.path().equals("/")) dialog.setNeutralButton(R.string.file_manager_up,(d,w)->{
                        if (current(session)) browse(session,ShellFilePathPolicy.shellParent(listing.path()));
                    });
                } else {
                    dialog.setPositiveButton(R.string.action_open,(d,w)->{ if (current(session)) openFile(session,browser,listing.path()); });
                    dialog.setNeutralButton(R.string.file_manager_copy_path,(d,w)->{ if (current(session)) copyText(listing.path()); });
                }
                dialog.show();
            });
        } catch (IOException | RuntimeException | org.json.JSONException error) { pathError(session,error); } });
    }
    private void openFile(ConsoleTerminalSession session,ConsolePathBrowser browser,String path) {
        int display=mActivity.getDisplay()==null ? 0 : mActivity.getDisplay().getDisplayId();
        execute(()->{ try {
            String name=path.substring(path.lastIndexOf('/')+1);
            var uri=GeneratedContentProvider.publish(mActivity,name,out->
                    browser.read(path,"[ -f \"$1\" ] && cat \"$1\"",out::write));
            viewFile(session,name,uri,display);
        } catch (IOException | RuntimeException | org.json.JSONException failure) { pathError(session,failure); } });
    }
    private void viewFile(ConsoleTerminalSession session,String name,android.net.Uri uri,int display)
            throws IOException, org.json.JSONException {
        if (!current(session)) return;
        var content=AndroidContentPayload.uris(name,java.util.List.of(new AndroidContentPayload.UriItem(
                uri,mActivity.getContentResolver().getType(uri))),java.util.List.of(),AndroidContentPayload.Origin.APPLICATION);
        var result=new AndroidIntegrationGateway(mActivity).openContent(content,display);
        if (!result.success) throw new IOException(result.message);
    }

    void refreshWorkingDirectory(final Consumer<String> action) {
        final var session = mSession.get();
        if (session == null) {
            return;
        }
        session.requestWorkingDirectory((directory, error) -> {
            if (mSession.get() == session && action != null && !mActivity.isFinishing() && !mActivity.isDestroyed()) {
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

}
