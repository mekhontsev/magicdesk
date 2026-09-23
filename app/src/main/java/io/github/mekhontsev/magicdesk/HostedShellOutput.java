package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import java.util.concurrent.CompletableFuture;

/** Borrowed output whose family viewport does not resize the client itself. */
interface HostedShellOutput extends HostedSurfaceOutput {
    CompletableFuture<Void> present(Surface surface, ShellBounds viewport);
}
