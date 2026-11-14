package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.net.Uri;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Content preparation and paste policy for a captured PTY, never a global current console. */
final class ConsoleContentDelivery {
    private ConsoleContentDelivery() { }

    static boolean needsConfirmation(String text, boolean bracketed) {
        return !bracketed && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0);
    }

    static void confirm(Activity activity, ConsoleTerminalSession session, String text,
            Consumer<Boolean> accepted, Runnable cancelled) {
        boolean unsafe = needsConfirmation(text, session != null && session.isReady()
                && session.emulator().isBracketedPasteEnabled());
        if (!unsafe) { accepted.accept(false); return; }
        UiDialogs.builder(activity).setTitle(R.string.console_paste_lines_title)
                .setMessage(R.string.console_paste_lines_message)
                .setPositiveButton(android.R.string.paste, (d, which) -> accepted.accept(true))
                .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.run())
                .setOnCancelListener(d -> cancelled.run()).show();
    }

    static CompletableFuture<String> prepare(ConsoleTerminalSession session, List<Uri> uris,
            String text, String fileName) {
        if (uris.isEmpty() && fileName.isEmpty()) return CompletableFuture.completedFuture(text);
        if (session.files() == null) return CompletableFuture.failedFuture(new IOException("File channel is unavailable"));
        return session.files().importContent(uris, fileName, text).thenApply(items -> {
            String paths = session.files().shellPaths(items.stream().map(ConsoleSessionFiles.Item::path).toList());
            if (paths.isEmpty()) throw new java.util.concurrent.CompletionException(new IOException("Session files closed"));
            return combine(fileName.isEmpty() ? text : "", paths);
        });
    }

    static String combine(String text, String paths) {
        if (paths.isEmpty()) return text;
        return text + (text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1)) ? "" : " ") + paths + " ";
    }

    static void paste(ConsoleTerminalSession session, BooleanSupplier current, String text,
            boolean confirmed) throws IOException {
        if (!current.getAsBoolean() || !session.isReady()) throw new IOException("Terminal session is no longer available");
        if (!confirmed && needsConfirmation(text, session.emulator().isBracketedPasteEnabled()))
            throw new IOException("Paste mode changed; confirm the insertion again");
        session.paste(text);
    }
}
