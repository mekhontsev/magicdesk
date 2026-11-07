package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.util.List;

/** Resolves external requests into existing execution and presentation contracts. */
final class RunCommandLaunch {
    final CommandExecution executor;
    final String command, directory;
    final DesktopExecSpec interactive;

    RunCommandLaunch(Context context, RunCommandRequest request, android.os.CancellationSignal cancellation) throws Exception {
        executor = new CommandExecution(context, request.backend().equals("termux")
                ? DesktopExecBackend.TERMUX : request.executorUid() >= 10000 ? DesktopExecBackend.APP : DesktopExecBackend.SHELL);
        if (executor.termux != null ? !executor.scope.equals(request.termuxPackage()) : executor.uid != request.executorUid())
            throw new IllegalStateException("Requested executor is not selected");
        String script = request.script();
        boolean graphical = request.mode().equals("x11") || request.mode().equals("wayland");
        GraphicalProtocol protocol = request.mode().equals("wayland") ? GraphicalProtocol.WAYLAND : GraphicalProtocol.X11;
        DesktopExecSpec specification = null;
        String resolvedCommand, resolvedDirectory = request.directory();
        boolean background = request.mode().equals("background");
        if (request.backend().equals("shroot")) {
            var entry = GuestEnvironmentCatalog.parse(GuestEnvironmentOperations.read(executor, "", cancellation, "list", "--json"), executor.backend).stream()
                    .filter(value -> value.id().equals(request.environmentId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown Shroot environmentId"));
            if (background) {
                var plan = new GuestLaunchPlan(new GuestEnvironment(entry.store(), "", "", true),
                        request.directory(), List.of("/bin/sh", "-c", script));
                resolvedCommand = String.join(" ", plan.arguments().stream().map(ShellCommandLine::quote).toList());
                resolvedDirectory = "";
            } else {
                var shortcut = LinuxLaunchRecipe.build("RUN_COMMAND", new LinuxLaunchRecipe.Environment(
                        LinuxLaunchRecipe.Kind.MANAGED_GUEST, entry.store(), executor.backend, ""),
                        script, request.directory(), "", graphical ? LinuxLaunchRecipe.Presentation.APPLICATION
                                : LinuxLaunchRecipe.Presentation.TERMINAL, protocol);
                specification = DesktopLaunchRequest.from(shortcut).prepareExec().exec;
                resolvedCommand = specification.command;
                resolvedDirectory = specification.workingDirectory;
            }
        } else {
            resolvedCommand = ShellCommandLine.quote(executor.shell()) + " -c " + ShellCommandLine.quote(script);
            if (!background) specification = new DesktopExecSpec(executor.backend, resolvedCommand,
                    request.mode().equals("terminal"), request.directory(),
                    graphical ? new GraphicalLaunchOptions(protocol, false, request.keyboardDirectory(), "", "") : null, false);
        }
        executor.requireCurrent();
        // Guard in the actual executor as well: queued presentation must not silently change identity.
        command = "[ \"$(/system/bin/id -u)\" = " + executor.uid + " ] || { printf '%s\\n' 'Executor identity changed' >&2; exit 125; }; "
                + resolvedCommand;
        directory = resolvedDirectory;
        interactive = specification == null ? null : specification.withCommand(command);
    }

    DesktopLaunchRequest request() {
        return new DesktopLaunchRequest("RUN_COMMAND", "utilities-terminal", null, null, interactive,
                DesktopLaunchPresentation.automatic(), DesktopLaunchArguments.empty(), "");
    }
}
