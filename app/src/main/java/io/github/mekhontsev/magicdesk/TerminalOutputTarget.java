package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;

/** A resolved peer identity; never substitutes a newly focused terminal or pane. */
record TerminalOutputTarget(ConsoleTerminalRegistry.OutputTarget terminal, TmuxPanes.Target pane) {
    static TerminalOutputTarget resolve(String terminalId, String tmuxTarget) throws IOException {
        if ((terminalId == null) == (tmuxTarget == null)) {
            throw new IllegalArgumentException("provide exactly one of terminalId or tmuxTarget");
        }
        return terminalId != null
                ? new TerminalOutputTarget(ConsoleTerminalRegistry.outputTarget(terminalId), null)
                : new TerminalOutputTarget(null, TmuxPanes.Target.parse(tmuxTarget));
    }

    boolean requiresTermux() { return pane != null || terminal.backend() == DesktopExecBackend.TERMUX; }

    void requireAvailable(Context context) throws IOException {
        if (requiresTermux()) TermuxIntegration.inspect(context).requireAvailable();
    }

    PtyPeerOutput.Receipt write(Context context, byte[] bytes) throws IOException {
        return pane != null ? TmuxPanes.emit(context, pane, bytes)
                : PtyPeerOutput.emit(context, terminal.backend(), terminal.endpoint(), bytes);
    }

    TerminalOutputStream stream(Context context, String mimeType) {
        return new TerminalOutputStream(bytes -> write(context, bytes), mimeType, pane != null);
    }
}
