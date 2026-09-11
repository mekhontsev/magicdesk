package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** A one-shot Shizuku bootstrap, used only when its UID 0 needs to become UID 2000. */
public final class ShellServiceBootstrap extends IShellServiceBootstrap.Stub {
    private final Context mContext;
    private boolean mStarted;

    public ShellServiceBootstrap(Context context) { mContext = context; }

    @Override public synchronized int start(String service, String token, int userId) {
        if (mStarted) throw new IllegalStateException("bootstrap already used");
        mStarted = true;
        try {
            final String[] command = ShellServiceStartup.command(mContext,
                    ShellServiceLauncher.Service.valueOf(service), token, userId, 2000);
            final var result = BoundedProcessRunner.run(new ProcessBuilder(command).redirectErrorStream(true).start());
            if (result.exitCode != 0) throw new java.io.IOException(result.output.trim());
            return ShellProcessLauncher.parsePid(result.output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bootstrap interrupted", error);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("bootstrap failed", error);
        }
    }

    @Override public void destroy() { System.exit(0); }
}
