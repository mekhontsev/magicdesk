package io.github.mekhontsev.magicdesk;

/** Device fixture encodes the shortcut editor's real recipe, without starting app services. */
public final class GuestRecipeFixture {
    public static void main(String[] args) {
        if (args.length != 6)
            throw new IllegalArgumentException("Expected protocol, store, XKB directory, name, guest command and receipt prefix");
        var environment = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.GUEST,
                args[1], DesktopExecBackend.SHELL, args[2]);
        var shortcut = LinuxLaunchRecipe.build(args[3], environment, args[4], "/", "",
                LinuxLaunchRecipe.Presentation.APPLICATION, GraphicalProtocol.parse(args[0]));
        String command = DesktopExecTemplate.expandArguments(shortcut.exec, DesktopLaunchArguments.empty(),
                shortcut.name, shortcut.icon, "");
        String prefix = args[5];
        String observed = "printf '0\\n' > " + ShellCommandLine.quote(prefix + ".started") + "; "
                + command + "; result=$?; printf '%s\\n' \"$result\" > "
                + ShellCommandLine.quote(prefix + ".exit") + "; exit \"$result\"";
        String exec = DesktopExecTemplate.encodeArguments(java.util.List.of("sh", "-c",
                "{ " + observed + "; } > " + ShellCommandLine.quote(prefix + ".log") + " 2>&1"));
        var observedShortcut = new DesktopApplicationShortcut(shortcut.name, shortcut.icon, exec, null, "",
                shortcut.launchMode, false, shortcut.execBackend, false).withGraphics(shortcut.graphics);
        System.out.print(DesktopEntryFile.encodeApplication(observedShortcut));
    }

    private GuestRecipeFixture() { }
}
