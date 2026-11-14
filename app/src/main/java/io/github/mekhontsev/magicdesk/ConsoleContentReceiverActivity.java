package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** User-confirmed Share ingress. The Activity retains incoming grants until copying finishes. */
public final class ConsoleContentReceiverActivity extends Activity {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "ConsoleShare"));
    private final ContentRequestScope requests = new ContentRequestScope(worker);
    private AndroidContentPayload content;
    private Intent destination;
    private ConsoleTerminalSession recipient;
    private Button choose;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setFinishOnTouchOutside(false);
        content = AndroidContentPayload.fromSendIntent(getIntent());
        if (content.isEmpty() || content.truncated) {
            Toast.makeText(this, content.truncated ? R.string.console_share_too_many
                    : R.string.desktop_share_unsupported, Toast.LENGTH_LONG).show();
            finish(); return;
        }
        choose = new Button(this);
        choose.setAllCaps(false);
        choose.setMaxLines(2);
        choose.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        choose.setText(R.string.terminal_sessions);
        choose.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down, 0);
        choose.setOnClickListener(view -> TerminalSessionsDialog.select(this, this::select));
        var latest = ConsoleTerminalRegistry.mostRecent();
        if (latest != null) select(CommandConsoleActivity.attachIntent(this, latest));
        String text = content.text.isEmpty() && !content.htmlText.isEmpty()
                ? android.text.Html.fromHtml(content.htmlText, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                : content.text;
        ConsoleTextDialog.show(this, text, choose, content.uriItems.size(), this::send, this::finish);
    }

    private void select(Intent intent) {
        String id = CommandConsoleActivity.terminalId(intent);
        var snapshot = id == null ? null : ConsoleTerminalRegistry.status(id);
        destination = new Intent(intent);
        if (id == null) CommandConsoleActivity.withTerminalId(destination, ConsoleTerminalRegistry.nextId());
        try { recipient = snapshot == null ? null : ConsoleTerminalRegistry.acquire(id, null); }
        catch (IllegalStateException failure) {
            destination = null;
            Toast.makeText(this, ShellAccess.usefulMessage(failure), Toast.LENGTH_LONG).show();
            return;
        }
        choose.setText(snapshot == null ? getString(R.string.terminal_new) + " ("
                + getString(CommandConsoleActivity.backend(intent).title()) + ")"
                : snapshot.taskLabel(getString(R.string.console_title)) + " ("
                        + getString(DesktopExecBackend.parse(snapshot.backend).title()) + ")");
    }

    private void send(String text, String name, Consumer<Throwable> complete) {
        if (destination == null) {
            complete.accept(new CancellationException());
            TerminalSessionsDialog.select(this, this::select);
            return;
        }
        final Intent intent = new Intent(destination);
        final ConsoleTerminalSession captured = recipient;
        ConsoleContentDelivery.confirm(this, captured, name.isEmpty() ? text : "", confirmed -> {
            if (captured != null) {
                deliver(intent, captured, text, name, confirmed, complete);
                return;
            }
            // Only the shared picker can create this Intent. Incoming Intent extras never choose an executor or command.
            open(intent, error -> {
                if (error != null) { complete.accept(error); return; }
                String id = CommandConsoleActivity.terminalId(intent);
                requests.submit(cancelled -> {
                    // EVENT_WAIT: exact terminal registration and PTY readiness, not a launch acceptance.
                    if (!ConsoleTerminalRegistry.awaitRegistration(id, 30_000))
                        throw new IOException("Terminal window did not open");
                    ContentStreamCopy.checkCancelled(cancelled);
                    var session = ConsoleTerminalRegistry.acquire(id, null);
                    session.awaitInputReady();
                    return session;
                }, null).thenAccept(result -> requests.deliver(this::runOnUiThread, () -> {
                    if (result.failure != null) { complete.accept(result.failure); return; }
                    recipient = result.value;
                    deliver(intent, result.value, text, name, confirmed, complete);
                }));
            });
        }, () -> complete.accept(new CancellationException()));
    }

    private void deliver(Intent intent, ConsoleTerminalSession session, String text, String name,
            boolean confirmed, Consumer<Throwable> complete) {
        if (!session.isReady()) { complete.accept(new IOException("Terminal session is no longer available")); return; }
        ConsoleContentDelivery.prepare(session, content.uris(), text, name).whenComplete((value, failure) ->
                requests.deliver(this::runOnUiThread, () -> {
                    if (failure != null) { complete.accept(failure); return; }
                    try {
                        ConsoleContentDelivery.paste(session, () -> !isFinishing() && !isDestroyed(), value, confirmed);
                    } catch (IOException error) { complete.accept(error); return; }
                    // Paste is committed. A presentation error must not offer a second insertion of the same content.
                    open(intent, error -> runOnUiThread(() -> {
                        if (error != null) Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
                        complete.accept(null);
                    }));
                }));
    }

    private void open(Intent intent, BuiltInWindowLauncher.Callback callback) {
        int display = getDisplay() == null ? 0 : getDisplay().getDisplayId();
        TerminalSessions.open(this, intent, ToolLaunchTarget.resolve("auto", display,
                DesktopRuntimeBridge.workspaceDisplayIds()), null, callback);
    }

    @Override protected void onDestroy() {
        requests.close(); worker.shutdownNow();
        super.onDestroy();
    }
}
